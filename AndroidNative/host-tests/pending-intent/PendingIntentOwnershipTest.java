import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import org.chromium.base.IntentUtils.LanduEnvironment;
import org.chromium.components.browser_ui.notifications.PendingIntentProvider;

public final class PendingIntentOwnershipTest {
    private static int assertions;

    private static void check(boolean result, String message) {
        assertions++;
        if (!result) throw new AssertionError(message);
    }

    private static void rejects(Runnable action, String message) {
        assertions++;
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(message);
    }

    public static void main(String[] args) {
        String owner = args[0];
        String foreign = owner.startsWith("1111")
                ? "22222222-2222-4222-8222-222222222222"
                : "11111111-1111-4111-8111-111111111111";
        Context context = new Context();
        Intent original = new Intent("download.pause").setData("content://example/1")
                .setComponent("DownloadService").putExtra("item", "file-1");
        rejects(() -> PendingIntentProvider.getService(context, 7, original, 0),
                "creation before lease initialization must fail");
        check(PendingIntent.creations == 0, "unowned operation must not reach Android factory");
        rejects(() -> LanduEnvironment.initialize("not-a-uuid"), "invalid owner accepted");
        check(!LanduEnvironment.owns(original), "unowned intent accepted");
        LanduEnvironment.initialize(owner);
        LanduEnvironment.initialize(owner);
        rejects(() -> LanduEnvironment.initialize(foreign), "process identity can change");

        Intent scoped = LanduEnvironment.scopePendingIntent(original);
        check(scoped != original, "caller intent was reused");
        check(original.getIdentifier() == null, "caller identifier changed");
        check(LanduEnvironment.owns(scoped), "current environment not recognized");
        check(owner.equals(LanduEnvironment.ownerOf(scoped)), "owner did not round trip");
        check(scoped.getAction().equals(original.getAction()), "action changed");
        check(scoped.getData().equals(original.getData()), "data changed");
        check(scoped.getComponent().equals(original.getComponent()), "component changed");
        check("file-1".equals(scoped.getExtra("item")), "extras lost");
        scoped.putExtra("item", "changed");
        check("file-1".equals(original.getExtra("item")), "caller extras changed");
        Intent again = LanduEnvironment.scopePendingIntent(scoped);
        check(again != scoped && again.filterEquals(scoped), "rescoping is not copy-idempotent");
        check(LanduEnvironment.ownerOf(null) == null, "null accepted");

        Intent empty = LanduEnvironment.scopePendingIntent(new Intent(original).setIdentifier(""));
        check(!empty.filterEquals(scoped), "empty and null original identifiers collide");
        for (String value : new String[] {"download:1", "download:2", "中文/:0:1", "a\u0000b"}) {
            Intent withOriginal = new Intent(original).setIdentifier(value);
            Intent bound = LanduEnvironment.scopePendingIntent(withOriginal);
            check(LanduEnvironment.owns(bound), "original identifier broke ownership");
            check(value.equals(withOriginal.getIdentifier()), "original identifier mutated");
            check(bound.filterEquals(LanduEnvironment.scopePendingIntent(withOriginal)),
                    "same operation is not stable");
            check(!bound.filterEquals(scoped), "original identifier lost from identity");
        }
        String prefix = "com.landu.browser.intent:v1:";
        Intent foreignIntent = new Intent(original).setIdentifier(prefix + foreign + ":0");
        check(!LanduEnvironment.owns(foreignIntent), "foreign owner accepted");
        rejects(() -> LanduEnvironment.scopePendingIntent(foreignIntent), "foreign intent retagged");
        for (String bad : new String[] {prefix, prefix + owner, prefix + owner + ":2",
                prefix + owner + ":0trailing", prefix + "BAD:0",
                "com.landu.browser.intent:v2:" + owner + ":0"}) {
            Intent malformed = new Intent(original).setIdentifier(bad);
            check(LanduEnvironment.ownerOf(malformed) == null, "malformed marker accepted");
            rejects(() -> LanduEnvironment.scopePendingIntent(malformed), "malformed marker retagged");
        }

        int dangerousFlags = PendingIntent.FLAG_UPDATE_CURRENT | Intent.FILL_IN_IDENTIFIER;
        for (String kind : new String[] {"activity", "service", "broadcast"}) {
            PendingIntentProvider provider = create(kind, context, original, dangerousFlags, true);
            PendingIntent result = provider.getPendingIntent();
            check(result.kind.equals(kind), "wrong Android factory");
            check(provider.getRequestCode() == 7, "request code changed");
            check((provider.getFlags() & Intent.FILL_IN_IDENTIFIER) == 0,
                    "mutable sender can replace environment identity");
            check((provider.getFlags() & PendingIntent.FLAG_UPDATE_CURRENT) != 0,
                    "update behavior lost");
            check((provider.getFlags() & PendingIntent.FLAG_MUTABLE) != 0, "reply mutability lost");
            check(LanduEnvironment.owns(result.intent), "factory got unbound intent");
            Intent replaced = new Intent(original).putExtra("item", "file-2");
            PendingIntent replacement = create(kind, context, replaced, dangerousFlags, true).getPendingIntent();
            check(result == replacement, "same-environment update created a different operation");
            check("file-2".equals(result.intent.getExtra("item")), "same-environment update failed");
            String identifier = result.intent.getIdentifier();
            result.sendFillIn(new Intent().setIdentifier(prefix + foreign + ":0").putExtra("reply", "hello"));
            check(identifier.equals(result.intent.getIdentifier()), "sender changed environment");
            check("hello".equals(result.intent.getExtra("reply")), "reply extras were blocked");
            PendingIntentProvider immutable = create(kind, context, original, 0, false);
            check((immutable.getFlags() & PendingIntent.FLAG_IMMUTABLE) != 0, "immutable default lost");
            check(original.getIdentifier() == null, "provider mutated caller");
        }
        check(!PendingIntentProvider.getActivity(context, 7, original, 0).getPendingIntent().equals(
                PendingIntentProvider.getService(context, 7, original, 0).getPendingIntent()),
                "activity/service capabilities collide");
        System.out.println("{\"passed\":true,\"assertions\":" + assertions
                + ",\"identity\":\"" + LanduEnvironment.scopePendingIntent(original).getIdentifier()
                + "\",\"androidFrameworkExecuted\":false}");
    }

    private static PendingIntentProvider create(String kind, Context context, Intent intent,
            int flags, boolean mutable) {
        if (kind.equals("activity")) return PendingIntentProvider.getActivity(context, 7, intent, flags, mutable);
        if (kind.equals("service")) return PendingIntentProvider.getService(context, 7, intent, flags, mutable);
        return PendingIntentProvider.getBroadcast(context, 7, intent, flags, mutable);
    }
}
