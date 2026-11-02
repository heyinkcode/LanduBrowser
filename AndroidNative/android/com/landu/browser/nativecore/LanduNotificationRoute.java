package com.landu.browser.nativecore;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import org.chromium.base.IntentUtils;

/** Private, fixed-destination handoff; never accepts a caller-selected dispatch component. */
public final class LanduNotificationRoute {
    public static final String ENTRY_CLASS = "com.landu.browser.nativecore.LanduNotificationActivity";
    private static final String DISPATCH_CLASS =
            "org.chromium.chrome.browser.notifications.NotificationIntentInterceptor$TrampolineActivity";
    private static final String ACTION = "notifications.NotificationIntentInterceptor.INTENT_ACTION";
    private static final String TYPE = "notifications.NotificationIntentInterceptor.EXTRA_INTENT_TYPE";
    private static final String PAYLOAD = "com.landu.browser.environment.NOTIFICATION";

    private LanduNotificationRoute() {}

    public static final class Entry {
        public final EnvironmentIdentity identity;
        private final Intent payload;

        private Entry(EnvironmentIdentity identity, Intent payload) {
            this.identity = identity;
            this.payload = payload;
        }

        public Intent launch(Context context, EnvironmentLeaseStore.LaunchRequest request) {
            if (!identity.equals(request.ticket.identity)) {
                throw new IllegalArgumentException("通知与启动环境不一致");
            }
            Intent launch = new Intent(context, LanduLaunchActivity.class);
            launch.putExtra(NativeEnvironmentRuntime.EXTRA_ID, identity.id());
            launch.putExtra(NativeEnvironmentRuntime.EXTRA_NONCE, request.ticket.nonce);
            launch.putExtra(PAYLOAD, new Intent(payload));
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return launch;
        }
    }

    public static Entry read(String packageName, Intent incoming) {
        if (incoming == null || !ACTION.equals(incoming.getAction())) {
            throw new IllegalArgumentException("通知入口无效，请返回环境管理");
        }
        ComponentName component = incoming.getComponent();
        String owner = IntentUtils.LanduEnvironment.ownerOf(incoming);
        int type = incoming.getIntExtra(TYPE, -1);
        if (component == null || !packageName.equals(component.getPackageName())
                || !ENTRY_CLASS.equals(component.getClassName()) || owner == null
                || (type != 0 && type != 1)) {
            throw new IllegalArgumentException("通知归属无效，请重新打开所属环境");
        }
        // RemoteInput uses ClipData as well as extras; copying preserves the reply in memory.
        Intent payload = new Intent(incoming);
        payload.setSelector(null);
        payload.setDataAndType(null, null);
        payload.setPackage(packageName);
        payload.setFlags(incoming.getFlags() & (Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION));
        return new Entry(EnvironmentIdentity.parse(owner), payload);
    }

    public static boolean isNotificationLaunch(Intent launch) {
        return launch != null && launch.hasExtra(PAYLOAD);
    }

    public static Intent dispatch(String packageName, Intent launch, String verifiedIdentity) {
        Intent payload = launch.getParcelableExtra(PAYLOAD);
        Entry entry = read(packageName, payload);
        if (!entry.identity.id().equals(verifiedIdentity)
                || !IntentUtils.LanduEnvironment.owns(entry.payload)) {
            throw new IllegalArgumentException("通知与当前运行环境不一致");
        }
        Intent target = new Intent(entry.payload);
        target.setClassName(packageName, DISPATCH_CLASS);
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return target;
    }
}
