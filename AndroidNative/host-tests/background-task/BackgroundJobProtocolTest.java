package org.chromium.components.background_task_scheduler.internal;

import android.app.Notification;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.PersistableBundle;
import java.lang.reflect.Method;
import java.util.List;
import org.chromium.base.CommandLine;
import org.chromium.base.IntentUtils;
import com.landu.browser.nativecore.NotificationIdStore;
import org.chromium.components.background_task_scheduler.BackgroundTask;
import org.chromium.components.background_task_scheduler.TaskInfo;
import org.chromium.components.background_task_scheduler.TaskParameters;

public final class BackgroundJobProtocolTest {
    private static final String A = "11111111-1111-4111-8111-111111111111";
    private static final String B = "22222222-2222-4222-8222-222222222222";
    private static int assertions;
    private static void check(boolean condition, String why) {
        assertions++;
        if (!condition) throw new AssertionError(why);
    }
    private static JobParameters job(String owner, int id) {
        CommandLine.setForHostTest(owner);
        TaskInfo info = TaskInfo.createTask(id, TaskInfo.OneOffInfo.create().setWindowEndTimeMs(10000).build()).build();
        PersistableBundle root = new PersistableBundle();
        root.putPersistableBundle(BackgroundTaskSchedulerJobService.hostExtrasKey(), info.getExtras());
        return new JobParameters(id, TaskInfo.getLanduJobNamespace(), root);
    }
    private static JobScheduler scheduler(Context context) throws Exception {
        Method method = BackgroundTaskSchedulerJobService.class.getDeclaredMethod("landuJobScheduler", Context.class);
        method.setAccessible(true);
        return (JobScheduler)method.invoke(null, context);
    }
    private static final class Task implements BackgroundTask {
        TaskFinishedCallback callback;
        int starts, stops;
        boolean processing = true;
        String stoppedOwner;
        @Override public boolean onStartTask(Context context, TaskParameters parameters, TaskFinishedCallback callback) {
            this.callback = callback; starts++; return processing;
        }
        @Override public boolean onStopTask(Context context, TaskParameters parameters) {
            stops++; stoppedOwner = parameters.getExtras().getString("com.landu.browser.task.OWNER"); return true;
        }
        public void reschedule(Context context) {}
    }
    private static void next(Task task) { BackgroundTaskSchedulerFactoryInternal.next = task; }
    public static void main(String[] args) throws Exception {
        NotificationIdStore ids = new NotificationIdStore(java.nio.file.Path.of(args[0]));
        IntentUtils.LanduEnvironment.initialize(A);
        IntentUtils.LanduEnvironment.bindNotificationIds(A, new IntentUtils.LanduEnvironment.NotificationIds() {
            public int getOrCreate(int id) {try{return ids.getOrCreate(A,id,ignored -> false);}catch(java.io.IOException e){throw new IllegalStateException(e);}}
            public int find(int id) {try{return ids.find(A,id);}catch(java.io.IOException e){throw new IllegalStateException(e);}}
            public boolean owns(int id) {try{return ids.owns(A,id);}catch(java.io.IOException e){throw new IllegalStateException(e);}}
        });
        Context context = new Context();
        CommandLine.setForHostTest(A);
        JobScheduler scopeA = scheduler(context);
        check(scopeA != null, "A scheduler exists");
        scopeA.schedule(new JobInfo(7));
        CommandLine.setForHostTest(B);
        JobScheduler scopeB = scheduler(context);
        check(scopeB != null && !scopeA.getNamespace().equals(scopeB.getNamespace()), "separate namespaces");
        scopeB.schedule(new JobInfo(7));
        check(scopeA.getAllPendingJobs().size() == 1 && scopeB.getAllPendingJobs().size() == 1, "same IDs coexist");
        scopeB.cancel(7);
        check(scopeA.getAllPendingJobs().size() == 1 && scopeB.getAllPendingJobs().isEmpty(), "cancel is scoped");
        check(context.scheduler.getAllPendingJobs().isEmpty(), "default namespace untouched");
        context.failService = true;
        check(scheduler(context) == null, "service error cannot fall back globally");
        context.failService = false;
        Build.VERSION.SDK_INT = 33;
        check(scheduler(context) == null, "old OS cannot use unscoped jobs");
        Build.VERSION.SDK_INT = 35;

        JobParameters a = job(A, 7), b = job(B, 7);
        CommandLine.setForHostTest(A);
        BackgroundTaskJobService service = new BackgroundTaskJobService();
        Task running = new Task(); next(running);
        int factory = BackgroundTaskSchedulerFactoryInternal.calls;
        check(service.onStartJob(a), "owned task starts");
        check(running.starts == 1, "owned task executed once");
        check(service.onStartJob(b), "foreign task waits for asynchronous reschedule");
        check(BackgroundTaskSchedulerFactoryInternal.calls == factory + 1, "foreign task never constructed");
        check(service.completions.isEmpty(), "defer is asynchronous");
        check(service.onStopJob(b), "stopped deferred task requests reschedule");
        check(running.stops == 0, "foreign same-ID stop cannot stop current task");
        Handler.drain();
        check(service.completions.isEmpty(), "stop removes deferred callback");
        running.callback.setNotification(4, new Notification());
        check(service.notifications == 1, "A remains current after stopping B");
        check(service.lastNotificationId != 4 && service.lastNotificationId == ids.find(A, 4),
                "download job bypassed persistent notification ID mapping");
        running.callback.taskFinished(false);
        check(service.completions.size() == 1 && service.completions.get(0).params == a
                && !service.completions.get(0).retry, "A completion remains in A namespace");

        check(service.onStartJob(b), "foreign task may be deferred again");
        int before = service.completions.size();
        List<Runnable> stale = Handler.queued();
        JobParameters b2 = new JobParameters(7, b.getJobNamespace(), b.getExtras());
        check(service.onStartJob(b2), "replacement defer accepted");
        stale.get(0).run();
        check(service.completions.size() == before, "stale defer cannot finish replacement");
        Handler.drain();
        check(service.completions.size() == before + 1 && service.completions.get(before).params == b2
                && service.completions.get(before).retry, "foreign one-off is rescheduled, not completed");

        JobParameters mismatched = new JobParameters(7, a.getJobNamespace(), b.getExtras());
        check(!service.onStartJob(mismatched), "namespace must match metadata");
        check(!service.onStartJob(new JobParameters(7, null, a.getExtras())), "legacy namespace rejected");
        check(!service.onStartJob(new JobParameters(7, a.getJobNamespace(), new PersistableBundle())), "missing extras rejected");
        PersistableBundle malformed = new PersistableBundle();
        malformed.putString(BackgroundTaskSchedulerJobService.hostExtrasKey(), "invalid");
        check(!service.onStartJob(new JobParameters(7, a.getJobNamespace(), malformed)), "malformed nested extras rejected");
        b.expired = true;
        check(!service.onStartJob(b) && Handler.queued().isEmpty(), "expired foreign task is not rescheduled");
        b.expired = false;

        Task first = new Task(); next(first); check(service.onStartJob(a), "first owned generation starts");
        Task replacement = new Task(); next(replacement); check(service.onStartJob(a), "replacement owned generation starts");
        before = service.completions.size();
        first.callback.taskFinished(false);
        check(service.completions.size() == before, "late task callback cannot finish replacement");
        check(service.onStopJob(mismatched), "matching key stops its current generation");
        check(replacement.stops == 1 && A.equals(replacement.stoppedOwner), "stop uses accepted parameters, not replacement payload");
        check(first.stops == 0, "old generation not stopped by replacement stop");

        Task reused = new Task(); next(reused); check(service.onStartJob(a), "reusable task first run");
        BackgroundTask.TaskFinishedCallback oldCallback = reused.callback;
        check(service.onStartJob(a), "same task object can have another run");
        before = service.completions.size(); int oldNotices = service.notifications;
        oldCallback.taskFinished(false); oldCallback.setNotification(9, new Notification());
        check(service.completions.size() == before && service.notifications == oldNotices,
                "old callback cannot affect a new run of the same task object");
        check(service.onStopJob(a), "current reused run can still stop");

        JobParameters a8 = job(A, 8);
        Task left = new Task(); next(left); check(service.onStartJob(a), "first ID starts");
        Task right = new Task(); next(right); check(service.onStartJob(a8), "second ID starts");
        before = service.completions.size();
        left.callback.taskFinished(false);
        check(service.completions.size() == before + 1 && service.completions.get(before).params == a,
                "different IDs within one namespace do not overwrite each other");
        right.callback.taskFinished(false);
        check(service.completions.size() == before + 2 && service.completions.get(before + 1).params == a8,
                "second ID keeps its own completion");

        Task immediate = new Task(); immediate.processing = false; next(immediate);
        check(!service.onStartJob(a), "immediate work completes synchronously");
        check(!service.onStopJob(a) && immediate.stops == 0, "completed task records removed");
        before = service.completions.size(); immediate.callback.taskFinished(false);
        check(service.completions.size() == before, "late immediate callback ignored");
        Handler.acceptPosts = false;
        check(!service.onStartJob(b) && Handler.queued().isEmpty(), "failed post does not retain defer");
        Handler.acceptPosts = true;
        Build.VERSION.SDK_INT = 33;
        check(!service.onStartJob(a), "old OS does not invoke unavailable namespace API");
        Build.VERSION.SDK_INT = 35;

        Task dying = new Task(); next(dying); check(service.onStartJob(a), "last owned task starts");
        check(service.onStartJob(b), "last deferred task starts");
        stale = Handler.queued(); before = service.completions.size(); int notices = service.notifications;
        service.onDestroy();
        check(Handler.queued().isEmpty(), "destroy removes deferred callbacks");
        for (Runnable callback : stale) callback.run();
        check(service.completions.size() == before, "late deferred callback ignored after destroy");
        dying.callback.taskFinished(false);
        dying.callback.setNotification(5, new Notification());
        check(service.completions.size() == before && service.notifications == notices,
                "destroy invalidates current task callbacks");
        System.out.println("{\"suite\":\"staged-job-service-protocol\",\"assertions\":" + assertions
                + ",\"passed\":true,\"platformSubstitutes\":true,\"deviceTested\":false}");
    }
}
