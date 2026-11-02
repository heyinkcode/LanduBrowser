package com.landu.browser.nativecore;

import android.app.Application;
import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.nio.file.Path;

public final class NativeEnvironmentRuntime {
    private static final Object LOCK = new Object();
    public static final String MANAGER_SUFFIX = ":landu_manager";
    public static final String EXTRA_ID = "com.landu.browser.environment.ID";
    public static final String EXTRA_NONCE = "com.landu.browser.environment.NONCE";
    public static final String EXTRA_RESUME = "com.landu.browser.environment.RESUME";
    private static EnvironmentLeaseStore.Session session;
    private static Path controlRoot;
    private static EnvironmentDefinition selectedEnvironment;
    private static ProxyAuthentication proxyAuthentication;
    private static boolean browserLaunchDispatched;
    public interface FlushHandler { void flush(EnvironmentShutdownCoordinator.Result result); }
    private static EnvironmentShutdownCoordinator shutdown;
    private static java.util.function.BooleanSupplier nativeInitializationComplete;
    public interface CookieHandler {
        void transfer(boolean importing, String json, java.util.function.Consumer<String> result);
    }
    private static CookieHandler cookieHandler;
    private static boolean cookieTransferBusy;

    public static void registerCookieHandler(CookieHandler handler) {
        synchronized (LOCK) { cookieHandler = java.util.Objects.requireNonNull(handler); }
    }

