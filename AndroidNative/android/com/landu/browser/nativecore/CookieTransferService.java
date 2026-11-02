package com.landu.browser.nativecore;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Private same-UID service plumbing. Bulk JSON travels over bounded pipes. */
final class CookieTransferService implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<Pending> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    @SuppressWarnings("deprecation")
    void handle(Message message) {
        Bundle data = message.getData();
        ParcelFileDescriptor input = data.getParcelable("cookieInput");
        ParcelFileDescriptor output = data.getParcelable("cookieOutput");
        Pending request = new Pending(message.replyTo, input, output);
        pending.add(request);
        String identity = data.getString(NativeEnvironmentRuntime.EXTRA_ID);
        String nonce = data.getString(NativeEnvironmentRuntime.EXTRA_NONCE);
        long deadline = data.getLong("cookieDeadline", 0);
        boolean importing = message.what == LanduSessionService.COOKIE_IMPORT;
        try {
            if (closed || input == null || output == null || !validDeadline(deadline)
                    || !NativeEnvironmentRuntime.stopHandlerReady(identity, nonce))
                throw new IOException("请打开所选环境并等待内核就绪，再管理 Cookie");
            io.execute(() -> {
                String json = "";
                try {
                    if (importing) {
                        try (var stream = new ParcelFileDescriptor.AutoCloseInputStream(input)) {
                            json = CookieTransferStreams.read(stream);
                            input.checkError();
                        }
                    } else input.close();
                    String payload = json;
                    main.post(() -> {
                        if (closed || request.finished.get()) { request.close(); return; }
                        try {
                            if (!validDeadline(deadline)) throw new IOException("Cookie 请求已过期，未开始写入");
                            NativeEnvironmentRuntime.requestCookies(identity, nonce, importing, payload, result -> {
                                try { io.execute(() -> request.write(result)); }
                                catch (RuntimeException error) { request.fail("Cookie 结果未确认，请先导出核对"); }
                            });
                        } catch (IOException | RuntimeException error) {
                            request.fail(error instanceof IOException ? error.getMessage() : "Cookie 接口不可用");
                        }
                    });
                } catch (IOException | RuntimeException error) { request.fail("Cookie 输入无效或传输中断，未开始写入"); }
            });
        } catch (IOException | RuntimeException error) {
            request.fail(error instanceof IOException ? error.getMessage() : "Cookie 接口不可用");
        }
    }

    private static boolean validDeadline(long deadline) {
        long remaining = deadline - SystemClock.elapsedRealtime();
        return remaining >= 28_000 && remaining <= 35_000;
    }

    private final class Pending implements AutoCloseable {
        final Messenger reply;
        final ParcelFileDescriptor input;
        final ParcelFileDescriptor output;
        final AtomicBoolean finished = new AtomicBoolean();
        Pending(Messenger reply, ParcelFileDescriptor input, ParcelFileDescriptor output) {
            this.reply = reply; this.input = input; this.output = output;
        }
        void write(String json) {
            if (finished.get()) return;
            try (var stream = new ParcelFileDescriptor.AutoCloseOutputStream(output)) {
                CookieTransferStreams.write(stream, json);
            } catch (IOException | RuntimeException error) {
                fail("Cookie 结果传输中断，请先导出核对");
                return;
            }
            close();
        }
        void fail(String error) {
            if (finished.get()) return;
            Message response = Message.obtain(null, LanduSessionService.FAILED);
            Bundle data = new Bundle();
            data.putString("error", error == null ? "Cookie 传输失败" : error);
            response.setData(data);
            try { reply.send(response); } catch (android.os.RemoteException ignored) { }
            close();
        }
        @Override public void close() {
            if (!finished.compareAndSet(false, true)) return;
            for (ParcelFileDescriptor descriptor : new ParcelFileDescriptor[] {input, output})
                if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) { }
            pending.remove(this);
        }
    }

    @Override public void close() {
        closed = true;
        for (Pending request : pending) request.close();
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }
}
