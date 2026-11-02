package com.landu.browser.nativecore;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.IOException;

// A private, nonce-validated routing activity; it never renders a custom launch screen.
@android.annotation.SuppressLint("CustomSplashScreen")
public final class LanduLaunchActivity extends Activity {
    private boolean notificationDispatched;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null && state.getBoolean("notificationDispatched", false)) {
            finish();
            return;
        }
        try {
            Intent request = getIntent();
            String identity = request.getStringExtra(NativeEnvironmentRuntime.EXTRA_ID);
            String nonce = request.getStringExtra(NativeEnvironmentRuntime.EXTRA_NONCE);
            EnvironmentDefinition environment = NativeEnvironmentRuntime.verifyLaunch(
                    identity, nonce);
            if (LanduNotificationRoute.isNotificationLaunch(request)) {
                Intent notification = LanduNotificationRoute.dispatch(getPackageName(), request, identity);
                notificationDispatched = true;
                startActivity(notification);
                NativeEnvironmentRuntime.noteBrowserLaunchDispatched(identity, nonce);
                finish();
                return;
            }
            String link = request.getStringExtra(LanduLinkActivity.EXTRA_LINK);
            boolean resume = NativeEnvironmentRuntime.shouldResumeWindow(identity, nonce,
                    request.getBooleanExtra(NativeEnvironmentRuntime.EXTRA_RESUME, false), link != null);
            if (resume && bringBrowserTaskForward()) {
                NativeEnvironmentRuntime.noteBrowserLaunchDispatched(identity, nonce);
                finish();
                return;
            }
            Intent browser;
            if (resume) {
                // A launcher intent brings the current tab forward without reopening the start page.
                browser = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            } else {
                String address = link == null ? environment.startPage : ExternalNavigation.webAddress(link);
                browser = new Intent(Intent.ACTION_VIEW, Uri.parse(address));
            }
            browser.setClassName(getPackageName(), "org.chromium.chrome.browser.ChromeTabbedActivity");
            browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(browser);
            NativeEnvironmentRuntime.noteBrowserLaunchDispatched(identity, nonce);
        } catch (IOException | RuntimeException error) {
            Toast.makeText(this, "环境启动失败，请返回岚渡环境管理重试", Toast.LENGTH_LONG).show();
            Intent manager = new Intent(this, LanduManagerActivity.class);
            manager.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(manager);
        }
        finish();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("notificationDispatched", notificationDispatched);
    }

    private boolean bringBrowserTaskForward() {
        try {
            ActivityManager manager = getSystemService(ActivityManager.class);
            if (manager == null) return false;
            for (ActivityManager.AppTask task : manager.getAppTasks()) {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                ComponentName base = info.baseActivity;
                if (base != null && base.getPackageName().equals(getPackageName())
                        && base.getClassName().equals("org.chromium.chrome.browser.ChromeTabbedActivity")) {
                    task.moveToFront();
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // The old task may have disappeared between observation and focus.
        }
        return false;
    }
}
