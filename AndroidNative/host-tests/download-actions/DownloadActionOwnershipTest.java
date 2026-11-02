package org.chromium.chrome.browser.download;

import static org.chromium.chrome.browser.download.DownloadNotificationService.*;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import org.chromium.base.IntentUtils;
import org.chromium.chrome.browser.init.ChromeBrowserInitializer;
import org.chromium.chrome.browser.notifications.NotificationConstants;
import org.chromium.components.offline_items_collection.ContentId;

/** Executes the full staged service implementation; Android scheduling is a host substitute. */
public final class DownloadActionOwnershipTest {
    private static final String A = "12345678-1234-4234-8234-123456789abc";
    private static final String B = "87654321-4321-4321-8321-cba987654321";
    private static int assertions;
    private static void check(boolean result, String message) {
        assertions++;
        if (!result) throw new AssertionError(message);
    }
    private static Intent raw(String action) {
        return new Intent(action).putExtra(EXTRA_DOWNLOAD_CONTENTID_NAMESPACE, "download")
                .putExtra(EXTRA_DOWNLOAD_CONTENTID_ID, "same-item-id")
                .putExtra(NotificationConstants.EXTRA_NOTIFICATION_ID, 41);
    }
    private static Intent own(String action) {
        return IntentUtils.LanduEnvironment.scopePendingIntent(raw(action));
    }
    private static Intent foreign(String action) {
        return raw(action).setIdentifier("com.landu.browser.intent:v1:" + B + ":0");
    }
    private static int effects() {
        return DownloadSharedPreferenceHelper.accesses + DownloadNotificationService.accesses
                + ChromeBrowserInitializer.starts + DownloadServiceDelegate.calls
                + DownloadManagerService.opens + DownloadManagerService.homeOpens
                + DownloadUtils.accesses + DownloadManagerBridge.queries;
    }
    private static void rejected(DownloadBroadcastManagerImpl service, Intent intent) {
        int before = effects();
        service.onNotificationInteraction(intent);
        check(effects() == before, "foreign/malformed notification accessed selected environment");
        if (intent != null) {
            service.updateNotification(intent);
            service.loadNativeAndPropagateInteraction(intent);
            service.propagateInteraction(intent);
            check(effects() == before, "direct callback bypassed environment gate");
        }
    }
    public static void main(String[] args) {
        DownloadBroadcastManagerImpl service = new DownloadBroadcastManagerImpl();
        check(effects() == 0, "service constructor opened environment data before ownership check");
        rejected(service, raw(ACTION_DOWNLOAD_CANCEL));
        check(service.onStartCommand(null, 0, 1) == android.app.Service.START_NOT_STICKY,
                "rejected start became sticky");
        check(service.hostService.stops == 1 && service.hostService.lastStart == 1,
                "idle rejected start left service running");
        IntentUtils.LanduEnvironment.initialize(A);
        for (String action : new String[]{ACTION_DOWNLOAD_CANCEL, ACTION_DOWNLOAD_PAUSE,
                ACTION_DOWNLOAD_RESUME, ACTION_DOWNLOAD_OPEN, DownloadManager.ACTION_NOTIFICATION_CLICKED}) {
            rejected(service, foreign(action));
            rejected(service, raw(action));
            rejected(service, raw(action).setIdentifier("com.landu.browser.intent:v2:" + A + ":0"));
            rejected(service, raw(action).putExtra("com.landu.browser.environment.ID", A));
        }
        rejected(service, own("unsupported-action"));
        for (String action : new String[]{ACTION_DOWNLOAD_CANCEL, ACTION_DOWNLOAD_PAUSE,
                ACTION_DOWNLOAD_RESUME, ACTION_DOWNLOAD_OPEN}) {
            rejected(service, own(action).removeExtra(EXTRA_DOWNLOAD_CONTENTID_ID));
            rejected(service, own(action).putExtra(EXTRA_DOWNLOAD_CONTENTID_ID, ""));
            rejected(service, own(action).putExtra(EXTRA_DOWNLOAD_CONTENTID_NAMESPACE, ""));
            rejected(service, own(action).putExtra(EXTRA_DOWNLOAD_CONTENTID_NAMESPACE, 7));
        }

        Context context = new Context();
        Intent internal = raw(ACTION_DOWNLOAD_RESUME);
        DownloadBroadcastManagerImpl.startDownloadBroadcastManager(context, internal);
        check(context.started != internal && internal.getIdentifier() == null && internal.getComponent() == null,
                "internal resume mutated caller intent");
        check(IntentUtils.LanduEnvironment.owns(context.started), "internal resume lost source identity");
        check(context.started.getComponent() != null, "internal resume lost service target");
        int starts = context.starts;
        try {
            DownloadBroadcastManagerImpl.startDownloadBroadcastManager(context, foreign(ACTION_DOWNLOAD_RESUME));
            throw new AssertionError("foreign internal resume was retagged");
        } catch (IllegalArgumentException expected) { assertions++; }
        check(context.starts == starts, "foreign resume started selected service");
        DownloadBroadcastManagerImpl.startDownloadBroadcastManager(context, null);
        DownloadBroadcastManagerImpl.startDownloadBroadcastManager(context, raw("unsupported-action"));
        check(context.starts == starts, "invalid resume started service");

        DownloadSharedPreferenceHelper.entry = new DownloadSharedPreferenceEntry(
                new ContentId("download", "same-item-id"), 41, null, false, "owned.bin", true, false);
        for (String action : new String[]{ACTION_DOWNLOAD_CANCEL, ACTION_DOWNLOAD_PAUSE, ACTION_DOWNLOAD_RESUME}) {
            Intent accepted = own(action);
            int operations = DownloadServiceDelegate.calls, loads = ChromeBrowserInitializer.starts;
            int notifications = DownloadNotificationService.updates;
            service.onStartCommand(accepted, 0, ++starts);
            check(DownloadNotificationService.updates == notifications + 1,
                    "owned action failed immediate notification update");
            check(ChromeBrowserInitializer.starts == loads + 1, "owned action failed native startup");
            check(DownloadServiceDelegate.calls == operations, "action ran before native initialization");
            check(accepted.getLongExtra("Download.Android.ResumptionStartTime", -1) == -1,
                    "resume modified caller extras");
            // Mutation after acceptance must not change the callback's action/identity/item.
            accepted.setAction("unsupported-action").setIdentifier("foreign");
            accepted.putExtra(EXTRA_DOWNLOAD_CONTENTID_ID, "another-item");
            int stops = service.hostService.stops;
            service.onStartCommand(foreign(action), 0, ++starts);
            check(service.hostService.stops == stops, "foreign start stopped accepted native work");
            ChromeBrowserInitializer.finish();
            check(DownloadServiceDelegate.calls == operations + 1, "owned action did not reach delegate");
            check(action.equals(DownloadServiceDelegate.lastAction), "queued action changed after acceptance");
            check("same-item-id".equals(DownloadServiceDelegate.lastId), "queued item changed after acceptance");
            check(DownloadServiceDelegate.destroyed == DownloadServiceDelegate.calls,
                    "delegate was not released");
        }
        check(DownloadSharedPreferenceHelper.writes == 1, "resume did not preserve upstream preference update");
        int calls = DownloadServiceDelegate.calls;
        service.onNotificationInteraction(own(ACTION_DOWNLOAD_OPEN));
        ChromeBrowserInitializer.finish();
        check(DownloadServiceDelegate.calls == calls + 1 && ACTION_DOWNLOAD_OPEN.equals(DownloadServiceDelegate.lastAction),
                "owned open did not reach aggregator");

        Intent home = own(DownloadManager.ACTION_NOTIFICATION_CLICKED)
                .putExtra(EXTRA_DOWNLOAD_CONTENTID_ID, "").putExtra(EXTRA_DOWNLOAD_CONTENTID_NAMESPACE, "");
        int homeOpens = DownloadManagerService.homeOpens;
        service.onNotificationInteraction(home);
        ChromeBrowserInitializer.finish();
        check(DownloadManagerService.homeOpens == homeOpens + 1, "empty item notification lost Download Home behavior");

        Intent query = own(DownloadManager.ACTION_NOTIFICATION_CLICKED)
                .putExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS, new long[]{9})
                .putExtra(EXTRA_DOWNLOAD_FILE_PATH, "/tmp/owned.bin");
        int opened = DownloadManagerService.opens;
        service.onNotificationInteraction(query);
        ChromeBrowserInitializer.finish();
        check(DownloadManagerService.opens == opened, "download query opened file before result");
        DownloadManagerBridge.finish(true);
        check(DownloadManagerService.opens == opened + 1, "owned download query did not open file");

        DownloadBroadcastManagerImpl closing = new DownloadBroadcastManagerImpl();
        closing.onNotificationInteraction(query);
        ChromeBrowserInitializer.finish();
        closing.onNotificationInteraction(own(ACTION_DOWNLOAD_CANCEL));
        int beforeDestroy = effects();
        closing.onDestroy();
        DownloadManagerBridge.finish(false);
        ChromeBrowserInitializer.finish();
        check(effects() == beforeDestroy, "destroyed service executed late native or download query callback");
        rejected(closing, own(ACTION_DOWNLOAD_CANCEL));
        Handler.drain();
        check(closing.hostService.unconditionalStops == 0, "destroyed service ran delayed stop");
        System.out.println("{\"passed\":true,\"assertions\":" + assertions
                + ",\"fullServiceExecuted\":true,\"androidFrameworkExecuted\":false}");
    }
}
