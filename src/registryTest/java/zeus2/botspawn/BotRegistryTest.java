package zeus2.botspawn;

import java.util.UUID;

public final class BotRegistryTest {
    public static void main(String[] args) {
        BotRegistry<Object> bots = new BotRegistry<>();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        Object first = new Object();
        Object replacement = new Object();
        bots.register("AliceBot1", alice, first);
        check(bots.owns("alicebot1", alice, first), "Owner must match regardless of name case");
        check(!bots.owns("AliceBot1", bob, first), "Another player must not own the bot");
        check(!bots.owns("AliceBot1", alice, replacement), "Reusing a name must not transfer ownership");
        check(!bots.owns("Unknown", alice, first), "Unknown bots must not be owned");
        check(bots.beginKill(first), "First death request must be accepted");
        check(!bots.beginKill(first), "Repeated death must be rejected until disconnect");
        check(bots.count(alice, entity -> entity == first) == 1, "Dying bot still occupies its slot");
        bots.prune(entity -> false);
        check(!bots.owns("AliceBot1", alice, first), "Disconnected bot ownership must be cleared");
        check(!bots.isKilling(first), "Disconnected bot death marker must be cleared");
        bots.register("AliceBot1", bob, replacement);
        check(!bots.owns("AliceBot1", alice, replacement), "Old owner must not control a new instance");
        check(bots.owns("AliceBot1", bob, replacement), "New owner must control their instance");
        check(bots.count(bob, entity -> entity == replacement) == 1, "Count exact active instances");
        check(bots.count(bob, entity -> false) == 0, "Ignore disconnected entities even before pruning");
        bots.beginKill(replacement);
        bots.clear();
        check(!bots.owns("AliceBot1", bob, replacement) && !bots.isKilling(replacement), "Reset all runtime state");
        System.out.println("14 BotRegistry regression checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
