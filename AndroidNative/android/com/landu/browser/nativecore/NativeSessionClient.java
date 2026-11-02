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
import android.os.RemoteException;
import android.os.SystemClock;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

public final class NativeSessionClient implements AutoCloseable {
    public interface Callback { void complete(String error); }
    private final Context context;
    private final EnvironmentLeaseStore store;
    private final ExecutorService worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ServiceConnection connection;
    private Callback callback;
    private volatile boolean closed;
    private long deadline;
    private long requestSequence;

    public NativeSessionClient(Context context, EnvironmentLeaseStore store, ExecutorService worker) {
        this.context = context;
        this.store = store;
        this.worker = worker;
    }

    public void stop(EnvironmentLeaseStore.Ticket ticket, Callback callback) {
        if (closed || this.callback != null) throw new IllegalStateException("已有环境关闭请求");
        this.callback = callback;
        final long requestId = ++requestSequence;
        // Allow initialization followed by the existing 12-second save barrier.
        deadline = SystemClock.elapsedRealtime() + 30_000;
        final Messenger[] remote = new Messenger[1];
        final boolean[] resumedForStop = {false};
        final Runnable[] send = new Runnable[1];
        Messenger response = new Messenger(new Handler(Looper.getMainLooper(), reply -> {
            if (!current(requestId)) return true;
            if (reply.what == LanduSessionService.FAILED) {
                finish(reply.getData().getString("error", "环境关闭失败"));
            } else if (reply.what == LanduSessionService.STOP_ACCEPTED) {
                unbind();
            } else if (reply.what == LanduSessionService.PREPARING) {
                if (!resumedForStop[0]) {
                    resumedForStop[0] = true;
                    Intent resume = new Intent(context, LanduLaunchActivity.class)
                            .putExtra(NativeEnvironmentRuntime.EXTRA_ID, ticket.identity.id())
                            .putExtra(NativeEnvironmentRuntime.EXTRA_NONCE, ticket.nonce)
                            .putExtra(NativeEnvironmentRuntime.EXTRA_RESUME, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { context.startActivity(resume); }
                    catch (RuntimeException error) {
                        finish("无法继续初始化当前环境，请打开浏览器后重试关闭");
                        return true;
                    }
                }
                main.postDelayed(() -> {
                    if (current(requestId)) send[0].run();
                }, 250);
            }
            return true;
        }));
        send[0] = () -> {
            if (!current(requestId) || remote[0] == null) return;
            if (SystemClock.elapsedRealtime() >= deadline) {
                finish("环境尚未准备好关闭，请稍后重试；未强制结束进程");
                return;
            }
            Message request = Message.obtain(null, LanduSessionService.STOP);
            Bundle data = new Bundle();
            data.putString(NativeEnvironmentRuntime.EXTRA_ID, ticket.identity.id());
            data.putString(NativeEnvironmentRuntime.EXTRA_NONCE, ticket.nonce);
            data.putLong("com.landu.browser.environment.STOP_DEADLINE", deadline);
            request.setData(data);
            request.replyTo = response;
            try { remote[0].send(request); }
            catch (RemoteException error) { checkExited(); }
        };
        connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                if (!current(requestId)) return;
                remote[0] = new Messenger(binder);
                send[0].run();
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                if (current(requestId)) { unbind(); checkExited(); }
            }
            @Override public void onNullBinding(ComponentName name) {
                if (current(requestId)) finish("内核关闭接口不可用");
            }
            @Override public void onBindingDied(ComponentName name) {
                if (current(requestId)) { unbind(); checkExited(); }
            }
        };
        try {
            if (!context.bindService(new Intent(context, LanduSessionService.class), connection, Context.BIND_AUTO_CREATE)) {
                finish("无法连接当前环境");
                return;
            }
        } catch (RuntimeException error) {
            finish("无法连接当前环境，请稍后重试");
            return;
        }
        checkExited();
    }

    private boolean checking;

    private boolean current(long requestId) {
        return !closed && callback != null && requestSequence == requestId;
    }

    private void checkExited() {
        if (closed || callback == null || checking) return;
        checking = true;
        final long requestId = requestSequence;
        try { worker.execute(() -> {
            boolean running;
            String error = null;
            try { running = store.isBrowserRunning(); }
            catch (IOException | RuntimeException failure) { running = true; error = "无法确认原环境是否退出，暂不切换"; }
            final boolean stillRunning = running;
            final String failure = error;
            main.post(() -> {
                if (!current(requestId)) return;
                checking = false;
                if (failure != null) finish(failure);
                else if (!stillRunning) finish(null);
                else if (SystemClock.elapsedRealtime() >= deadline) finish("环境尚未退出，请稍后重试；未强制结束进程");
                else main.postDelayed(() -> { if (current(requestId)) checkExited(); }, 150);
            });
        }); } catch (RuntimeException error) {
            checking = false;
            finish("环境退出检查暂不可用，请重新打开管理页面");
        }
    }

    private void finish(String error) {
        Callback complete = callback;
        callback = null;
        checking = false;
        requestSequence++;
        main.removeCallbacksAndMessages(null);
        unbind();
        if (!closed && complete != null) complete.complete(error);
    }

    private void unbind() {
        if (connection != null) {
            try { context.unbindService(connection); } catch (IllegalArgumentException ignored) { }
            connection = null;
        }
    }

    @Override
    public void close() {
        closed = true;
        requestSequence++;
        callback = null;
        main.removeCallbacksAndMessages(null);
        unbind();
    }
}
