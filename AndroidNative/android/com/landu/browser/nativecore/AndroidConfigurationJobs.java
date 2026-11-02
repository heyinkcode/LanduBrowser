package com.landu.browser.nativecore;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executors;

/** Lazily initialized from the manager UI; work captures application services, never Activity. */
final class AndroidConfigurationJobs {
    private static final class Holder {
        static final Handler MAIN = new Handler(Looper.getMainLooper());
        static final EnvironmentTransferController INSTANCE = new EnvironmentTransferController(
                Executors.newSingleThreadExecutor(), command -> MAIN.post(command));
    }

    static EnvironmentTransferController get() { return Holder.INSTANCE; }
    private AndroidConfigurationJobs() { }
}
