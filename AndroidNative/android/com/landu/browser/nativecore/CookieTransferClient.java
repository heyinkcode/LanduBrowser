package com.landu.browser.nativecore;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A single explicit Cookie transfer to an immutable owner/nonce browser ticket. */
public final class CookieTransferClient implements AutoCloseable {
    public interface Result { void complete(String json, String error); }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ParcelFileDescriptor[] pipes = new ParcelFileDescriptor[4];
    private ServiceConnection connection;
    private Result result;
    private volatile boolean closed;
    private boolean bound;

    public CookieTransferClient(Context context) { this.context = context; }

    public void start(EnvironmentLeaseStore.Ticket ticket, boolean importing, String json, Result result) {
        if (closed || this.result != null) throw new IllegalStateException("Cookie 传输已经开始");
        this.result = result;
        long deadline = SystemClock.elapsedRealtime() + 35_000;
        main.postDelayed(() -> finish(null, importing
                ? "Cookie 导入结果未确认，请先导出核对，勿重复导入"
                : "Cookie 导出超时，请稍后重试"), 35_000);
        try {
            ParcelFileDescriptor[] input = ParcelFileDescriptor.createReliablePipe();
            pipes[0] = input[0]; pipes[1] = input[1];
            ParcelFileDescriptor[] output = ParcelFileDescriptor.createReliablePipe();
            pipes[2] = output[0]; pipes[3] = output[1];
            Messenger reply = new Messenger(new Handler(Looper.getMainLooper(), message -> {
                if (!closed && message.sendingUid == Process.myUid() && message.what == LanduSessionService.FAILED)
                    finish(null, message.getData().getString("error", "Cookie 传输失败"));
                return true;
            }));
            connection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                    if (closed) return;
                    Message message = Message.obtain(null, importing
                            ? LanduSessionService.COOKIE_IMPORT : LanduSessionService.COOKIE_EXPORT);
                    Bundle data = new Bundle();
                    data.putString(NativeEnvironmentRuntime.EXTRA_ID, ticket.identity.id());
                    data.putString(NativeEnvironmentRuntime.EXTRA_NONCE, ticket.nonce);
                    data.putLong("cookieDeadline", deadline);
                    data.putParcelable("cookieInput", pipes[0]);
                    data.putParcelable("cookieOutput", pipes[3]);
                    message.setData(data); message.replyTo = reply;
                    try {
                        new Messenger(binder).send(message);
                        closePipe(0); closePipe(3);
                        io.execute(() -> {
                            String payload = null;
                            String error = null;
                            try {
                                if (importing) {
                                    try (var stream = new ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])) {
                                        CookieTransferStreams.write(stream, json);
                                    }
                                } else closePipe(1);
                                try (var stream = new ParcelFileDescriptor.AutoCloseInputStream(pipes[2])) {
                                    payload = CookieTransferStreams.read(stream);
                                    pipes[2].checkError();
                                }
                            } catch (IOException | RuntimeException failure) {
                                error = importing ? "Cookie 导入连接中断，结果未确认，请先导出核对"
                                        : "Cookie 导出连接中断，请稍后重试";
                            }
                            final String received = payload;
                            final String failure = error;
                            main.post(() -> finish(received, failure));
                        });
                    } catch (android.os.RemoteException | RuntimeException failure) {
                        finish(null, "无法连接当前环境的 Cookie 接口");
                    }
                }
                @Override public void onServiceDisconnected(ComponentName name) {
                    finish(null, importing ? "环境连接中断，导入结果未确认，请先导出核对" : "环境连接中断，未完成导出");
                }
                @Override public void onNullBinding(ComponentName name) { finish(null, "Cookie 接口不可用"); }
                @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
            };
            bound = context.bindService(new Intent(context, LanduSessionService.class), connection, Context.BIND_AUTO_CREATE);
            if (!bound) finish(null, "请先启动所选环境，再管理 Cookie");
        } catch (IOException | RuntimeException failure) { finish(null, "无法准备 Cookie 传输"); }
    }

    private void finish(String json, String error) {
        if (closed) return;
        Result callback = result;
        result = null;
        close();
        if (callback != null) callback.complete(json, error);
    }

    private void closePipe(int index) {
        ParcelFileDescriptor pipe = pipes[index];
        if (pipe != null) try { pipe.close(); } catch (IOException ignored) { }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        result = null;
        main.removeCallbacksAndMessages(null);
        for (int i = 0; i < pipes.length; i++) closePipe(i);
        if (bound && connection != null) {
            try { context.unbindService(connection); } catch (IllegalArgumentException ignored) { }
        }
        connection = null;
        bound = false;
        io.shutdownNow();
    }
}
