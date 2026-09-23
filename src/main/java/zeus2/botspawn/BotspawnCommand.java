package zeus2.botspawn;

import carpet.patches.EntityPlayerMPFake;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.NodeType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.UUIDUtil;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.OldUsersConverter;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;

public final class BotspawnCommand {
    private BotspawnCommand() {
    }

    public static final String PERMISSION_USE = "avience.botspawn.use";
    public static final String PERMISSION_STAFF = "avience.botspawn.staff";
    public static final String PERMISSION_KILL = "avience.botspawn.kill";
    // Keep the override outside the kill.* tree: permission providers may
    // interpret a granted parent node as permission for its children.
    public static final String PERMISSION_KILL_OTHERS = "avience.botspawn.killothers";

    public static final String PERMISSION_LIMIT_1 = "avience.botspawn.limit.1";
    public static final String PERMISSION_LIMIT_2 = "avience.botspawn.limit.2";
    public static final String PERMISSION_LIMIT_3 = "avience.botspawn.limit.3";
    public static final String PERMISSION_LIMIT_5 = "avience.botspawn.limit.5";
    public static final String PERMISSION_LIMIT_10 = "avience.botspawn.limit.10";
    public static final String PERMISSION_LIMIT_UNLIMITED = "avience.botspawn.limit.unlimited";

    private static final String BOT_GROUP = "bot";
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final int SPAWN_TIMEOUT_TICKS = 600;
    private static final int UNLIMITED_SLOT_SCAN_MAX = 10_000;

    /**
     * Bot name (lower-case) -> owner UUID and the exact connected entity.
     *
     * This is only used for active bots created by this mod during the current
     * server runtime. Donor names also encode their owner in the username.
     */
    private static final BotRegistry<ServerPlayer> BOTS = new BotRegistry<>();

    /** Bot name (lower-case) -> pending spawn. */
    private static final Map<String, PendingSpawn> PENDING = new HashMap<>();
    private static final Set<String> REJECTED_PROFILES = new HashSet<>();
    private static final Set<String> RESTORE_KEYS = new HashSet<>();
    private static final Map<String, String> ACTIVE_OWNER_NAMES = new HashMap<>();
    private static BotOwnerStore STORE;

    private static long serverTicks;

    public static void reset() {
        PENDING.clear();
        REJECTED_PROFILES.clear();
        RESTORE_KEYS.clear();
        ACTIVE_OWNER_NAMES.clear();
        STORE = null;
        BOTS.clear();
        serverTicks = 0;
    }

