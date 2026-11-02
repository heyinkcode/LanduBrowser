package org.chromium.chrome.browser.notifications;

import android.app.job.*;
import android.content.*;
import android.os.*;
import org.chromium.base.IntentUtils;
import org.chromium.chrome.browser.init.ChromeBrowserInitializer;

/** Android classes in this test are deterministic protocol substitutes, not device execution. */
public final class NotificationJobOwnershipTest {
    private static final String A = "11111111-1111-4111-8111-111111111111";
    private static final String B = "22222222-2222-4222-8222-222222222222";
    private static int assertions;

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    private static Intent intent(String owner) {
        Intent result = IntentUtils.LanduEnvironment.scopePendingIntent(new Intent("CLICK")
                .putExtra(NotificationConstants.EXTRA_NOTIFICATION_ID, "notification-1")
                .putExtra(NotificationConstants.EXTRA_NOTIFICATION_INFO_ORIGIN, "https://example.test")
                .putExtra(NotificationConstants.EXTRA_NOTIFICATION_INFO_PROFILE_ID, "Default")
                .putExtra("reply", "hello"));
        return result.setIdentifier(result.getIdentifier().replace(A, owner));
    }

    private static JobParameters params(int id, Intent intent) {
        return new JobParameters(id, NotificationJobService.landuNamespace(intent),
                NotificationJobServiceImpl.getJobExtrasFromIntent(intent));
    }

    private static int sideEffects() {
        return NotificationPlatformBridge.preCalls + NotificationPlatformBridge.dispatches
                + NotificationUmaTracker.calls + TrampolineActivityTracker.calls
                + org.chromium.base.metrics.RecordHistogram.calls + ChromeBrowserInitializer.starts;
    }

