package com.landu.browser.nativecore;

import android.content.Context;

import org.chromium.base.PathUtils;
import org.chromium.base.CommandLine;
import org.chromium.base.IntentUtils;

import java.nio.file.Path;

public final class LanduEnvironmentBootstrap {
    private static final Object LOCK = new Object();
    private static boolean notificationIdsBound;
    private LanduEnvironmentBootstrap() {}

    public static Context attach(Context context) {
        synchronized (LOCK) {
            Context isolated = NativeEnvironmentRuntime.attach(context);
            org.chromium.net.AndroidNetworkLibrary.bindLanduSocks5Credentials(NativeEnvironmentRuntime::socks5Credentials);
            IntentUtils.LanduEnvironment.initialize(NativeEnvironmentRuntime.environmentId());
            IntentUtils.LanduEnvironment.bindNotificationDispatchGate(
                    NativeEnvironmentRuntime.environmentId(), NativeEnvironmentRuntime::notificationsAllowed);
            if (!notificationIdsBound) {
                bindNotificationIds(context, NativeEnvironmentRuntime.environmentId());
                notificationIdsBound = true;
            }
            return isolated;
        }
    }

    private static void bindNotificationIds(Context originalContext, String owner) {
        try {
            // Android's app mount namespace may alias /data/user/0 to /data/data.
            // Resolve only the framework-owned root; keep our child link checks intact.
            Path applicationRoot = originalContext.getNoBackupFilesDir().toPath().toRealPath();
            NotificationIdStore store = new NotificationIdStore(
                    applicationRoot.resolve("landu-control").resolve("notifications"));
            android.app.NotificationManager manager = originalContext.getSystemService(android.app.NotificationManager.class);
            if (manager == null) throw new IllegalStateException("Android notification manager is unavailable");
            IntentUtils.LanduEnvironment.bindNotificationIds(owner, new IntentUtils.LanduEnvironment.NotificationIds() {
                @Override public int getOrCreate(int logicalId) {
                    java.util.Set<Integer> occupied = new java.util.HashSet<>();
                    for (android.service.notification.StatusBarNotification notification : manager.getActiveNotifications()) {
                        if (notification.getTag() == null) occupied.add(notification.getId());
                    }
                    try { return store.getOrCreate(owner, logicalId, occupied::contains); }
                    catch (java.io.IOException error) { throw new IllegalStateException("Cannot allocate notification ID", error); }
                }
                @Override public int find(int logicalId) {
                    try { return store.find(owner, logicalId); }
                    catch (java.io.IOException error) { throw new IllegalStateException("Cannot read notification ID", error); }
                }
                @Override public boolean owns(int platformId) {
                    try { return store.owns(owner, platformId); }
                    catch (java.io.IOException error) { throw new IllegalStateException("Cannot verify notification owner", error); }
                }
            });
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot initialize notification registry", error);
        }
    }

    public static void initializePaths(Context context) {
        synchronized (LOCK) {
            Path directory = NativeEnvironmentRuntime.directory();
            PathUtils.setPrivateDirectoryPath(directory.toString(),
                    directory.resolve("cache").toString(), "profile", null);
            FingerprintSettings settings = NativeEnvironmentRuntime.fingerprint();
            CommandLine command = CommandLine.getInstance();
            BrowserCommandLineTarget target = new BrowserCommandLineTarget() {
                @Override public void remove(String name) { command.removeSwitch(name); }
                @Override public void set(String name, String value) { command.appendSwitchWithValue(name, value); }
            };
            target.set("landu-environment-id", NativeEnvironmentRuntime.environmentId());
            NativeEnvironmentRuntime.network().applyTo(target);
            NativeEnvironmentRuntime.region().applyTo(target, NativeEnvironmentRuntime.deviceLanguages(context));
            // The full configured language list must agree with request headers in all realms.
            // Preserve unrelated feature overrides; Chromium otherwise reduces navigator.languages.
            String disabledFeatures = command.getSwitchValue("disable-features");
            boolean languageReductionDisabled = false;
            if (disabledFeatures != null) {
                for (String feature : disabledFeatures.split(",")) {
                    if ("ReduceAcceptLanguage".equals(feature.trim().split("[<:]", 2)[0])) {
                        languageReductionDisabled = true;
                        break;
                    }
                }
            }
            if (!languageReductionDisabled) {
                command.appendSwitchWithValue("disable-features",
                        disabledFeatures == null || disabledFeatures.isEmpty() ? "ReduceAcceptLanguage"
                                : disabledFeatures + ",ReduceAcceptLanguage");
            }
            NativeEnvironmentRuntime.androidIdentity().applyTo(target);
            NativeEnvironmentRuntime.geolocation().applyTo(target);
            settings.applyTo(target);
        }
    }

}
