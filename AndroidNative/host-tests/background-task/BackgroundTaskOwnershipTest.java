import android.os.PersistableBundle;
import org.chromium.base.CommandLine;
import org.chromium.components.background_task_scheduler.TaskInfo;

public final class BackgroundTaskOwnershipTest {
    private static final String A = "11111111-1111-4111-8111-111111111111";
    private static final String B = "22222222-2222-4222-8222-222222222222";
    private static final String OWNER = "com.landu.browser.task.OWNER";
    private static final String SCHEMA = "com.landu.browser.task.SCHEMA";
    private static int assertions;
    private static void check(boolean condition) {
        assertions++;
        if (!condition) throw new AssertionError("Check " + assertions);
    }
    private static TaskInfo build(PersistableBundle extras) {
        return TaskInfo.createTask(123, TaskInfo.OneOffInfo.create().setWindowEndTimeMs(10000).build())
                .setExtras(extras).setRequiresCharging(true).setIsPersisted(true).setUpdateCurrent(true).build();
    }
    private static void refused(PersistableBundle extras) {
        assertions++;
        try { build(extras); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Foreign or malformed task was retagged");
    }
    public static void main(String[] args) {
        CommandLine.clearForHostTest();
        PersistableBundle caller = new PersistableBundle();
        caller.putString("download", "original");
        TaskInfo early = build(caller);
        check(!TaskInfo.hasMatchingLanduEnvironment(early.getExtras()));
        CommandLine.setForHostTest(A);
        check(!TaskInfo.hasMatchingLanduEnvironment(early.getExtras()));
        TaskInfo first = build(caller);
        String namespaceA = TaskInfo.getLanduJobNamespace();
        check(("com.landu.browser.environment." + A).equals(namespaceA));
        check(namespaceA.equals(TaskInfo.getLanduJobNamespace(first.getExtras())));
        check(TaskInfo.hasMatchingLanduEnvironment(first.getExtras()));
        check(A.equals(first.getExtras().getString(OWNER)));
        check(first.getExtras().getInt(SCHEMA, 0) == 1);
        check(!caller.containsKey(OWNER) && !caller.containsKey(SCHEMA));
        check(first.getExtras() != caller);
        check(first.getTaskId() == 123 && first.requiresCharging() && first.isPersisted() && first.shouldUpdateCurrent());
        caller.putString("download", "changed-after-build");
        check("original".equals(first.getExtras().getString("download")));
        TaskInfo repeated = build(first.getExtras());
        check(TaskInfo.hasMatchingLanduEnvironment(repeated.getExtras()));
        check(repeated.getExtras() != first.getExtras());
        CommandLine.setForHostTest(B);
        check(!namespaceA.equals(TaskInfo.getLanduJobNamespace()));
        check(namespaceA.equals(TaskInfo.getLanduJobNamespace(first.getExtras())));
        check(!TaskInfo.hasMatchingLanduEnvironment(first.getExtras()));
        refused(first.getExtras());
        check(A.equals(first.getExtras().getString(OWNER)));
        TaskInfo second = build(caller);
        check(B.equals(second.getExtras().getString(OWNER)));
        check(TaskInfo.hasMatchingLanduEnvironment(second.getExtras()));
        CommandLine.setForHostTest(A);
        check(TaskInfo.hasMatchingLanduEnvironment(first.getExtras()));
        check(!TaskInfo.hasMatchingLanduEnvironment(second.getExtras()));
        for (int schema : new int[] {0, 2, -1, Integer.MAX_VALUE}) {
            PersistableBundle wrong = new PersistableBundle(first.getExtras());
            wrong.putInt(SCHEMA, schema);
            check(!TaskInfo.hasMatchingLanduEnvironment(wrong));
            check(TaskInfo.getLanduJobNamespace(wrong) == null);
            refused(wrong);
        }
        PersistableBundle onlyOwner = new PersistableBundle(); onlyOwner.putString(OWNER, A);
        check(!TaskInfo.hasMatchingLanduEnvironment(onlyOwner)); refused(onlyOwner);
        PersistableBundle onlySchema = new PersistableBundle(); onlySchema.putInt(SCHEMA, 1);
        check(!TaskInfo.hasMatchingLanduEnvironment(onlySchema)); refused(onlySchema);
        PersistableBundle wrongType = new PersistableBundle(first.getExtras()); wrongType.putString(SCHEMA, "1");
        check(!TaskInfo.hasMatchingLanduEnvironment(wrongType)); refused(wrongType);
        for (String owner : new String[] {"", "invalid", A.toUpperCase(), "1-1-1-1-1", "00000000-0000-0000-0000-00000000000z"}) {
            // A is numerical; use a separate uppercase UUID to cover canonicalization.
            if (owner.equals(A)) owner = "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA";
            CommandLine.setForHostTest(owner);
            check(!TaskInfo.hasMatchingLanduEnvironment(first.getExtras()));
            check(TaskInfo.getLanduJobNamespace() == null);
            TaskInfo invalid = build(new PersistableBundle());
            check(!invalid.getExtras().containsKey(OWNER));
        }
        CommandLine.clearForHostTest();
        check(!TaskInfo.hasMatchingLanduEnvironment(first.getExtras()));
        CommandLine.setForHostTest(A);
        check(TaskInfo.hasMatchingLanduEnvironment(first.getExtras()));
        System.out.println("{\"suite\":\"staged-background-task-ownership\",\"assertions\":" + assertions
                + ",\"passed\":true,\"platformSubstitutes\":true,\"deviceTested\":false}");
    }
}
