package zeus2.botspawn;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Botspawn implements ModInitializer {
    public static final String MOD_ID = "botspawn";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        String version = FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        LOGGER.info("Botspawn {} loading: /botkill requires avience.botspawn.kill; override is avience.botspawn.killothers", version);
        boolean luckPermsAvailable = FabricLoader.getInstance().isModLoaded("luckperms");
        if (!luckPermsAvailable) {
            LOGGER.error("============================================================");
            LOGGER.error("BOTSPAWN SPAWN/KILL DISABLED: LuckPerms is not installed.");
            LOGGER.error("/botlist remains available, but /botspawn and /botkill will not be registered.");
            LOGGER.error("Install LuckPerms and restart the server to enable spawning and killing bots.");
            LOGGER.error("============================================================");
        }

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            if (luckPermsAvailable) BotspawnCommand.register(dispatcher);
            else BotspawnCommand.registerList(dispatcher);
        });

        ServerLifecycleEvents.SERVER_STARTING.register(BotspawnCommand::start);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> BotspawnCommand.reset());

        ServerTickEvents.END_SERVER_TICK.register(BotspawnCommand::tick);

        LOGGER.info("Botspawn initialised (LuckPerms: {})", luckPermsAvailable ? "available" : "missing");
    }
}
