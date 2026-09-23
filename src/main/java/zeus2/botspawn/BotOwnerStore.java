package zeus2.botspawn;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Records ownership in the world directory after each completed spawn or removal. */
final class BotOwnerStore {
    private static final String HEADER = "# botspawn-owners-v1";
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final Path path;
    private final Map<String, Owner> owners = new HashMap<>();

    BotOwnerStore(Path path) throws IOException {
        this.path = path;
        if (Files.notExists(path)) return;
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !HEADER.equals(lines.get(0))) {
            throw new IOException("Unsupported BotSpawn ownership file: " + path);
        }
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split("\\t", -1);
            if (parts.length != 4 || !USERNAME.matcher(parts[0]).matches()
                    || !USERNAME.matcher(parts[3]).matches()) {
                throw new IOException("Invalid BotSpawn ownership record at line " + (i + 1));
            }
            try {
                Owner owner = new Owner(parts[0], UUID.fromString(parts[1]),
                        UUID.fromString(parts[2]), parts[3]);
                if (owners.putIfAbsent(key(owner.name()), owner) != null) {
                    throw new IOException("Duplicate bot name at line " + (i + 1));
                }
            } catch (IllegalArgumentException ex) {
                throw new IOException("Invalid UUID at line " + (i + 1), ex);
            }
        }
    }

    Owner get(String name) {
        return owners.get(key(name));
    }

    List<Owner> entries() {
        return List.copyOf(owners.values());
    }

    void record(Owner owner) throws IOException {
        Map<String, Owner> updated = new HashMap<>(owners);
        updated.put(key(owner.name()), owner);
        write(updated);
        owners.clear();
        owners.putAll(updated);
    }

    void remove(String name, UUID botUuid) throws IOException {
        Owner existing = get(name);
        if (existing == null || !existing.botUuid().equals(botUuid)) return;
        Map<String, Owner> updated = new HashMap<>(owners);
        updated.remove(key(name));
        write(updated);
        owners.clear();
        owners.putAll(updated);
    }

    private void write(Map<String, Owner> updated) throws IOException {
        Files.createDirectories(path.getParent());
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        updated.values().stream().sorted((a, b) -> key(a.name()).compareTo(key(b.name())))
                .forEach(owner -> lines.add(owner.name() + "\t" + owner.botUuid()
                        + "\t" + owner.ownerUuid() + "\t" + owner.ownerName()));
        byte[] data = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            try (FileChannel file = FileChannel.open(temp, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    record Owner(String name, UUID botUuid, UUID ownerUuid, String ownerName) {
    }
}