    public static void main(String[] args) {
        IntentUtils.LanduEnvironment.initialize(A);
        Intent a = intent(A), b = intent(B);
        Context context = new Context();
        org.chromium.base.ContextUtils.context = context;
        NotificationServiceImpl.Receiver receiver = new NotificationServiceImpl.Receiver();
        receiver.onReceive(context, a);
        check(NotificationPlatformBridge.preCalls == 1, "current notification did not reach pre-native handling");
        int before = sideEffects();
        receiver.onReceive(context, b);
        check(sideEffects() == before, "foreign receiver touched selected environment");
        String nsA = NotificationJobService.landuNamespace(a), nsB = NotificationJobService.landuNamespace(b);
        check(!nsA.equals(nsB), "notification namespaces collide");
        check(context.scheduler.queues.size() == 2 && !context.scheduler.queues.containsKey("global"), "global scheduler used");
        JobInfo jobA = context.scheduler.queues.get(nsA).get(10), jobB = context.scheduler.queues.get(nsB).get(10);
        check(jobA != null && jobB != null, "same job ID overwrote another environment");
        check("hello".equals(jobB.extras.getString(NotificationConstants.EXTRA_NOTIFICATION_REPLY)), "RemoteInput reply lost");
        check("Default".equals(jobB.extras.getString(NotificationConstants.EXTRA_NOTIFICATION_INFO_PROFILE_ID)), "profile metadata lost");
        check(jobB.deadline == 0 && !jobB.expedited, "ordinary notification scheduling changed");
        receiver.onReceive(context, new Intent(b).setAction(NotificationConstants.ACTION_PRE_UNSUBSCRIBE));
        check(context.scheduler.queues.get(nsB).get(11).expedited, "unsubscribe priority missing");
        check(sideEffects() == before, "foreign unsubscribe touched selected profile");
        receiver.onReceive(context, new Intent("CLICK"));
        check(context.scheduler.queues.size() == 2 && sideEffects() == before, "unowned notification accepted");

        NotificationJobService service = new NotificationJobService();
        JobParameters own = new JobParameters(10, nsA, jobA.extras);
        check(!service.onStartJob(own), "original synchronous completion changed");
        check(service.starts == 1 && NotificationPlatformBridge.dispatches == 1, "owned job did not dispatch");
        check(a.getIdentifier().equals(NotificationPlatformBridge.last.getIdentifier()), "job round trip lost environment identity");
        check("CLICK".equals(NotificationPlatformBridge.last.getAction()), "job action changed");
        before = sideEffects();
        JobParameters foreign = new JobParameters(10, nsB, jobB.extras);
        check(service.onStartJob(foreign), "foreign job not held for asynchronous completion");
        check(service.starts == 1 && sideEffects() == before && service.finished.isEmpty(), "foreign job ran or finished synchronously");
        Handler.drain();
        check(service.finished.size() == 1 && service.finished.get(0) == foreign && service.retries.get(0), "foreign job not returned for rescheduling");
        check(sideEffects() == before, "deferred notification initialized native");

        check(service.onStartJob(foreign), "foreign restart rejected");
        Runnable old = Handler.snapshot().get(0);
        JobParameters replacement = params(10, b);
        check(service.onStartJob(replacement), "replacement not accepted");
        check(!service.onStopJob(foreign), "stale stop canceled new run");
        old.run();
        check(service.finished.size() == 1, "stale callback finished newer run");
        Handler.drain();
        check(service.finished.size() == 2 && service.finished.get(1) == replacement, "replacement callback lost");

        JobParameters stopped = params(10, b);
        check(service.onStartJob(stopped), "stop case not started");
        Runnable stoppedCallback = Handler.snapshot().get(0);
        check(service.onStopJob(stopped), "foreign stop failed to request retry");
        stoppedCallback.run();
        Handler.drain();
        check(service.finished.size() == 2, "stopped job finished again");
        check(!service.onStopJob(stopped), "duplicate stop requested another retry");

        JobParameters secondId = params(11, b);
        check(service.onStartJob(foreign) && service.onStartJob(secondId), "distinct IDs not accepted");
        check(Handler.snapshot().size() == 2, "distinct job IDs shared callback slot");
        Handler.drain();
        check(service.finished.size() == 4, "distinct jobs did not finish independently");
        check(service.onStartJob(foreign), "destroy case not started");
        Runnable afterDestroy = Handler.snapshot().get(0);
        service.onDestroy();
        afterDestroy.run();
        Handler.drain();
        check(service.finished.size() == 4, "destroyed service completed a stale job");
        check(!service.onStartJob(own) && !service.onStopJob(own), "destroyed service accepted work");

        NotificationJobService clean = new NotificationJobService();
        check(!clean.onStartJob(new JobParameters(10, "global", jobA.extras)), "global namespace accepted");
        check(!clean.onStartJob(new JobParameters(10, nsB, jobA.extras)), "namespace and owner mismatch accepted");
        check(!clean.onStartJob(new JobParameters(10, nsA, new PersistableBundle())), "untagged job accepted");
        PersistableBundle malformed = new PersistableBundle(jobA.extras);
        malformed.values.put("com.landu.browser.notification.IDENTIFIER", 12);
        check(!clean.onStartJob(new JobParameters(10, nsA, malformed)), "malformed envelope accepted");
        check(clean.starts == 0, "invalid job loaded implementation");
        NotificationJobServiceImpl impl = new NotificationJobServiceImpl();
        before = sideEffects();
        check(!impl.onStartJob(foreign) && !impl.onStopJob(foreign) && sideEffects() == before, "direct implementation bypassed ownership");
        NotificationServiceImpl.dispatchIntentOnUiThread(b);
        check(sideEffects() == before, "direct dispatch bypassed ownership");
        new NotificationServiceImpl().onHandleIntent(b);
        check(sideEffects() == before && context.scheduler.queues.get(nsB).containsKey(10), "legacy service applied foreign action");
        Intent missingAction = new Intent(a).setAction(null);
        check(!clean.onStartJob(params(10, missingAction)), "missing action accepted");
        check(sideEffects() == before, "missing action mutated state");

        Handler.acceptPosts = false;
        check(!clean.onStartJob(foreign), "failed handler submission reported running");
        check(!clean.onStopJob(foreign), "failed submission left lifecycle entry");
        Handler.acceptPosts = true;
        Build.VERSION.SDK_INT = 33;
        check(NotificationJobService.landuScheduler(context, a) == null, "old Android used global fallback");
        check(!clean.onStartJob(own), "old Android accepted unsupported namespace");
        Build.VERSION.SDK_INT = 36;
        context.failService = true;
        check(NotificationJobService.landuScheduler(context, a) == null, "unavailable scheduler did not fail closed");
        context.failService = false;
        context.scheduler.failNamespace = true;
        check(NotificationJobService.landuScheduler(context, a) == null, "namespace failure used global scheduler");
        context.scheduler.failNamespace = false;
        check(NotificationJobService.landuScheduler(context, a) != null, "Android 16 namespace unavailable");
        System.out.println("{\"passed\":true,\"assertions\":" + assertions + "}");
    }
}