    public static void start(MinecraftServer server) {
        reset();
        try {
            STORE = new BotOwnerStore(server.getWorldPath(LevelResource.ROOT).resolve("botspawn-owners.txt"));
            for (BotOwnerStore.Owner owner : STORE.entries()) RESTORE_KEYS.add(key(owner.name()));
            Botspawn.LOGGER.info("Loaded {} BotSpawn ownership records", RESTORE_KEYS.size());
        } catch (IOException exception) {
            // Preserve a damaged file for manual recovery. Never overwrite it
            // with an empty list just because reading failed.
            Botspawn.LOGGER.error("Could not read BotSpawn ownership file. Ownership will not persist until repaired.", exception);
        }
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("botspawn")
                .requires(source -> source.getEntity() instanceof ServerPlayer
                        && Permissions.check(source, PERMISSION_USE, false));

        addGamemode(root, "survival", GameType.SURVIVAL);
        addGamemode(root, "spectator", GameType.SPECTATOR);

        dispatcher.register(root);

        registerList(dispatcher);

        dispatcher.register(Commands.literal("botkill")
                .requires(source -> source.getEntity() instanceof ServerPlayer
                        && Permissions.check(source, PERMISSION_KILL, false))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((context, builder) -> suggestKillableBots(context.getSource(), builder))
                        .executes(context -> killBot(context.getSource(),
                                StringArgumentType.getString(context, "name")))));
    }

    public static void registerList(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("botlist")
                .executes(context -> listBots(context.getSource())));
    }

    private static int listBots(CommandSourceStack source) {
        List<ServerPlayer> bots = new ArrayList<>();
        for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
            if (player instanceof EntityPlayerMPFake) bots.add(player);
        }
        bots.sort(Comparator.comparing(bot -> key(bot.getName().getString())));
        source.sendSuccess(() -> Component.literal("Connected bots: " + bots.size()), false);
        for (ServerPlayer bot : bots) {
            String name = bot.getName().getString();
            String ownerName = ACTIVE_OWNER_NAMES.get(key(name));
            String owner = ownerName == null || BOTS.ownerOf(name, bot) == null ? "unassigned" : ownerName;
            source.sendSuccess(() -> Component.literal(name + " — owner: " + owner
                    + " — " + gameTypeName(bot.gameMode.getGameModeForPlayer())), false);
        }
        return Math.max(1, bots.size());
    }

    private static CompletableFuture<Suggestions> suggestKillableBots(CommandSourceStack source, SuggestionsBuilder builder) {
        if (!(source.getEntity() instanceof ServerPlayer caller)) {
            return builder.buildFuture();
        }
        boolean override = Permissions.check(source, PERMISSION_KILL_OTHERS, false);
        for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
            if (player instanceof EntityPlayerMPFake
                    && !BOTS.isKilling(player)
                    && (override || ownsBot(caller.getUUID(), player))
                    && player.getName().getString().toLowerCase(Locale.ROOT)
                    .startsWith(builder.getRemainingLowerCase())) {
                builder.suggest(player.getName().getString());
            }
        }
        return builder.buildFuture();
    }

    private static int killBot(CommandSourceStack source, String username) {
        if (!(source.getEntity() instanceof ServerPlayer caller)) {
            source.sendFailure(Component.literal("Only a player can use this command."));
            return 0;
        }
        // Recheck at execution time: command visibility and suggestions are not authorization.
        if (!Permissions.check(caller, PERMISSION_KILL, false)) {
            source.sendFailure(Component.literal("You do not have permission to use /botkill."));
            return 0;
        }
        ServerPlayer target = findOnlinePlayer(source.getServer(), username);
        if (!(target instanceof EntityPlayerMPFake bot)) {
            source.sendFailure(Component.literal("No connected Carpet bot has that name."));
            return 0;
        }
        if (!ownsBot(caller.getUUID(), bot)
                && !Permissions.check(caller, PERMISSION_KILL_OTHERS, false)) {
            source.sendFailure(Component.literal("You can only kill your own bots."));
            return 0;
        }
        if (!BOTS.beginKill(bot)) {
            source.sendFailure(Component.literal("That bot is already being killed."));
            return 0;
        }

        // Carpet disconnects fake players as part of the normal death path.
        // Item drops follow the server's ordinary death rules.
        try {
            bot.die(bot.damageSources().genericKill());
        } catch (RuntimeException exception) {
            Botspawn.LOGGER.error("Failed to kill Carpet bot {}", bot.getName().getString(), exception);
            source.sendFailure(Component.literal("Could not finish killing the bot; check the server log."));
            return 0;
        }
        forgetOwner(source.getServer(), bot.getName().getString(), bot.getUUID());
        // Retain ownership and the limit slot until Carpet has disconnected
        // this exact entity. Also prevent repeated death processing meanwhile.
        source.sendSuccess(() -> Component.literal("Killed " + bot.getName().getString() + "."), true);
        return 1;
    }

    private static boolean ownsBot(UUID ownerUuid, ServerPlayer bot) {
        return BOTS.owns(bot.getName().getString(), ownerUuid, bot);
    }

    private static void addGamemode(
            com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> root,
            String literal,
            GameType gameType
    ) {
        root.then(Commands.literal(literal)
                // Donor / normal form: /botspawn <gamemode>
                .executes(context -> spawnAutomatic(context.getSource(), gameType))

                // Staff form: /botspawn <gamemode> <name>
                .then(Commands.argument("name", StringArgumentType.word())
                        .requires(source -> Permissions.check(source, PERMISSION_STAFF, false))
                        .executes(context -> spawnCustom(
                                context.getSource(),
                                gameType,
                                StringArgumentType.getString(context, "name")
                        ))));
    }

    private static int spawnAutomatic(CommandSourceStack source, GameType gameType) {
        final ServerPlayer owner;
        try {
            owner = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Only a player can use this command."));
            return 0;
        }

        int limit = getBotLimit(owner);
        if (limit == 0) {
            source.sendFailure(Component.literal("You do not have a bot-limit permission."));
            return 0;
        }

        int used = countOwnedBots(source.getServer(), owner.getUUID());
        if (limit > 0 && used >= limit) {
            source.sendFailure(Component.literal("You have reached your bot limit (" + limit + ")."));
            return 0;
        }

        if (owner.getName().getString().length() > 12) {
            source.sendFailure(Component.literal("Your username is too long to append Bot1 within Minecraft's 16-character limit. Ask staff to spawn a custom name."));
            return 0;
        }
        String username = findAutomaticName(owner);
        if (username == null) {
            source.sendFailure(Component.literal(
                    "No available bot slot could be created for your username."
            ));
            return 0;
        }

        return beginSpawn(source, owner, username, gameType, false);
    }

    private static int spawnCustom(CommandSourceStack source, GameType gameType, String username) {
        final ServerPlayer owner;
        try {
            owner = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Only a player can use this command."));
            return 0;
        }

        // Do the check again at execution time; the Brigadier requirement mainly
        // controls visibility/tab-completion.
        if (!Permissions.check(owner, PERMISSION_STAFF, false)) {
            source.sendFailure(Component.literal("You do not have permission to specify a custom bot name."));
            return 0;
        }

        int limit = getBotLimit(owner);
        if (limit == 0) {
            source.sendFailure(Component.literal("You do not have a bot-limit permission."));
            return 0;
        }

        int used = countOwnedBots(source.getServer(), owner.getUUID());
        if (limit > 0 && used >= limit) {
            source.sendFailure(Component.literal("You have reached your bot limit (" + limit + ")."));
            return 0;
        }

        if (!VALID_NAME.matcher(username).matches()) {
            source.sendFailure(Component.literal(
                    "Bot names must be 3-16 characters using only A-Z, a-z, 0-9 or _."
            ));
            return 0;
        }

        if (nameInUse(source.getServer(), username)) {
            source.sendFailure(Component.literal("That player name is already in use."));
            return 0;
        }

        // Staff intentionally bypass the whitelist-name protection here.
        return beginSpawn(source, owner, username, gameType, true);
    }

    private static int beginSpawn(
            CommandSourceStack source,
            ServerPlayer owner,
            String username,
            GameType gameType,
            boolean staffCustomName
    ) {
        MinecraftServer server = source.getServer();
        String key = key(username);

        if (gameType != GameType.SURVIVAL && gameType != GameType.SPECTATOR) {
            source.sendFailure(Component.literal("Bots can only spawn in survival or spectator mode."));
            return 0;
        }

        if (PENDING.containsKey(key) || EntityPlayerMPFake.isSpawningPlayer(username)) {
            source.sendFailure(Component.literal("That player name is already being spawned."));
            return 0;
        }

        if (nameInUse(server, username)) {
            source.sendFailure(Component.literal("That player name is already in use."));
            return 0;
        }

        if (!Permissions.check(owner, PERMISSION_USE, false)
                || (staffCustomName && !Permissions.check(owner, PERMISSION_STAFF, false))) {
            source.sendFailure(Component.literal("You no longer have permission to spawn this bot."));
            return 0;
        }
        if (!staffCustomName && isWhitelisted(server, username)) {
            source.sendFailure(Component.literal("That name belongs to a whitelisted player."));
            return 0;
        }

        // Carpet fake players do not pass through Fabric's normal network pre-login
        // phase. LuckPerms normally loads user data during that phase, so without
        // doing it ourselves LuckPerms sees the fake player at JOIN with no cached
        // User and disconnects it.
        //
        // Match Carpet's UUID lookup: registered/cached profiles can have an
        // online UUID, even when the server itself is in offline mode.
        UUID resolvedUuid;
        try {
            server.services().nameToIdCache().resolveOfflineUsers(false);
            resolvedUuid = OldUsersConverter.convertMobOwnerIfNecessary(server, username);
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Could not resolve the bot profile; try again later."));
            Botspawn.LOGGER.error("Profile resolution failed for {}", username, exception);
            return 0;
        } finally {
            server.services().nameToIdCache().resolveOfflineUsers(
                    server.isDedicatedServer() && server.usesAuthentication());
        }
        UUID botUuid = resolvedUuid != null ? resolvedUuid : UUIDUtil.createOfflinePlayerUUID(username);

        PendingSpawn request = new PendingSpawn(
                owner.getUUID(), owner.getName().getString(), botUuid, username, gameType,
                serverTicks + SPAWN_TIMEOUT_TICKS, staffCustomName, false
        );
        PENDING.put(key, request);

        source.sendSuccess(() -> Component.literal(
                "Preparing " + username + " in " + gameTypeName(gameType) + " mode..."
        ), false);

        final LuckPerms luckPerms;
        try {
            luckPerms = LuckPermsProvider.get();
        } catch (IllegalStateException e) {
            PENDING.remove(key);
            source.sendFailure(Component.literal("LuckPerms is not available; bot spawn cancelled."));
            Botspawn.LOGGER.error("LuckPerms API became unavailable while spawning {}", username, e);
            return 0;
        }

        try {
            luckPerms.getUserManager().loadUser(botUuid, username).whenComplete((user, throwable) ->
                    server.execute(() -> {
                        // The request may have timed out or been removed while LuckPerms
                        // was loading. Do not resurrect an expired request.
                        PendingSpawn pending = PENDING.get(key);
                        if (pending != request) {
                            return;
                        }

                        if (throwable != null) {
                            PENDING.remove(key);
                            ServerPlayer requestOwner = findOnlinePlayer(server, pending.ownerUuid());
                            if (requestOwner != null) {
                                requestOwner.sendSystemMessage(Component.literal(
                                        "Could not load LuckPerms data for bot " + username + "."
                                ));
                            }
                            Botspawn.LOGGER.error("Failed to preload LuckPerms user data for {} ({})",
                                    username, botUuid, throwable);
                            return;
                        }

                        // Re-check the live player list just before creation. PENDING
                        // contains our own request, so don't use nameInUse() here.
                        if (findOnlinePlayer(server, username) != null
                                || EntityPlayerMPFake.isSpawningPlayer(username)) {
                            PENDING.remove(key);
                            ServerPlayer requestOwner = findOnlinePlayer(server, pending.ownerUuid());
                            if (requestOwner != null) {
                                requestOwner.sendSystemMessage(Component.literal(
                                        "That player name became unavailable before the bot could spawn."
                                ));
                            }
                            return;
                        }

                        ServerPlayer requestOwner = findOnlinePlayer(server, pending.ownerUuid());
                        if (requestOwner == null) {
                            PENDING.remove(key);
                            return;
                        }

                        int currentLimit = getBotLimit(requestOwner);
                        if (!Permissions.check(requestOwner, PERMISSION_USE, false)
                                || (pending.staffCustomName() && !Permissions.check(requestOwner, PERMISSION_STAFF, false))
                                || currentLimit == 0
                                || (currentLimit > 0 && countOwnedBots(server, requestOwner.getUUID()) > currentLimit)
                                || (!pending.staffCustomName() && isWhitelisted(server, username))) {
                            PENDING.remove(key);
                            requestOwner.sendSystemMessage(Component.literal(
                                    "Bot spawn cancelled: permissions, limit, or whitelist changed."));
                            return;
                        }

                        boolean accepted;
                        try {
                            accepted = EntityPlayerMPFake.createFake(
                                username,
                                server,
                                requestOwner.position(),
                                requestOwner.getYRot(),
                                requestOwner.getXRot(),
                                requestOwner.level().dimension(),
                                gameType,
                                false
                            );
                        } catch (RuntimeException exception) {
                            PENDING.remove(key);
                            requestOwner.sendSystemMessage(Component.literal("Carpet failed to spawn the bot; see the server log."));
                            Botspawn.LOGGER.error("Carpet spawn failed for {}", username, exception);
                            return;
                        } finally {
                            server.services().nameToIdCache().resolveOfflineUsers(
                                    server.isDedicatedServer() && server.usesAuthentication());
                        }

                        if (!accepted) {
                            PENDING.remove(key);
                            if (requestOwner != null) {
                                requestOwner.sendSystemMessage(Component.literal(
                                        "Carpet could not start spawning that bot. Check allowSpawningOfflinePlayers."
                                ));
                            }
                            return;
                        }

                        // Give Carpet a fresh timeout window now that its asynchronous
                        // profile/fake-player creation has actually started.
                        PENDING.put(key, new PendingSpawn(
                                pending.ownerUuid(),
                                pending.ownerName(),
                                pending.botUuid(),
                                pending.username(),
                                pending.gameType(),
                                serverTicks + SPAWN_TIMEOUT_TICKS,
                                pending.staffCustomName(),
                                true
                        ));
                    })
            );
        } catch (RuntimeException exception) {
            PENDING.remove(key, request);
            Botspawn.LOGGER.error("Could not start loading LuckPerms data for {}", username, exception);
            source.sendFailure(Component.literal("LuckPerms could not prepare the bot; spawn cancelled."));
            return 0;
        }

        return 1;
    }

    public static void tick(MinecraftServer server) {
        serverTicks++;

        recoverOwners(server);

        // Drop ownership records for bots which are no longer connected.
        BOTS.prune(bot -> findOnlinePlayer(server, bot.getUUID()) == bot);
        for (String name : new ArrayList<>(ACTIVE_OWNER_NAMES.keySet())) {
            ServerPlayer found = findOnlinePlayer(server, name);
            if (!(found instanceof EntityPlayerMPFake) || BOTS.ownerOf(name, found) == null) {
                BotOwnerStore.Owner recorded = STORE == null ? null : STORE.get(name);
                if (recorded != null) forgetOwner(server, name, recorded.botUuid());
                ACTIVE_OWNER_NAMES.remove(name);
            }
        }

        Iterator<Map.Entry<String, PendingSpawn>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, PendingSpawn> entry = iterator.next();
            PendingSpawn pending = entry.getValue();

            ServerPlayer found = findOnlinePlayer(server, pending.username());
            if (pending.carpetStarted() && found instanceof EntityPlayerMPFake) {
                if (!found.getUUID().equals(pending.botUuid())) {
                    // Keep the slot reserved while the unexpected fake remains.
                    if (REJECTED_PROFILES.add(entry.getKey())) {
                        Botspawn.LOGGER.error("Refusing ownership of {}: spawned UUID differs from the prepared profile", pending.username());
                        ServerPlayer owner = findOnlinePlayer(server, pending.ownerUuid());
                        if (owner != null) owner.sendSystemMessage(Component.literal(
                                "Bot profile changed during spawn; its slot remains reserved. Ask staff to check " + pending.username() + "."));
                    }
                    continue;
                }
                REJECTED_PROFILES.remove(entry.getKey());
                BOTS.register(entry.getKey(), pending.ownerUuid(), found);
                ServerPlayer owner = findOnlinePlayer(server, pending.ownerUuid());
                String ownerName = owner == null ? pending.ownerName() : owner.getName().getString();
                ACTIVE_OWNER_NAMES.put(entry.getKey(), ownerName);
                RESTORE_KEYS.remove(entry.getKey());
                if (STORE != null) {
                    try {
                        STORE.record(new BotOwnerStore.Owner(found.getName().getString(), found.getUUID(),
                                pending.ownerUuid(), ownerName));
                    } catch (IOException exception) {
                        Botspawn.LOGGER.error("Failed to save ownership for {}", pending.username(), exception);
                        if (owner != null) owner.sendSystemMessage(Component.literal(
                                "Bot spawned, but its ownership could not be saved. Ask staff to check the server log."));
                    }
                }
                iterator.remove();
                finishSpawn(server, found, pending);
                continue;
            }

            if (REJECTED_PROFILES.remove(entry.getKey())) {
                iterator.remove();
                continue;
            }

            // Carpet's profile request cannot be cancelled through its public
            // API. Keep its slot reserved until it completes, even after our
            // normal deadline, so a late bot cannot bypass the owner's limit.
            if (pending.carpetStarted() && EntityPlayerMPFake.isSpawningPlayer(pending.username())) {
                if (serverTicks == pending.expiresAtTick()) {
                    ServerPlayer owner = findOnlinePlayer(server, pending.ownerUuid());
                    if (owner != null) owner.sendSystemMessage(Component.literal(
                            "Carpet is still loading " + pending.username() + "; its bot slot remains reserved."));
                }
                continue;
            }
            if (serverTicks >= pending.expiresAtTick()) {
                ServerPlayer owner = findOnlinePlayer(server, pending.ownerUuid());
                if (owner != null) {
                    owner.sendSystemMessage(Component.literal(
                            "Bot " + pending.username() + " did not finish spawning in time."
                    ));
                }
                iterator.remove();
            }
        }
    }

    private static void recoverOwners(MinecraftServer server) {
        if (STORE == null || RESTORE_KEYS.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!(player instanceof EntityPlayerMPFake)) continue;
            String name = key(player.getName().getString());
            if (!RESTORE_KEYS.contains(name) || PENDING.containsKey(name)) continue;
            BotOwnerStore.Owner saved = STORE.get(name);
            if (saved != null && player.getUUID().equals(saved.botUuid())) {
                BOTS.register(name, saved.ownerUuid(), player);
                ACTIVE_OWNER_NAMES.put(name, saved.ownerName());
                RESTORE_KEYS.remove(name);
                Botspawn.LOGGER.info("Restored ownership of {} to {}", saved.name(), saved.ownerName());
            }
        }
    }

    private static void forgetOwner(MinecraftServer server, String name, UUID botUuid) {
        String nameKey = key(name);
        RESTORE_KEYS.remove(nameKey);
        ACTIVE_OWNER_NAMES.remove(nameKey);
        if (STORE != null) {
            try {
                STORE.remove(name, botUuid);
            } catch (IOException exception) {
                Botspawn.LOGGER.error("Could not save removal of bot {} from the ownership file", name, exception);
            }
        }
    }

    private static void finishSpawn(MinecraftServer server, ServerPlayer bot, PendingSpawn pending) {
        boolean whitelistedProfile = isWhitelisted(server, pending.username());

        // Do not modify LuckPerms data for a whitelisted real-player identity.
        // Staff are allowed to spawn such a name, but permanently attaching the
        // "bot" group to that real account would be an unsafe side effect.
        boolean offlineIdentity = bot.getUUID().equals(
                UUIDUtil.createOfflinePlayerUUID(bot.getName().getString()));
        if (!whitelistedProfile && offlineIdentity) {
            addLuckPermsBotGroup(server, bot, pending.ownerUuid());
        }

        ServerPlayer owner = findOnlinePlayer(server, pending.ownerUuid());
        if (owner != null) {
            int limit = getBotLimit(owner);
            int used = countOwnedBots(server, owner.getUUID());
            String limitText = limit < 0 ? "unlimited" : Integer.toString(limit);

            String suffix = whitelistedProfile || !offlineIdentity
                    ? " (real or whitelisted identity: LuckPerms bot group was not changed)"
                    : "";

            owner.sendSystemMessage(Component.literal(
                    "Spawned " + pending.username() + " in " + gameTypeName(pending.gameType())
                            + " mode. Bots: " + used + "/" + limitText + suffix
            ));
        }
    }

    private static void addLuckPermsBotGroup(MinecraftServer server, ServerPlayer bot, UUID ownerUuid) {
        try {
            LuckPerms luckPerms = LuckPermsProvider.get();
            UUID botUuid = bot.getUUID();
            String botName = bot.getName().getString();

            // A null cached group does not prove the group is absent in storage.
            luckPerms.getGroupManager().loadGroup(BOT_GROUP).thenCompose(group -> {
                if (group.isEmpty()) {
                    return CompletableFuture.<Void>failedFuture(new IllegalStateException(
                            "LuckPerms group 'bot' does not exist; create it with /lp creategroup bot"));
                }
                return luckPerms.getUserManager().modifyUser(botUuid, user -> {
                    boolean alreadyMember = user.getNodes(NodeType.INHERITANCE).stream()
                            .anyMatch(node -> node.getValue() && node.getGroupName().equalsIgnoreCase(BOT_GROUP)
                                    && !node.hasExpiry() && node.getContexts().isEmpty());
                    if (!alreadyMember) {
                        user.data().add(InheritanceNode.builder(BOT_GROUP).build());
                    }
                });
            }).exceptionally(throwable -> {
                Botspawn.LOGGER.error("Failed to add {} to LuckPerms group '{}'", botName, BOT_GROUP, throwable);
                server.execute(() -> {
                    ServerPlayer owner = findOnlinePlayer(server, ownerUuid);
                    if (owner != null) owner.sendSystemMessage(Component.literal(
                            "Bot " + botName + " spawned, but its LuckPerms bot group could not be saved. Ask staff to check the server log."));
                });
                return null;
            });
        } catch (IllegalStateException e) {
            Botspawn.LOGGER.error("LuckPerms API is not available; bot group '{}' was not assigned", BOT_GROUP, e);
        }
    }

    private static int getBotLimit(ServerPlayer player) {
        // Highest granted tier wins. These are real Fabric permission checks,
        // so LuckPerms /lp verbose will see them.
        if (Permissions.check(player, PERMISSION_LIMIT_UNLIMITED, false)) return -1;
        if (Permissions.check(player, PERMISSION_LIMIT_10, false)) return 10;
        if (Permissions.check(player, PERMISSION_LIMIT_5, false)) return 5;
        if (Permissions.check(player, PERMISSION_LIMIT_3, false)) return 3;
        if (Permissions.check(player, PERMISSION_LIMIT_2, false)) return 2;
        if (Permissions.check(player, PERMISSION_LIMIT_1, false)) return 1;
        return 0;
    }

    private static String findAutomaticName(ServerPlayer owner) {
        MinecraftServer server = java.util.Objects.requireNonNull(owner.level().getServer());
        String ownerName = owner.getName().getString();

        int scanMax = UNLIMITED_SLOT_SCAN_MAX;
        for (int slot = 1; slot <= scanMax; slot++) {
            String candidate = ownerName + "Bot" + slot;

            // Minecraft usernames cannot exceed 16 characters. Once the number
            // grows, later candidates only get longer, so we can stop scanning.
            if (candidate.length() > 16) {
                return null;
            }

            if (!VALID_NAME.matcher(candidate).matches()) {
                continue;
            }

            // Normal donators can never spawn a name belonging to a whitelisted
            // player, even when that player is offline.
            if (isWhitelisted(server, candidate)) {
                continue;
            }

            if (nameInUse(server, candidate)) {
                continue;
            }

            return candidate;
        }

        return null;
    }

    private static int countOwnedBots(MinecraftServer server, UUID ownerUuid) {
        int count = BOTS.count(ownerUuid, bot -> findOnlinePlayer(server, bot.getUUID()) == bot);

        // Pending spawns count immediately, preventing a player from racing
        // several commands before Carpet finishes creating the fake players.
        for (PendingSpawn pending : PENDING.values()) {
            if (pending.ownerUuid().equals(ownerUuid)) {
                count++;
            }
        }

        return count;
    }

    private static boolean nameInUse(MinecraftServer server, String username) {
        String wanted = key(username);

        if (PENDING.containsKey(wanted) || EntityPlayerMPFake.isSpawningPlayer(username)) {
            return true;
        }

        return findOnlinePlayer(server, username) != null;
    }

    private static ServerPlayer findOnlinePlayer(MinecraftServer server, UUID uuid) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getUUID().equals(uuid)) {
                return player;
            }
        }
        return null;
    }

    private static ServerPlayer findOnlinePlayer(MinecraftServer server, String username) {
        String wanted = key(username);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (key(player.getName().getString()).equals(wanted)) {
                return player;
            }
        }
        return null;
    }

    private static boolean isWhitelisted(MinecraftServer server, String username) {
        String wanted = key(username);
        for (String whitelistedName : server.getPlayerList().getWhiteListNames()) {
            if (key(whitelistedName).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    private static String gameTypeName(GameType gameType) {
        return switch (gameType) {
            case SURVIVAL -> "survival";
            case CREATIVE -> "creative";
            case ADVENTURE -> "adventure";
            case SPECTATOR -> "spectator";
            default -> gameType.toString().toLowerCase(Locale.ROOT);
        };
    }

    private static String key(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    private record PendingSpawn(
            UUID ownerUuid,
            String ownerName,
            UUID botUuid,
            String username,
            GameType gameType,
            long expiresAtTick,
            boolean staffCustomName,
            boolean carpetStarted
    ) {
    }
}
