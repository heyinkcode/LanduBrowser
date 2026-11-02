package org.chromium.chrome.browser.notifications;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import org.chromium.base.ApplicationStatus;
import org.chromium.base.IntentUtils;
import org.chromium.base.metrics.RecordHistogram;
import org.chromium.chrome.browser.flags.ChromeFeatureList;
import org.chromium.components.browser_ui.notifications.NotificationMetadata;
import org.chromium.components.browser_ui.notifications.PendingIntentProvider;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Full wrapper/receiver/activity implementation with explicitly simulated Android objects. */
public final class NotificationInterceptorTest {
    private static final String A = "12345678-1234-4234-8234-123456789abc";
    private static final String B = "87654321-4321-4321-8321-cba987654321";
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static int effects() {
        return NotificationUmaTracker.events + RecordHistogram.events
                + TrampolineActivityTracker.accesses + PendingIntent.sends;
    }
    private static NotificationMetadata metadata(String tag) {
        return new NotificationMetadata(NotificationUmaTracker.SystemNotificationType.DOWNLOAD_FILES, tag, 41);
    }
    private static PendingIntent outer(int type, int action, String tag, PendingIntentProvider inner) {
        return NotificationIntentInterceptor.createInterceptPendingIntent(type, action, metadata(tag), inner);
    }
    private static void reject(Intent intent) {
        int before = effects();
        new NotificationIntentInterceptor.Receiver().onReceive(new Context(), intent);
        check(effects() == before, "foreign wrapper accessed metrics or dispatched an operation");
        new NotificationIntentInterceptor.ServiceImpl().onHandleIntent(intent);
        check(effects() == before, "service wrapper bypassed owner check");
        for (int sdk : new int[]{31, 35, 36}) {
            Build.VERSION.SDK_INT = sdk;
            NotificationIntentInterceptor.TrampolineActivity activity = new NotificationIntentInterceptor.TrampolineActivity();
            activity.setIntent(intent);
            activity.onCreate(null);
            check(activity.finished && activity.contents == 0, "foreign activity was kept visible");
            check(effects() == before, "foreign activity initialized metrics or tracking");
            activity.onDestroy();
            check(activity.superDestroy == 1, "rejected activity skipped framework destruction");
            check(effects() == before, "rejected activity recorded destruction metrics");
        }
    }
    public static void main(String[] args) {
        String owner = args[0].equals("A") ? A : B;
        String foreign = args[0].equals("A") ? B : A;
        Context context = new Context();
        reject(null);
        reject(new Intent(NotificationIntentInterceptor.INTENT_ACTION));
        try {
            outer(NotificationIntentInterceptor.IntentType.DELETE_INTENT, -1, null, null);
            throw new AssertionError("uninitialized wrapper factory accepted an ownerless notification");
        } catch (IllegalStateException expected) { assertions++; }
        IntentUtils.LanduEnvironment.initialize(owner);
        boolean[] dispatchAllowed = {true};
        IntentUtils.LanduEnvironment.bindNotificationDispatchGate(owner, () -> dispatchAllowed[0]);
        Intent original = new Intent("download.action").setIdentifier("original-download-identity");
        PendingIntentProvider provider = PendingIntentProvider.getService(context, 9, original,
                PendingIntent.FLAG_UPDATE_CURRENT | Intent.FILL_IN_IDENTIFIER, true);
        check("original-download-identity".equals(original.getIdentifier()), "inner provider mutated caller");
        check(IntentUtils.LanduEnvironment.owns(provider.getPendingIntent().intent), "inner provider lost owner");

        List<String> keys = new ArrayList<>();
        for (String tag : new String[]{null, "", "null", "download:tag", "中文"}) {
            for (int type : new int[]{NotificationIntentInterceptor.IntentType.CONTENT_INTENT,
                    NotificationIntentInterceptor.IntentType.ACTION_INTENT, NotificationIntentInterceptor.IntentType.DELETE_INTENT}) {
                PendingIntent wrapped = outer(type, NotificationUmaTracker.ActionType.DOWNLOAD_PAUSE, tag, provider);
                check(IntentUtils.LanduEnvironment.owns(wrapped.intent), "outer wrapper has no environment identity");
                check(NotificationIntentInterceptor.getPendingIntentForTesting(wrapped.intent) == provider.getPendingIntent(),
                        "outer wrapper replaced inner operation");
                check((wrapped.flags & Intent.FILL_IN_IDENTIFIER) == 0, "outer wrapper allows identifier replacement");
                check((wrapped.flags & PendingIntent.FLAG_MUTABLE) != 0, "reply-capable operation lost mutability");
                check(type == NotificationIntentInterceptor.IntentType.DELETE_INTENT
                        ? wrapped.kind.equals("broadcast") : wrapped.kind.equals("activity"), "upstream wrapper routing changed");
                check(type == NotificationIntentInterceptor.IntentType.DELETE_INTENT
                        ? wrapped.intent.component.equals("com.landu.browser.nativecore.LanduNotificationReceiver")
                        : wrapped.intent.component.equals("com.landu.browser.nativecore.LanduNotificationActivity"),
                        "notification tap did not route through the manager process");
                check(wrapped == outer(type, NotificationUmaTracker.ActionType.DOWNLOAD_PAUSE, tag, provider),
                        "same environment notification update changed wrapper identity");
                keys.add(wrapped.key);
            }
        }
        check(new HashSet<>(keys).size() == keys.size(), "null, empty, tag or action wrapper identity collided");
        PendingIntent wrapped = outer(NotificationIntentInterceptor.IntentType.ACTION_INTENT,
                NotificationUmaTracker.ActionType.DOWNLOAD_PAUSE, "reply", provider);
        Intent other = new Intent(wrapped.intent).setIdentifier(wrapped.intent.getIdentifier().replace(owner, foreign));
        reject(other);
        reject(new Intent(wrapped.intent).setIdentifier("unscoped"));
        reject(new Intent(wrapped.intent).setIdentifier(wrapped.intent.getIdentifier().replace(":v1:", ":v9:")));
        reject(new Intent(wrapped.intent).setIdentifier(null).putExtra("com.landu.browser.environment.ID", owner));

        // Public legacy providers may supply flags directly; the outer factory also protects them.
        PendingIntentProvider direct = new PendingIntentProvider(provider.getPendingIntent(),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE | Intent.FILL_IN_IDENTIFIER, 9);
        PendingIntent protectedWrapper = outer(NotificationIntentInterceptor.IntentType.ACTION_INTENT, -1, "flags", direct);
        check((protectedWrapper.flags & Intent.FILL_IN_IDENTIFIER) == 0, "outer factory did not protect legacy provider flags");

        Bundle reply = new Bundle();
        reply.putCharSequence(NotificationConstants.KEY_TEXT_REPLY, "owned reply");
        Intent received = new Intent(wrapped.intent).putExtra("host.remoteInput", reply);
        int beforeSend = PendingIntent.sends;
        int beforeMetrics = NotificationUmaTracker.events;
        NotificationIntentInterceptor.Receiver receiver = new NotificationIntentInterceptor.Receiver();
        dispatchAllowed[0] = false;
        receiver.onReceive(context, received);
        check(PendingIntent.sends == beforeSend && NotificationUmaTracker.events == beforeMetrics
                        && receiver.result == 0, "stopping environment acknowledged or executed background notification");
        dispatchAllowed[0] = true;
        receiver.onReceive(context, received);
        check(receiver.result == Activity.RESULT_OK, "accepted background notification was not acknowledged");
        check(PendingIntent.sends == beforeSend + 1 && NotificationUmaTracker.events == beforeMetrics + 1,
                "owned action was not forwarded and recorded once");
        check("owned reply".equals(provider.getPendingIntent().lastFill.extras.get(NotificationConstants.EXTRA_NOTIFICATION_REPLY)),
                "RemoteInput reply was lost");
        check(provider.getPendingIntent().lastFill.getIdentifier() == null, "forwarding replaced inner filter identity");
        check(IntentUtils.LanduEnvironment.owns(provider.getPendingIntent().intent), "forwarding changed inner owner");
        check(received.getIdentifier().equals(wrapped.intent.getIdentifier()), "reply forwarding mutated outer identity");

        int metrics = NotificationUmaTracker.events;
        PendingIntent deletion = NotificationIntentInterceptor.getDefaultDeletePendingIntent(metadata("default-delete"));
        check(deletion.kind.equals("broadcast") && (deletion.flags & PendingIntent.FLAG_IMMUTABLE) != 0,
                "default delete no longer uses immutable broadcast");
        new NotificationIntentInterceptor.Receiver().onReceive(context, deletion.intent);
        check(NotificationUmaTracker.events == metrics + 1, "owned default deletion was not recorded");

        ChromeFeatureList.sNotificationTrampolineNoNewTask.enabled = true;
        check(outer(NotificationIntentInterceptor.IntentType.ACTION_INTENT,
                NotificationUmaTracker.ActionType.DOWNLOAD_CANCEL, "background", provider).kind.equals("broadcast"),
                "background download action no longer uses upstream broadcast mode");
        ChromeFeatureList.sNotificationTrampolineNoNewTask.enabled = false;
        for (int sdk : new int[]{31, 35, 36}) {
            Build.VERSION.SDK_INT = sdk;
            NotificationIntentInterceptor.TrampolineActivity activity = new NotificationIntentInterceptor.TrampolineActivity();
            activity.setIntent(wrapped.intent);
            int sends = PendingIntent.sends;
            activity.onCreate(null);
            check(PendingIntent.sends == sends + 1, "owned activity failed to forward inner intent");
            check(sdk < 35 ? activity.finished : activity.contents == 1, "Android version activity path changed");
            activity.onDestroy();
            check(activity.superDestroy == 1, "owned activity skipped framework destruction");
        }
        Build.VERSION.SDK_INT = 36;
        ApplicationStatus.activities.add(new Activity());
        NotificationIntentInterceptor.TrampolineActivity visible = new NotificationIntentInterceptor.TrampolineActivity();
        visible.setIntent(wrapped.intent);visible.onCreate(null);
        check(visible.finished && visible.contents == 0, "visible app unexpectedly kept a translucent activity");
        visible.onDestroy();ApplicationStatus.activities.clear();

        provider.getPendingIntent().cancelled = true;
        NotificationIntentInterceptor.Receiver expiredReceiver = new NotificationIntentInterceptor.Receiver();
        expiredReceiver.onReceive(context, received);
        check(expiredReceiver.result == Activity.RESULT_OK, "cancelled operation was left for endless retry");
        NotificationIntentInterceptor.TrampolineActivity cancelled = new NotificationIntentInterceptor.TrampolineActivity();
        cancelled.setIntent(wrapped.intent);cancelled.onCreate(null);
        check(cancelled.finished, "cancelled inner operation left trampoline activity open");
        cancelled.onDestroy();
        System.out.print("{\"passed\":true,\"assertions\":" + assertions + ",\"owner\":\"" + owner + "\",\"keys\":[");
        for (int i=0;i<keys.size();i++) {
            if (i>0) System.out.print(",");
            System.out.print("\"" + keys.get(i) + "\"");
        }
        System.out.println("]}");
    }
}
