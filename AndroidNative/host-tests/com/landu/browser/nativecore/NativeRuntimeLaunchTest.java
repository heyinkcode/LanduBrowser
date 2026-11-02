package com.landu.browser.nativecore;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Exercises only Java session/route decisions. No Android methods or UI are executed. */
public final class NativeRuntimeLaunchTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).resolve("runtime-session-" + UUID.randomUUID());
        Files.createDirectories(root);
        EnvironmentIdentity identity = EnvironmentIdentity.parse("10af0101-2000-4000-8000-000000000001");
        EnvironmentLeaseStore store = new EnvironmentLeaseStore(root.resolve("control"));
        EnvironmentCatalog catalog = new EnvironmentCatalog(root.resolve("control"));
        EnvironmentDefinition initial = new EnvironmentDefinition(identity, "Before edit", "https://initial.example/", "", 100, 200);
        initial = catalog.save(0, initial).find(identity);
        EnvironmentLeaseStore.Ticket ticket = store.prepareLaunch(identity);
        try (EnvironmentLeaseStore.Session session = store.claimSelected(root.resolve("environments"))) {
            inject("session", session);
            inject("selectedEnvironment", initial);
            inject("controlRoot", root.resolve("control"));
            inject("shutdown", null);
            inject("browserLaunchDispatched", false);
            check(!NativeEnvironmentRuntime.stopHandlerReady(identity.id(), ticket.nonce),
                    "A validated early stop must wait for the native shutdown handler");
            rejects(() -> NativeEnvironmentRuntime.stopHandlerReady(identity.id(), UUID.randomUUID().toString()));
            rejects(() -> NativeEnvironmentRuntime.stopHandlerReady("10af0101-2000-4000-8000-000000000002", ticket.nonce));
            EnvironmentShutdownCoordinator coordinator = new EnvironmentShutdownCoordinator(
                    new EnvironmentShutdownCoordinator.Operations() {
                        @Override public void blockBackgroundRestart() { throw new AssertionError("Readiness must not begin shutdown"); }
                        @Override public void restoreAfterFailure() { throw new AssertionError("Readiness must not restore shutdown"); }
                        @Override public void flush(EnvironmentShutdownCoordinator.Result result) { throw new AssertionError("Readiness must not flush"); }
                        @Override public void terminate() { throw new AssertionError("Readiness must not terminate"); }
                    }, (action, delay) -> { throw new AssertionError("Readiness must not schedule shutdown"); });
            inject("shutdown", coordinator);
            java.util.concurrent.atomic.AtomicBoolean initialized = new java.util.concurrent.atomic.AtomicBoolean();
            inject("nativeInitializationComplete", (java.util.function.BooleanSupplier) initialized::get);
            check(!NativeEnvironmentRuntime.stopHandlerReady(identity.id(), ticket.nonce),
                    "A registered shutdown handler must still wait for browser initialization");
            rejects(() -> NativeEnvironmentRuntime.requestStop(identity.id(), ticket.nonce,
                    error -> { throw new AssertionError("Uninitialized shutdown must not start"); }));
            initialized.set(true);
            check(NativeEnvironmentRuntime.stopHandlerReady(identity.id(), ticket.nonce),
                    "The validated owner can close once browser initialization completes");
            rejects(() -> NativeEnvironmentRuntime.stopHandlerReady(identity.id(), UUID.randomUUID().toString()));
            rejects(() -> NativeEnvironmentRuntime.stopHandlerReady("10af0101-2000-4000-8000-000000000002", ticket.nonce));
            check(store.observe().belongsTo(identity), "Readiness probes must preserve the live lease");
            inject("shutdown", null);
            inject("nativeInitializationComplete", null);
            check(NativeEnvironmentRuntime.verifyLaunch(identity.id(), ticket.nonce) == initial,
                    "The first navigation must use the startup snapshot");
            check(!NativeEnvironmentRuntime.shouldResumeWindow(identity.id(), ticket.nonce, false, false),
                    "The first normal request must open the configured start page");
            EnvironmentDefinition edited = initial.edit("After edit", "https://edited.example/", "changed")
                    .withFingerprint(initial.fingerprint.withHardware(8, 4).withCanvasNoise(true)
                        .withGpuMetadata(new EnvironmentGpuMetadata("vendor", "renderer", "vendor", "", "", "")))
                    .withAndroidIdentity(new EnvironmentAndroidIdentity("15", "Pixel 8"));
            catalog.save(1, edited);
            EnvironmentDefinition running = NativeEnvironmentRuntime.verifyLaunch(identity.id(), ticket.nonce);
            check(running == initial && running.startPage.equals("https://initial.example/")
                    && running.fingerprint.hardwareConcurrency == 0 && !running.fingerprint.canvasNoise && running.androidIdentity.isSystem()
                    && running.fingerprint.gpuMetadata.isSystem(),
                    "Saving edits must not mutate or replace a running configuration snapshot");
            check(catalog.read().find(identity).name.equals("After edit"), "The edit must still be saved for the next launch");
            check(catalog.read().find(identity).fingerprint.gpuMetadata.values()[1].equals("renderer"),
                    "GPU edits must be saved for the next launch without mutating the running snapshot");
            EnvironmentGroup group = EnvironmentGroup.create("工作分组");
            EnvironmentCatalog.Snapshot groups = catalog.saveGroup(2, group);
            EnvironmentDefinition classified = groups.find(identity).withOrganization(
                    new EnvironmentOrganization(1, group.id, "当前工作"));
            groups = catalog.save(groups.revision, classified);
            check(NativeEnvironmentRuntime.verifyLaunch(identity.id(), ticket.nonce) == initial
                    && store.observe().belongsTo(identity), "group edits preserve the active snapshot and selected environment lease");
            check(groups.find(identity).organization.groupId.equals(group.id)
                    && groups.find(identity).organization.tags.equals("当前工作"), "organization edits persist while the browser keeps running");
            groups = catalog.removeGroup(groups.revision, group.id);
            check(groups.find(identity).organization.groupId.isEmpty()
                    && NativeEnvironmentRuntime.verifyLaunch(identity.id(), ticket.nonce) == initial
                    && store.observe().belongsTo(identity), "removing a group does not stop or switch its running environment");
            EnvironmentCatalog.ImportResult imported = catalog.importConfiguration(groups.revision,
                    EnvironmentCatalog.decodeConfiguration(catalog.exportConfiguration()), true);
            check(imported.updated == 1 && NativeEnvironmentRuntime.verifyLaunch(identity.id(), ticket.nonce) == initial
                    && store.observe().belongsTo(identity), "explicit configuration import preserves the running snapshot and lease");
            rejects(() -> NativeEnvironmentRuntime.noteBrowserLaunchDispatched(identity.id(), UUID.randomUUID().toString()));
            check(!NativeEnvironmentRuntime.shouldResumeWindow(identity.id(), ticket.nonce, false, false),
                    "A rejected request cannot consume the initial navigation");
            NativeEnvironmentRuntime.noteBrowserLaunchDispatched(identity.id(), ticket.nonce);
            check(NativeEnvironmentRuntime.shouldResumeWindow(identity.id(), ticket.nonce, false, false),
                    "A fast duplicate pending request must resume after the first dispatch");
            check(NativeEnvironmentRuntime.shouldResumeWindow(identity.id(), ticket.nonce, true, false),
                    "An explicit resume must keep the current tab");
            check(!NativeEnvironmentRuntime.shouldResumeWindow(identity.id(), ticket.nonce, true, true),
                    "An external link must still request navigation while the browser is running");
            check(!NativeEnvironmentRuntime.shouldResumeWindow(identity.id(), ticket.nonce, false, true),
                    "An external link must not be dropped as a duplicate startup request");
            rejects(() -> NativeEnvironmentRuntime.verifyLaunch("10af0101-2000-4000-8000-000000000002", ticket.nonce));
            rejects(() -> NativeEnvironmentRuntime.shouldResumeWindow(identity.id(), UUID.randomUUID().toString(), true, false));
            session.close();
            rejects(() -> NativeEnvironmentRuntime.stopHandlerReady(identity.id(), ticket.nonce));
            rejects(() -> NativeEnvironmentRuntime.verifyLaunch(identity.id(), ticket.nonce));
            rejects(() -> NativeEnvironmentRuntime.noteBrowserLaunchDispatched(identity.id(), ticket.nonce));
        } finally {
            inject("session", null); inject("selectedEnvironment", null); inject("controlRoot", null);
            inject("shutdown", null); inject("browserLaunchDispatched", false);
            inject("nativeInitializationComplete", null);
        }
        String report = "{\"suite\":\"native-runtime-java-launch-decisions\",\"assertions\":" + assertions
                + ",\"passed\":true,\"androidRuntimeExecuted\":false,\"uiTested\":false}";
        Files.writeString(root.resolve("result.json"), report);
        System.out.println(report);
    }

    private static void inject(String name, Object value) throws Exception {
        Field field = NativeEnvironmentRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void rejects(Action action) throws Exception {
        assertions++;
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected a stale or closed-session request to be rejected");
    }

    private interface Action { void run() throws Exception; }
}
