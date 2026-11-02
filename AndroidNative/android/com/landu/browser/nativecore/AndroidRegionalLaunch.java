package com.landu.browser.nativecore;

import android.content.Context;
import java.io.IOException;
import java.nio.file.Path;

/** Shared by normal, external-link and cold notification launches; all IO runs on their workers. */
public final class AndroidRegionalLaunch {
    private AndroidRegionalLaunch() { }
    public static EnvironmentLeaseStore.LaunchRequest prepare(Context context, EnvironmentLeaseStore leases,
            EnvironmentIdentity identity) throws IOException {
        Path control = context.getNoBackupFilesDir().toPath().resolve("landu-control");
        EnvironmentCatalog catalog = new EnvironmentCatalog(control);
        EnvironmentDefinition environment = catalog.read().find(identity);
        EnvironmentLeaseStore.LaunchRequest request = leases.requestLaunch(identity);
        if (request.resumeExisting) return request; // A live process retains its original launch snapshot.
        boolean prepared = false;
        try {
            ProxyCredentials credentials = environment.network.requiresAuthentication
                    ? AndroidProxyCredentials.open(control).read(identity, environment.network) : null;
            EnvironmentExitInfo measured = null; String failure = "";
            try { measured = AndroidExitLookup.lookup(environment, credentials); }
            catch (IOException lookupFailure) {
                if (environment.regionalPolicy.needsExitLookup()) {
                    throw new IOException("出口检测失败，未启动环境。请检查当前网络或代理后重试；也可改用固定地区设置。", lookupFailure);
                }
                failure = "出口检测失败；已按固定或设备设置启动";
            }
            EnvironmentRegionalLaunchStore store = new EnvironmentRegionalLaunchStore(control);
            store.write(environment, request.ticket, measured, failure);
            store.read(catalog.read().find(identity), request.ticket); // Reject concurrent edits/replaced identities.
            EnvironmentLeaseStore.Ticket pending = leases.pendingLaunch();
            if (pending == null || !pending.nonce.equals(request.ticket.nonce)
                    || Thread.currentThread().isInterrupted()) throw new IOException("启动已取消，请重试");
            prepared = true;
            return request;
        } finally {
            if (!prepared) {
                try { leases.cancelPending(request.ticket); } catch (IOException ignored) { }
            }
        }
    }
}
