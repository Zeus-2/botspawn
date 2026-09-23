package zeus2.botspawn;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Server-thread-only ownership for exact connected bot instances. */
final class BotRegistry<T> {
    private final Map<String, Entry<T>> owners = new HashMap<>();
    private final Set<T> killing = Collections.newSetFromMap(new IdentityHashMap<>());

    void register(String name, UUID owner, T bot) {
        owners.put(name.toLowerCase(Locale.ROOT), new Entry<>(owner, bot));
    }

    boolean owns(String name, UUID owner, T bot) {
        Entry<T> entry = owners.get(name.toLowerCase(Locale.ROOT));
        return entry != null && entry.bot() == bot && entry.owner().equals(owner);
    }

    UUID ownerOf(String name, T bot) {
        Entry<T> entry = owners.get(name.toLowerCase(Locale.ROOT));
        return entry != null && entry.bot() == bot ? entry.owner() : null;
    }

    boolean beginKill(T bot) {
        return killing.add(bot);
    }

    boolean isKilling(T bot) {
        return killing.contains(bot);
    }

    int count(UUID owner, Predicate<T> connected) {
        return (int) owners.values().stream()
                .filter(entry -> entry.owner().equals(owner) && connected.test(entry.bot())).count();
    }

    void prune(Predicate<T> connected) {
        owners.values().removeIf(entry -> !connected.test(entry.bot()));
        killing.removeIf(bot -> !connected.test(bot));
    }

    void clear() {
        owners.clear();
        killing.clear();
    }

    private record Entry<T>(UUID owner, T bot) {
    }
}
