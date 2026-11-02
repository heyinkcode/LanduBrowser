package com.landu.browser.nativecore;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;

import java.io.IOException;

public final class LanduSessionService extends Service {
    public static final int STOP = 1;
    public static final int STOP_ACCEPTED = 2;
    public static final int FAILED = 3;
    public static final int PREPARING = 4;
    public static final int COOKIE_IMPORT = 5;
    public static final int COOKIE_EXPORT = 6;
    private final CookieTransferService cookies = new CookieTransferService();
    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        if (message.sendingUid != Process.myUid() || message.replyTo == null) return true;
        if (message.what == COOKIE_IMPORT || message.what == COOKIE_EXPORT) {
            cookies.handle(message);
            return true;
        }
        if (message.what != STOP) return true;
        Messenger response = message.replyTo;
        try {
            String identity = message.getData().getString(NativeEnvironmentRuntime.EXTRA_ID);
            String nonce = message.getData().getString(NativeEnvironmentRuntime.EXTRA_NONCE);
            // A queued binder request cannot begin a 12-second flush after the
            // manager has timed out. Both processes use the same elapsed clock.
            long deadline = message.getData().getLong("com.landu.browser.environment.STOP_DEADLINE", 0);
            if (deadline - SystemClock.elapsedRealtime() < 13_000) {
                throw new IOException("关闭请求已过期，环境仍保持打开，请重试");
            }
            // A paused activity may not have drawn its first frame yet. Validate the
            // owner before asking the manager to resume that same browser window.
            if (!NativeEnvironmentRuntime.stopHandlerReady(identity, nonce)) {
                try { response.send(Message.obtain(null, PREPARING)); }
                catch (RemoteException ignored) { }
                return true;
            }
            NativeEnvironmentRuntime.requestStop(
                    identity, nonce,
                    error -> respond(response, error));
        } catch (IOException | RuntimeException error) {
            respond(response, error.getMessage() == null ? "环境关闭失败" : error.getMessage());
        }
        return true;
    }));

    private void respond(Messenger response, String error) {
        Message reply = Message.obtain(null, error == null ? STOP_ACCEPTED : FAILED);
        Bundle data = new Bundle();
        if (error != null) data.putString("error", error);
        reply.setData(data);
        try { response.send(reply); } catch (RemoteException ignored) { }
    }

    @Override
    public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_NOT_STICKY; }

    @Override public void onDestroy() {
        cookies.close();
        super.onDestroy();
    }
}
