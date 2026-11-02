package com.landu.browser.nativecore;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runs in :landu_manager, before any Chromium process chooses its environment. */
public final class LanduNotificationActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LanduNotificationRoute.Entry entry;
    private EnvironmentLeaseStore leases;
    private NativeSessionClient sessionClient;
    private TextView message;
    private Button primary;
    private Button cancel;
    private boolean resumed;
    private boolean busy;
    private boolean dispatched;
    private Runnable whenResumed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null && state.getBoolean("dispatched", false)) {
            finish();
            return;
        }
        showShell();
        try {
            entry = LanduNotificationRoute.read(getPackageName(), getIntent());
            attemptOpen();
        } catch (RuntimeException error) {
            showFailure("无法识别此通知，请重新打开所属环境", false);
        }
    }

    private void showShell() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(0xfff6f8fc);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            shell.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(24), dp(24), dp(24), dp(24));
        content.addView(text("打开环境通知", 22, 500, 0xff1d2433), row(0));
        message = text("正在查找通知所属环境…", 14, 300, 0xff626d82);
        content.addView(message, row(16));
        primary = button("正在准备…", true);
        primary.setEnabled(false);
        content.addView(primary, buttonRow(24));
        cancel = button("取消", false);
        cancel.setOnClickListener(view -> finish());
        content.addView(cancel, buttonRow(12));
        scroll.addView(content);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        setContentView(shell);
    }

    private void attemptOpen() {
        if (busy || dispatched || isFinishing() || isDestroyed()) return;
        setBusy("正在打开通知所属环境…");
        worker.execute(() -> {
            try {
                Path control = getNoBackupFilesDir().toPath().resolve("landu-control");
                EnvironmentCatalog.Snapshot catalog = new EnvironmentCatalog(control).read();
                EnvironmentDefinition target = catalog.find(entry.identity);
                if (leases == null) leases = new EnvironmentLeaseStore(control);
                try {
                    EnvironmentLeaseStore.LaunchRequest request = AndroidRegionalLaunch.prepare(this, leases, entry.identity);
                    onVisible(() -> dispatch(request));
                } catch (EnvironmentLeaseStore.BusyException occupied) {
                    EnvironmentLeaseStore.Observation current = leases.observe();
                    String currentName = "当前环境";
                    if (current.ticket != null) {
                        try { currentName = catalog.find(current.ticket.identity).name; }
                        catch (IOException ignored) { }
                    }
                    String displayName = currentName;
                    onVisible(() -> showOccupied(target.name, displayName, current));
                }
            } catch (IOException | RuntimeException error) {
                onVisible(() -> showFailure("无法打开所属环境：" + error.getMessage(), true));
            }
        });
    }

    private void dispatch(EnvironmentLeaseStore.LaunchRequest request) {
        if (dispatched || isFinishing() || isDestroyed()) return;
        try {
            Intent launch = entry.launch(this, request);
            dispatched = true;
            startActivity(launch);
            finish();
        } catch (RuntimeException error) {
            dispatched = false;
            showFailure("启动未完成，请重试；也可以返回环境管理", true);
        }
    }

    private void showOccupied(String targetName, String currentName,
            EnvironmentLeaseStore.Observation current) {
        busy = false;
        cancel.setEnabled(true);
        boolean other = current.ticket != null && !current.ticket.identity.equals(entry.identity);
        if (other && current.status == EnvironmentLeaseStore.Status.RUNNING) {
            message.setText(getString(org.chromium.chrome.base.R.string.landu_notification_switch, targetName, currentName));
            primary.setText("保存并切换");
            primary.setEnabled(true);
            primary.setOnClickListener(view -> switchEnvironment(current.ticket, false));
        } else if (other && !current.processRunning
                && current.status == EnvironmentLeaseStore.Status.PENDING) {
            message.setText(getString(org.chromium.chrome.base.R.string.landu_notification_pending, currentName, targetName));
            primary.setText("取消待启动并打开通知");
            primary.setEnabled(true);
            primary.setOnClickListener(view -> switchEnvironment(current.ticket, true));
        } else {
            showFailure("环境正在启动或关闭，请稍后重试", true);
        }
    }

    private void switchEnvironment(EnvironmentLeaseStore.Ticket confirmed, boolean pending) {
        if (busy || dispatched || isFinishing() || isDestroyed()) return;
        setBusy(pending ? "正在取消待启动请求…" : "正在保存并关闭原环境…");
        worker.execute(() -> {
            try {
                EnvironmentLeaseStore.Observation current = leases.observe();
                if (!sameTicket(confirmed, current.ticket)
                        || current.processRunning == pending
                        || current.status != (pending ? EnvironmentLeaseStore.Status.PENDING
                                                     : EnvironmentLeaseStore.Status.RUNNING)) {
                    throw new IOException("运行环境已变化，请重新确认切换");
                }
                if (pending) {
                    leases.cancelPending(confirmed);
                    onVisible(() -> { busy = false; attemptOpen(); });
                } else {
                    onVisible(() -> {
                        if (sessionClient == null) sessionClient = new NativeSessionClient(this, leases, worker);
                        sessionClient.stop(confirmed, error -> onVisible(() -> {
                            busy = false;
                            if (error != null) showFailure(error, true);
                            else attemptOpen();
                        }));
                    });
                }
            } catch (IOException | RuntimeException error) {
                onVisible(() -> showFailure(error.getMessage(), true));
            }
        });
    }

    private static boolean sameTicket(EnvironmentLeaseStore.Ticket left, EnvironmentLeaseStore.Ticket right) {
        return right != null && left.identity.equals(right.identity) && left.nonce.equals(right.nonce);
    }

    private void setBusy(String status) {
        busy = true;
        message.setText(status);
        primary.setText("正在处理…");
        primary.setEnabled(false);
        cancel.setEnabled(false);
    }

    private void showFailure(String status, boolean retry) {
        busy = false;
        message.setText(status == null ? "暂时无法打开通知，请返回环境管理" : status);
        primary.setText("重试");
        primary.setEnabled(retry);
        primary.setOnClickListener(view -> attemptOpen());
        cancel.setEnabled(true);
    }

    private void onVisible(Runnable action) {
        main.post(() -> {
            if (isDestroyed() || isFinishing() || dispatched) return;
            whenResumed = action;
            runVisibleAction();
        });
    }

    private void runVisibleAction() {
        if (!resumed || whenResumed == null || isFinishing() || isDestroyed() || dispatched) return;
        Runnable action = whenResumed;
        whenResumed = null;
        action.run();
    }

    @Override protected void onResume() { super.onResume(); resumed = true; runVisibleAction(); }
    @Override protected void onPause() { resumed = false; super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("dispatched", dispatched);
    }
    @Override protected void onDestroy() {
        resumed = false;
        whenResumed = null;
        main.removeCallbacksAndMessages(null);
        if (sessionClient != null) sessionClient.close();
        worker.shutdown();
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private LinearLayout.LayoutParams row(int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(margin);
        return params;
    }
    private LinearLayout.LayoutParams buttonRow(int margin) {
        LinearLayout.LayoutParams params = row(margin);
        params.height = dp(48);
        return params;
    }
    private TextView text(String value, int size, int weight, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(Typeface.DEFAULT, weight, false));
        view.setLineSpacing(dp(4), 1);
        return view;
    }
    private Button button(String value, boolean filled) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.create(Typeface.DEFAULT, 400, false));
        button.setTextColor(filled ? 0xffffffff : 0xff1d2433);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(filled ? 0xff1677ff : 0xffffffff);
        shape.setCornerRadius(dp(12));
        if (!filled) shape.setStroke(dp(1), 0xffdce2ec);
        // 系统触摸反馈随系统动效设置运行，处理过程中保持按钮布局稳定。
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), shape, null));
        return button;
    }
}