    public static void requestCookies(String identity, String nonce, boolean importing,
            String json, java.util.function.Consumer<String> result) throws IOException {
        synchronized (LOCK) {
            if (!stopHandlerReady(identity, nonce) || cookieHandler == null)
                throw new IOException("请先打开当前环境，等待内核初始化完成");
            if (shutdown.isStopping() || cookieTransferBusy)
                throw new IOException("环境正在保存或传输 Cookie，请稍后重试");
            cookieTransferBusy = true;
            java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                cookieHandler.transfer(importing, json, value -> {
                    synchronized (LOCK) {
                        if (!completed.compareAndSet(false, true)) return;
                        cookieTransferBusy = false;
                    }
                    result.accept(value);
                });
            } catch (RuntimeException failure) {
                if (completed.compareAndSet(false, true)) cookieTransferBusy = false;
                throw new IOException("Cookie 接口暂不可用，请重新打开环境后重试");
            }
        }
    }
    private static final EnvironmentSaveBarrier windowSaves = new EnvironmentSaveBarrier();

    private NativeEnvironmentRuntime() {}

    public static boolean isManagerProcess() {
        return Application.getProcessName().endsWith(MANAGER_SUFFIX);
    }

    public static Context attach(Context context) {
        synchronized (LOCK) {
            if (session != null) throw new IllegalStateException("Browser environment already initialized");
            Path privateRoot = context.getNoBackupFilesDir().toPath();
            controlRoot = privateRoot.resolve("landu-control");
            try {
                EnvironmentLeaseStore store = new EnvironmentLeaseStore(controlRoot);
                session = store.claimSelected(privateRoot.resolve("landu-environments"));
                selectedEnvironment = new EnvironmentCatalog(controlRoot).read().find(session.ticket().identity);
                EnvironmentRegionalLaunchStore regional = new EnvironmentRegionalLaunchStore(controlRoot);
                try {
                    selectedEnvironment = selectedEnvironment.withResolvedRegionalSettings(
                            regional.read(selectedEnvironment, session.ticket()).resolved);
                } catch (IOException unavailable) {
                    // Old manual configurations still work without a receipt. Automatic ones must
                    // be prepared by the manager before this process binds any native switches.
                    if (selectedEnvironment.regionalPolicy.needsExitLookup()) throw unavailable;
                }
                if (selectedEnvironment.network.requiresAuthentication) {
                    ProxyCredentials credentials = AndroidProxyCredentials.open(controlRoot)
                            .read(selectedEnvironment.identity, selectedEnvironment.network);
                    proxyAuthentication = new ProxyAuthentication(selectedEnvironment.network, credentials);
                }
                return new IsolatedEnvironmentContext(context, session);
            } catch (IOException error) {
                throw new IllegalStateException("Select an available Landu environment before starting Chromium", error);
            }
        }
    }

    public static Context wrapActivity(Context context) {
        synchronized (LOCK) {
            if (session == null) throw new IllegalStateException("Native environment is unavailable");
            return new IsolatedEnvironmentContext(context, session);
        }
    }

    /** A notification arriving during a flush must not mutate the session being saved. */
    public static boolean notificationsAllowed() {
        synchronized (LOCK) {
            return session != null && (shutdown == null || !shutdown.isStopping());
        }
    }

    public static Path directory() {
        synchronized (LOCK) {
            if (session == null) throw new IllegalStateException("Native environment is unavailable");
            return session.directory();
        }
    }

    public static String environmentId() {
        synchronized (LOCK) {
            if (session == null) throw new IllegalStateException("Native environment is unavailable");
            return session.ticket().identity.id();
        }
    }

    public static FingerprintSettings fingerprint() {
        synchronized (LOCK) {
            if (selectedEnvironment == null) throw new IllegalStateException("Native environment is unavailable");
            return selectedEnvironment.fingerprint;
        }
    }

    public static EnvironmentNetworkSettings network() {
        synchronized (LOCK) {
            if (selectedEnvironment == null) throw new IllegalStateException("Native environment is unavailable");
            return selectedEnvironment.network;
        }
    }

    public static EnvironmentRegionSettings region() {
        synchronized (LOCK) {
            if (selectedEnvironment == null) throw new IllegalStateException("Native environment is unavailable");
            return selectedEnvironment.region;
        }
    }

    public static ProxyCredentials proxyCredentials(boolean isProxy, String scheme,
            String host, int port, String authenticationScheme) {
        synchronized (LOCK) {
            if (session == null || proxyAuthentication == null
                    || (shutdown != null && shutdown.isStopping())) return null;
            return proxyAuthentication.answer(isProxy, scheme, host, port, authenticationScheme);
        }
    }

    /** Empty means no authentication, one entry means deny, two entries are an exact endpoint secret. */
    public static String[] socks5Credentials(String host, int port) {
        synchronized (LOCK) {
            if (selectedEnvironment == null || !selectedEnvironment.network.requiresAuthentication
                    || !selectedEnvironment.network.proxyUri.startsWith("socks5://")) return new String[0];
            if (session == null || proxyAuthentication == null || (shutdown != null && shutdown.isStopping())) return new String[]{""};
            ProxyCredentials credentials = proxyAuthentication.socks5(host, port);
            return credentials == null ? new String[]{""} : new String[]{credentials.username, credentials.password};
        }
    }

    public static EnvironmentGeolocationSettings geolocation() {
        synchronized (LOCK) {
            if (selectedEnvironment == null) throw new IllegalStateException("Native environment is unavailable");
            return selectedEnvironment.geolocation;
        }
    }

    public static EnvironmentAndroidIdentity androidIdentity() {
        synchronized (LOCK) {
            if (selectedEnvironment == null) throw new IllegalStateException("Native environment is unavailable");
            return selectedEnvironment.androidIdentity;
        }
    }

    public static String deviceLanguages(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                LocaleManager manager = context.getSystemService(LocaleManager.class);
                android.os.LocaleList locales = manager == null ? null : manager.getSystemLocales();
                if (locales != null && !locales.isEmpty()) {
                    return locales.toLanguageTags();
                }
            } catch (RuntimeException ignored) { }
        }
        String languages = Resources.getSystem().getConfiguration().getLocales().toLanguageTags();
        return languages.isEmpty() ? java.util.Locale.getDefault().toLanguageTag() : languages;
    }

    public static EnvironmentDefinition verifyLaunch(String identity, String nonce) throws IOException {
        synchronized (LOCK) {
            if (session == null) throw new IOException("Native environment is unavailable");
            if (shutdown != null && shutdown.isStopping()) throw new IOException("环境正在关闭，请稍后重试");
            session.verifyLaunch(identity, nonce);
            // Edits made in the manager take effect on the next process launch.
            // Navigation in this live session must use its immutable startup snapshot.
            if (selectedEnvironment == null) throw new IOException("Native environment is unavailable");
            return selectedEnvironment;
        }
    }

    public static boolean shouldResumeWindow(String identity, String nonce,
            boolean requestedResume, boolean hasExternalLink) throws IOException {
        synchronized (LOCK) {
            verifyLaunch(identity, nonce);
            return !hasExternalLink && (requestedResume || browserLaunchDispatched);
        }
    }

    public static void noteBrowserLaunchDispatched(String identity, String nonce) throws IOException {
        synchronized (LOCK) {
            if (session == null) throw new IOException("Native environment is unavailable");
            session.verifyLaunch(identity, nonce);
            browserLaunchDispatched = true;
        }
    }

    public static void registerStopHandler(FlushHandler flush, Runnable terminate,
            java.util.function.BooleanSupplier initialized) {
        synchronized (LOCK) {
            if (shutdown != null) return;
            nativeInitializationComplete = java.util.Objects.requireNonNull(initialized);
            Handler main = new Handler(Looper.getMainLooper());
            shutdown = new EnvironmentShutdownCoordinator(new EnvironmentShutdownCoordinator.Operations() {
                @Override public void blockBackgroundRestart() throws IOException { session.preventBackgroundRestart(); }
                @Override public void restoreAfterFailure() throws IOException { session.restoreAfterFailedStop(); }
                @Override public void flush(EnvironmentShutdownCoordinator.Result result) {
                    final long expectedWindows = windowSaves.revision();
                    windowSaves.saveAll(new EnvironmentShutdownCoordinator.Result() {
                        @Override public boolean isPending() { return result.isPending(); }
                        @Override public void complete(String error) {
                            main.post(() -> {
                                if (!result.isPending()) return;
                                if (error != null) { result.complete(error); return; }
                                if (!windowSaves.unchanged(expectedWindows)) {
                                    result.complete("浏览器窗口状态已变化，请重新关闭环境");
                                    return;
                                }
                                try { flush.flush(failure -> main.post(() -> {
                                    if (!result.isPending()) return;
                                    result.complete(failure != null ? failure : windowSaves.unchanged(expectedWindows)
                                            ? null : "浏览器窗口状态已变化，请重新关闭环境");
                                })); }
                                catch (RuntimeException failure) { result.complete("数据保存失败，环境仍保持打开"); }
                            });
                        }
                    });
                }
                @Override public void terminate() { terminate.run(); }
            }, (action, delay) -> {
                main.postDelayed(action, delay);
                return () -> main.removeCallbacks(action);
            });
        }
    }

    public static void registerWindowSave(Object owner, EnvironmentSaveBarrier.Save save) {
        windowSaves.register(owner, save);
    }

    public static void unregisterWindowSave(Object owner) {
        windowSaves.unregister(owner);
    }

    public static boolean stopHandlerReady(String identity, String nonce) throws IOException {
        synchronized (LOCK) {
            if (session == null) throw new IOException("Native environment is unavailable");
            session.verifyLaunch(identity, nonce);
            return shutdown != null && nativeInitializationComplete != null
                    && nativeInitializationComplete.getAsBoolean();
        }
    }

    public static void requestStop(String identity, String nonce,
            EnvironmentShutdownCoordinator.Result result) throws IOException {
        synchronized (LOCK) {
            if (!stopHandlerReady(identity, nonce)) throw new IOException("内核尚未准备好，请稍后重试");
            if (cookieTransferBusy) throw new IOException("正在传输 Cookie，请完成后再关闭环境");
            shutdown.request(result);
        }
    }
}
