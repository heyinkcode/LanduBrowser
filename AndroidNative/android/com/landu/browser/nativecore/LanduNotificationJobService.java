package com.landu.browser.nativecore;

import android.app.Activity;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.app.job.JobWorkItem;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** System-owned transient work queue; stopped environments are never reopened by a dismissal. */
public final class LanduNotificationJobService extends JobService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, Run> runs = new HashMap<>();
    private boolean destroyed;

    private static final class Run {
        final JobParameters params;
        final EnvironmentIdentity identity;
        final String namespace;
        JobWorkItem item;
        Run(JobParameters params, EnvironmentIdentity identity, String namespace) {
            this.params = params;
            this.identity = identity;
            this.namespace = namespace;
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        if (destroyed || Build.VERSION.SDK_INT < 34
                || params.getJobId() != LanduNotificationReceiver.JOB_ID) return false;
        String namespace = params.getJobNamespace();
        if (namespace == null || !namespace.startsWith(LanduNotificationReceiver.NAMESPACE)) return false;
        EnvironmentIdentity identity;
        try { identity = EnvironmentIdentity.parse(namespace.substring(LanduNotificationReceiver.NAMESPACE.length())); }
        catch (IllegalArgumentException error) { return false; }
        Run run = new Run(params, identity, namespace);
        runs.put(namespace, run);
        // Framework completion must not happen synchronously before returning true.
        main.post(() -> next(run));
        return true;
    }

    private boolean current(Run run) {
        return !destroyed && runs.get(run.namespace) == run;
    }

    private void next(Run run) {
        if (!current(run)) return;
        try {
            run.item = run.params.dequeueWork();
            if (run.item == null) {
                // dequeueWork(null) completes this enqueued job automatically.
                runs.remove(run.namespace);
                return;
            }
            Intent payload = LanduNotificationReceiver.copy(this, run.item.getIntent());
            if (!run.identity.equals(LanduNotificationReceiver.owner(this, payload))) {
                complete(run);
                return;
            }
            worker.execute(() -> inspect(run, payload));
        } catch (IllegalArgumentException error) {
            if (run.item != null) complete(run); else retry(run);
        } catch (RuntimeException error) { retry(run); }
    }

    private void inspect(Run run, Intent payload) {
        boolean exists = false;
        boolean allowed = false;
        boolean readable = false;
        try {
            Path control = getNoBackupFilesDir().toPath().resolve("landu-control");
            for (EnvironmentDefinition environment : new EnvironmentCatalog(control).read().environments) {
                if (environment.identity.equals(run.identity)) {
                    exists = true;
                    break;
                }
            }
            if (exists) {
                EnvironmentLeaseStore.Ticket ticket = new EnvironmentLeaseStore(control).currentLaunch();
                // Includes crash recovery of the selected session, but never writes a selection,
                // cancels another pending launch or changes a CLOSED record to PENDING.
                allowed = ticket != null && ticket.identity.equals(run.identity);
            }
            readable = true;
        } catch (IOException | RuntimeException error) { /* Retry without changing environment data. */ }
        final boolean found = exists;
        final boolean dispatch = allowed;
        final boolean ok = readable;
        main.post(() -> {
            if (!current(run)) return;
            if (!ok) retry(run);
            else if (!found) complete(run);
            else if (!dispatch) retry(run);
            else forward(run, payload);
        });
    }

    private void forward(Run run, Intent payload) {
        if (!current(run)) return;
        Intent target = new Intent(payload);
        target.setClassName(getPackageName(), LanduNotificationReceiver.TARGET);
        try {
            sendOrderedBroadcast(target, null, new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent result) {
                    if (!current(run)) return;
                    // The browser checks its fixed owner and shutdown gate before acknowledging.
                    if (getResultCode() == Activity.RESULT_OK) complete(run);
                    else retry(run);
                }
            }, main, Activity.RESULT_CANCELED, null, null);
        } catch (RuntimeException error) { retry(run); }
    }

    private void complete(Run run) {
        if (!current(run)) return;
        try { run.params.completeWork(run.item); }
        catch (RuntimeException error) { retry(run); return; }
        run.item = null;
        main.post(() -> next(run));
    }

    private void retry(Run run) {
        if (!current(run)) return;
        runs.remove(run.namespace);
        // Uncompleted work remains in the OS queue for its normal backoff retry.
        jobFinished(run.params, true);
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (Build.VERSION.SDK_INT < 34) return false;
        Run run = runs.get(params.getJobNamespace());
        if (run != null && run.params == params) runs.remove(run.namespace);
        return !destroyed;
    }

    @Override public void onDestroy() {
        destroyed = true;
        runs.clear();
        main.removeCallbacksAndMessages(null);
        worker.shutdown();
        super.onDestroy();
    }
}
