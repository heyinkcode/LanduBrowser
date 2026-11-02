package com.landu.browser.nativecore;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.job.JobWorkItem;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.chromium.base.IntentUtils;
import org.chromium.base.Log;

/** Notification broadcasts enter the manager without initializing any browser profile. */
public final class LanduNotificationReceiver extends BroadcastReceiver {
    static final String ACTION = "notifications.NotificationIntentInterceptor.INTENT_ACTION";
    static final String TYPE = "notifications.NotificationIntentInterceptor.EXTRA_INTENT_TYPE";
    static final String NAMESPACE = "com.landu.browser.notification-route.";
    static final int JOB_ID = 1;
    static final String TARGET =
            "org.chromium.chrome.browser.notifications.NotificationIntentInterceptor$Receiver";

    static EnvironmentIdentity owner(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            throw new IllegalArgumentException("Invalid notification action");
        }
        ComponentName component = intent.getComponent();
        String id = IntentUtils.LanduEnvironment.ownerOf(intent);
        int type = intent.getIntExtra(TYPE, -1);
        if (id == null || component == null
                || !context.getPackageName().equals(component.getPackageName())
                || !LanduNotificationReceiver.class.getName().equals(component.getClassName())
                || type < 0 || type > 2) {
            throw new IllegalArgumentException("Invalid notification owner or route");
        }
        return EnvironmentIdentity.parse(id);
    }

    static Intent copy(Context context, Intent original) {
        owner(context, original);
        Intent result = new Intent(original);
        result.setSelector(null);
        result.setDataAndType(null, null);
        result.setPackage(context.getPackageName());
        // Retain RemoteInput extras and ClipData, not caller-controlled activity/URI grant flags.
        result.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        return result;
    }

    static int enqueue(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT < 34) return JobScheduler.RESULT_FAILURE;
        EnvironmentIdentity identity = owner(context, intent);
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return JobScheduler.RESULT_FAILURE;
        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, LanduNotificationJobService.class))
                .setMinimumLatency(1)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build();
        return scheduler.forNamespace(NAMESPACE + identity.id())
                .enqueue(job, new JobWorkItem(copy(context, intent)));
    }

    @Override public void onReceive(Context context, Intent intent) {
        try {
            if (enqueue(context, intent) != JobScheduler.RESULT_SUCCESS) {
                Log.w("LanduNotification", "Android could not queue this notification operation");
            }
        } catch (RuntimeException error) {
            // Do not log notification text, reply payloads or account-specific identifiers.
            Log.w("LanduNotification", "Notification operation was not queued");
        }
    }
}
