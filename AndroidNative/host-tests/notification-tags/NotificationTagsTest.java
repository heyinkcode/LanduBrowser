package org.chromium.components.browser_ui.notifications;

import android.app.Notification;
import android.app.Service;
import com.landu.browser.nativecore.NotificationIdStore;
import androidx.core.app.NotificationManagerCompat;
import org.chromium.base.IntentUtils;
import org.chromium.base.task.AsyncTask;
import org.chromium.base.task.PostTask;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

/** Executes the staged proxy implementations against an explicitly substituted OS notification map. */
public class NotificationTagsTest {
    static final String A = "00000000-0000-4000-8000-000000000001";
    static final String B = "00000000-0000-4000-8000-000000000002";
    static final String PREFIX = "com.landu.browser.notification:v1:";
    static int checks;
    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    static void rejects(Runnable action, String message) {
        boolean failed = false;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { failed = true; }
        check(failed, message);
    }
    static void drain() { AsyncTask.drain(); PostTask.drain(); }
    static List<BaseNotificationManagerProxy.StatusBarNotificationProxy> active(BaseNotificationManagerProxy proxy) {
        List<BaseNotificationManagerProxy.StatusBarNotificationProxy> result = new ArrayList<>();
        boolean[] called = {false};
        proxy.getActiveNotifications(list -> { result.addAll(list); called[0] = true; });
        check(!called[0], "active callback ran synchronously");
        drain();
        check(called[0], "active callback missing");
        return result;
    }
    static void exercise(BaseNotificationManagerProxy proxy, String owner, String foreign, boolean async) {
        NotificationManagerCompat manager = NotificationManagerCompat.INSTANCE;
        manager.clearFixture();
        String tag = "https://站点.example:443/#message:1";
        String scoped = PREFIX + owner + ':' + tag;
        String other = PREFIX + foreign + ':' + tag;
        Notification otherNotification = new Notification("foreign");
        Notification oldNotification = new Notification("legacy");
        Notification foreground = new Notification("foreground");
        manager.notify(other, 7, otherNotification);
        manager.notify(tag, 7, oldNotification);
        manager.notify(null, 7, foreground);
        manager.notify(PREFIX + owner + "x:bad", 7, oldNotification);
        Notification own = new Notification("own");
        NotificationMetadata metadata = new NotificationMetadata(1, tag, 7);
        proxy.notify(new NotificationWrapper(own, metadata));
        if (async) check(manager.peek(scoped, 7) == null, "async publication did not queue");
        drain();
        check(manager.peek(scoped, 7) == own, "owned tagged publication missing");
        check(manager.peek(other, 7) == otherNotification, "foreign same ID notification overwritten");
        check(manager.peek(tag, 7) == oldNotification, "legacy notification overwritten");
        check(manager.peek(null, 7) == foreground, "tagged publication touched foreground notification");
        check(metadata.tag.equals(tag) && metadata.id == 7, "caller metadata mutated");
        List<BaseNotificationManagerProxy.StatusBarNotificationProxy> list = active(proxy);
        check(list.size() == 1, "active query leaked foreign or unowned notifications");
        check(list.get(0).getId() == 7 && list.get(0).getTag().equals(tag), "logical identity was not restored");
        check(list.get(0).getNotification() == own, "notification payload changed");
        proxy.notify(new NotificationWrapper(new Notification("replacement"), new NotificationMetadata(1, tag, 7)));
        drain();
        check(manager.peek(scoped, 7).value.equals("replacement"), "own replacement failed");
        proxy.cancel(list.get(0).getTag(), list.get(0).getId());
        drain();
        check(manager.peek(scoped, 7) == null, "logical cancel did not find owned tag");
        check(manager.peek(other, 7) == otherNotification, "logical cancel removed another environment");
        proxy.cancel(other, 7);
        proxy.notify(new NotificationWrapper(own, new NotificationMetadata(1, other, 7)));
        drain();
        check(manager.peek(other, 7) == otherNotification, "foreign scoped tag was retagged or cancelled");
        proxy.notify(new NotificationWrapper(own, new NotificationMetadata(1, "", 8)));
        proxy.notify(new NotificationWrapper(own, new NotificationMetadata(1, tag, Integer.MIN_VALUE)));
        drain();
        check(manager.peek(PREFIX + owner + ':', 8) == own, "empty logical tag lost");
        check(manager.peek(scoped, Integer.MIN_VALUE) == own, "signed ID changed");
        proxy.cancelAll();
        if (async) check(manager.peek(PREFIX + owner + ':', 8) == own, "async cancelAll did not queue");
        drain();
        check(manager.globalCancels == 0, "global cancelAll called");
        check(active(proxy).isEmpty(), "owned cancelAll left tagged notifications");
        check(manager.peek(other, 7) == otherNotification, "cancelAll cleared foreign environment");
        check(manager.peek(tag, 7) == oldNotification, "cancelAll cleared legacy notification");
        check(manager.peek(null, 7) == foreground, "cancelAll cleared foreground service");
        // Untagged paths deliberately retain the upstream foreground-service ID contract.
        proxy.notify(new NotificationWrapper(own, new NotificationMetadata(1, null, 31)));
        drain();
        int mapped31 = IntentUtils.LanduEnvironment.findNotificationId(31);
        check(mapped31 != 0 && mapped31 != 31 && manager.peek(null, mapped31) == own,
                "untagged notification was not mapped");
        check(manager.peek(null, 31) == null, "untagged notification used a raw ID");
        proxy.cancel(31); drain();
        check(manager.peek(null, mapped31) == null, "untagged foreground cancel contract changed");
        proxy.notify(new NotificationWrapper(own, new NotificationMetadata(1, null, 32)));
        drain(); int mapped32 = IntentUtils.LanduEnvironment.findNotificationId(32);
        proxy.cancel(null, 32); drain();
        check(manager.peek(null, mapped32) == null, "nullable tag cancel contract changed");
        proxy.cancel(12345678); drain();
        check(IntentUtils.LanduEnvironment.findNotificationId(12345678) == 0, "cancel allocated a new ID");
        proxy.notify(new NotificationWrapper(own, new NotificationMetadata(1, null, 33))); drain();
        int mapped33 = IntentUtils.LanduEnvironment.findNotificationId(33);
        Service service = new Service();
        for (int sdk : new int[]{35, 36, 29, 28}) {
            android.os.Build.VERSION.SDK_INT = sdk;
            Notification fg = new Notification("pinned");
            ForegroundServiceUtils.getInstance().startForeground(service, 33, fg, 1);
            check(service.notificationId == mapped33, "foreground service bypassed the ID mapping");
            check(manager.peek(null, mapped33) == fg, "foreground service did not update mapped notification");
            check(service.type == (sdk >= 29 ? 1 : 0), "foreground type changed");
        }
        android.os.Build.VERSION.SDK_INT = 35;
        proxy.notify(new NotificationWrapper(own, new NotificationMetadata(1, null, 34))); drain();
        int mapped34 = IntentUtils.LanduEnvironment.findNotificationId(34);
        proxy.cancelAll(); drain();
        check(manager.peek(null, mapped33) != null, "cancelAll removed an active foreground service");
        check(manager.peek(null, mapped34) == null, "cancelAll left owned untagged notification");
        check(manager.peek(null, 7) == foreground, "cancelAll removed legacy untagged notification");
        ForegroundServiceUtils.getInstance().stopForeground(service, 1);
        check(manager.peek(null, mapped33) == null, "foreground stop did not remove its mapped notification");
        check(manager.peek(null, 7) == foreground, "foreground stop removed another notification");
    }
    public static void main(String[] args) throws Exception {
        String owner = args[0].equals("A") ? A : B, foreign = owner.equals(A) ? B : A;
        NotificationManagerProxy sync = NotificationManagerProxyImpl.getInstance();
        BaseNotificationManagerProxy async = AsyncNotificationManagerProxyImpl.getInstance();
        check(IntentUtils.LanduEnvironment.getCurrentOwner() == null, "fixture owner was preinitialized");
        rejects(() -> NotificationMetadata.landuTag("site"), "missing owner was accepted");
        check(!NotificationMetadata.landuOwnsTag(PREFIX + owner + ":site"), "uninitialized query owns tags");
        NotificationManagerCompat manager = NotificationManagerCompat.INSTANCE;
        Notification sentinel = new Notification("sentinel");
        manager.notify(PREFIX + owner + ":site", 1, sentinel);
        sync.cancelAll(); async.cancelAll(); drain();
        check(manager.peek(PREFIX + owner + ":site", 1) == sentinel, "uninitialized cancel removed notification");
        check(active(sync).isEmpty() && active(async).isEmpty(), "uninitialized query leaked notifications");
        IntentUtils.LanduEnvironment.initialize(owner);
        check(owner.equals(IntentUtils.LanduEnvironment.getCurrentOwner()), "owner accessor wrong");
        rejects(() -> IntentUtils.LanduEnvironment.initialize(foreign), "owner was switched in live process");
        rejects(() -> IntentUtils.LanduEnvironment.notificationId(1), "unbound ID registry was accepted");
        NotificationIdStore store = new NotificationIdStore(Path.of(args[1]));
        IntentUtils.LanduEnvironment.NotificationIds mapper = new IntentUtils.LanduEnvironment.NotificationIds() {
            public int getOrCreate(int id) {
                try { return store.getOrCreate(owner, id, candidate -> manager.peek(null, candidate) != null); }
                catch (java.io.IOException e) { throw new IllegalStateException(e); }
            }
            public int find(int id) {try {return store.find(owner, id);} catch(java.io.IOException e){throw new IllegalStateException(e);}}
            public boolean owns(int id) {try {return store.owns(owner, id);} catch(java.io.IOException e){throw new IllegalStateException(e);}}
        };
        rejects(() -> IntentUtils.LanduEnvironment.bindNotificationIds(foreign, mapper), "foreign registry binding accepted");
        IntentUtils.LanduEnvironment.bindNotificationIds(owner, mapper);
        for (String tag : new String[]{"", "site", "a:b:c", "站点😀\nvalue", "v1:", "com.landu.browser.intent:v1:"}) {
            String scoped = NotificationMetadata.landuTag(tag);
            check(scoped.equals(PREFIX + owner + ':' + tag), "tag encoding collision");
            check(NotificationMetadata.landuTag(scoped).equals(scoped), "tag encoding not idempotent");
            check(NotificationMetadata.landuLogicalTag(scoped).equals(tag), "tag roundtrip changed original");
        }
        check(NotificationMetadata.landuTag(null) == null, "null tag became empty tag");
        for (String tag : new String[]{PREFIX + foreign + ":site", "com.landu.browser.notification:v2:x",
                PREFIX + owner + "0:site", "com.landu.browser.notification:"}) {
            check(!NotificationMetadata.landuOwnsTag(tag), "foreign/malformed ownership accepted");
            rejects(() -> NotificationMetadata.landuTag(tag), "foreign/malformed tag accepted");
            rejects(() -> NotificationMetadata.landuLogicalTag(tag), "foreign/malformed decode accepted");
        }
        rejects(() -> NotificationMetadata.landuLogicalTag(null), "null logical decode accepted");
        exercise(sync, owner, foreign, false);
        exercise(async, owner, foreign, true);
        // Deprecated direct tagged overload must apply the same mapping.
        manager.clearFixture();
        sync.notify("direct", 99, sentinel);
        check(manager.peek(PREFIX + owner + ":direct", 99) == sentinel, "direct tagged overload bypassed scope");
        sync.cancel("direct", 99);
        check(manager.peek(PREFIX + owner + ":direct", 99) == null, "direct tagged cancel failed");
        sync.notify(100, sentinel);
        int mapped100 = IntentUtils.LanduEnvironment.findNotificationId(100);
        check(mapped100 != 0 && manager.peek(null, mapped100) == sentinel, "direct untagged overload bypassed mapping");
        int foreign100 = store.getOrCreate(foreign, 100, ignored -> false);
        check(foreign100 != mapped100, "cross-environment physical ID collision");
        Notification foreignNotification = new Notification("foreign-untagged");
        manager.notify(foreign100, foreignNotification);
        sync.cancel(100);
        check(manager.peek(null, mapped100) == null && manager.peek(null, foreign100) == foreignNotification,
                "untagged cancellation crossed environments");
        check(manager.globalCancels == 0, "global cancellation used");
        System.out.println("{\"passed\":true,\"owner\":\"" + args[0] + "\",\"assertions\":" + checks + "}");
    }
}
