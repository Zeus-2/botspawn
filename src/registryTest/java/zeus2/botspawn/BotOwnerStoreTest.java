package zeus2.botspawn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class BotOwnerStoreTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("botspawn-store-test-");
        Path file = directory.resolve("world").resolve("botspawn-owners.txt");
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID firstBot = UUID.randomUUID();
        UUID secondBot = UUID.randomUUID();
        BotOwnerStore store = new BotOwnerStore(file);
        store.record(new BotOwnerStore.Owner("AliceBot1", firstBot, alice, "Alice"));
        store.record(new BotOwnerStore.Owner("BobBot1", secondBot, bob, "Bob"));
        store = new BotOwnerStore(file);
        check(store.entries().size() == 2, "Both owners survive restart");
        check(store.get("alicebot1").ownerUuid().equals(alice), "Lookup is case insensitive");
        Files.writeString(file.resolveSibling("botspawn-owners.txt.tmp"), "interrupted write");
        check(new BotOwnerStore(file).entries().size() == 2, "Interrupted temp write does not corrupt saved owners");
        store.remove("AliceBot1", secondBot);
        check(store.get("AliceBot1") != null, "Wrong bot UUID cannot remove ownership");
        store.remove("AliceBot1", firstBot);
        store = new BotOwnerStore(file);
        check(store.get("AliceBot1") == null, "Killed bot stays removed after restart");
        check(store.get("BobBot1").ownerUuid().equals(bob), "Other records survive removal");
        check(!Files.exists(file.resolveSibling("botspawn-owners.txt.tmp")), "No unfinished temp file");
        Files.writeString(file, "corrupted file", StandardCharsets.UTF_8);
        try {
            new BotOwnerStore(file);
            throw new AssertionError("Corruption must be rejected instead of silently replaced");
        } catch (IOException expected) {
            check(Files.readString(file).equals("corrupted file"), "Corrupted source is preserved");
        }
        System.out.println("8 BotOwnerStore regression checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
