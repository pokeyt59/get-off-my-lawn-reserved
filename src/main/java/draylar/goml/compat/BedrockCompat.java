package draylar.goml.compat;

import draylar.goml.GetOffMyLawn;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.util.Optional;
import java.util.UUID;

/**
 * Detects Bedrock players connected through Floodgate or Geyser, so they can receive visuals their client can display,
 * and finds them by name. Both APIs are looked up by reflection, so there is no build dependency on either.
 */
public final class BedrockCompat {
    private static final String FLOODGATE = "org.geysermc.floodgate.api.FloodgateApi";
    // The longest name a Java profile can have, Floodgate cuts its prefixed names to fit
    private static final int MAX_NAME_LENGTH = 16;

    @Nullable
    private static MethodHandle isBedrockPlayer;
    @Nullable
    private static MethodHandle playerPrefix;

    private BedrockCompat() {}

    public static void init(MinecraftServer server) {
        isBedrockPlayer = find(FLOODGATE, "getInstance", "isFloodgatePlayer");
        playerPrefix = null;

        if (isBedrockPlayer != null) {
            // Only Floodgate renames Bedrock players
            try {
                playerPrefix = onInstance(Class.forName(FLOODGATE), "getInstance", "getPlayerPrefix", MethodType.methodType(String.class));
            } catch (Throwable e) {
                GetOffMyLawn.LOGGER.warn("Couldn't read Floodgate's name prefix, Bedrock players can only be found by name with it", e);
            }
        } else {
            isBedrockPlayer = find("org.geysermc.geyser.api.GeyserApi", "api", "isBedrockPlayer");
        }
    }

    public static boolean isBedrock(ServerPlayer player) {
        var handle = isBedrockPlayer;
        if (handle == null) {
            return false;
        }

        try {
            return (boolean) handle.invokeExact(player.getUUID());
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * @return the prefix Floodgate puts before Bedrock players' names (".Steve" with its default "."), or an empty
     *         string without Floodgate
     */
    public static String namePrefix() {
        var handle = playerPrefix;
        if (handle == null) {
            return "";
        }

        try {
            var prefix = (String) handle.invokeExact();
            return prefix != null ? prefix : "";
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * Looks a player up in the server's name cache like vanilla does, but also finds Bedrock players by the name they
     * chose, without Floodgate's prefix. May ask Mojang about the name (in online mode), so don't call this on the
     * server thread.
     */
    public static Optional<NameAndId> findByName(MinecraftServer server, String name) {
        return findByName(server, name, namePrefix());
    }

    /**
     * {@link #findByName(MinecraftServer, String)} with the given Floodgate prefix, so tests don't need Floodgate.
     */
    @VisibleForTesting
    public static Optional<NameAndId> findByName(MinecraftServer server, String name, String prefix) {
        var names = server.services().nameToIdCache();

        if (!prefix.isEmpty() && !name.startsWith(prefix)) {
            // Floodgate's name for them: its prefix first, spaces as underscores (its default), cut to fit
            var floodgateName = prefix + name.replace(' ', '_');
            floodgateName = floodgateName.substring(0, Math.min(floodgateName.length(), MAX_NAME_LENGTH));

            // Only a Bedrock player who joined before has a Floodgate UUID. For any other name the cache asks Mojang
            // (online mode) or makes up an offline player.
            var bedrock = names.get(floodgateName).filter(found -> isFloodgateId(found.id()));

            if (bedrock.isPresent()) {
                // Someone who played here under exactly this name is still found by it
                var exact = names.get(name);
                return exact.isPresent() && hasPlayedHere(server, exact.get().id()) ? exact : bedrock;
            }
        }

        return names.get(name);
    }

    // Floodgate makes Bedrock players' UUIDs from their Xbox id, the first half is zero (its isFloodgateId)
    private static boolean isFloodgateId(UUID uuid) {
        return uuid.getMostSignificantBits() == 0 && uuid.getLeastSignificantBits() != 0;
    }

    private static boolean hasPlayedHere(MinecraftServer server, UUID uuid) {
        return server.getPlayerList().getPlayer(uuid) != null
                || Files.exists(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(uuid + ".dat"));
    }

    /**
     * Creates a (UUID)boolean handle calling {@code check} on the api instance.
     */
    @Nullable
    private static MethodHandle find(String className, String instanceGetter, String check) {
        Class<?> clazz;
        try {
            clazz = Class.forName(className);
        } catch (Throwable e) {
            return null;
        }

        try {
            var handle = onInstance(clazz, instanceGetter, check, MethodType.methodType(boolean.class, UUID.class));
            GetOffMyLawn.LOGGER.info("Bedrock player detection enabled using {}", className);
            return handle;
        } catch (Throwable e) {
            GetOffMyLawn.LOGGER.warn("Found {}, but couldn't use it to detect Bedrock players!", className, e);
            return null;
        }
    }

    /**
     * Creates a handle calling {@code method} on the api instance. The instance is requested on every call, as these
     * mods might set it up after this runs.
     */
    private static MethodHandle onInstance(Class<?> clazz, String instanceGetter, String method, MethodType type) throws ReflectiveOperationException {
        var lookup = MethodHandles.publicLookup();
        var getter = lookup.findStatic(clazz, instanceGetter, MethodType.methodType(clazz));
        return MethodHandles.foldArguments(lookup.findVirtual(clazz, method, type), getter);
    }
}
