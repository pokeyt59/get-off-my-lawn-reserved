package draylar.goml.compat;

import draylar.goml.GetOffMyLawn;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.CachedUserNameToIdResolver;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserNameToIdResolver;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.util.Locale;
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
     * Looks a player up by name like vanilla does, but also finds Bedrock players by the name they chose, without
     * Floodgate's prefix. For a name that isn't a known Bedrock player's, vanilla's lookup can ask Mojang, so don't
     * call this on the server thread.
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

            // Bedrock players who joined are in the cache. Only the cache is searched: Mojang can't know them, and
            // vanilla's lookup would ask it about every name that isn't a Bedrock player's. A cached name without a
            // Floodgate UUID is someone else's, like an offline player made up for a name typed in a command.
            var bedrock = cached(names, floodgateName).filter(found -> isFloodgateId(found.id()));

            if (bedrock.isPresent()) {
                // Someone who played here under exactly this name is still found by it
                var exact = cached(names, name);
                return exact.isPresent() && hasPlayedHere(server, exact.get().id()) ? exact : bedrock;
            }
        }

        return names.get(name);
    }

    /**
     * Looks the name up in the server's name cache only, without asking Mojang (or making up an offline player) like
     * {@link UserNameToIdResolver#get(String)} does for names it doesn't know.
     */
    private static Optional<NameAndId> cached(UserNameToIdResolver names, String name) {
        if (names instanceof CachedUserNameToIdResolver cache) {
            var entry = cache.profilesByName.get(name.toLowerCase(Locale.ROOT));
            return entry != null ? Optional.of(entry.nameAndId()) : Optional.empty();
        }
        // Another resolver, like the mock of vanilla's game test server, which never asks Mojang: the usual lookup
        return names.get(name);
    }

    // Floodgate makes Bedrock players' UUIDs from their Xbox id, so the first half is zero (like its isFloodgateId).
    // Logins without Xbox authentication get Xbox id 0, the all-zero UUID.
    private static boolean isFloodgateId(UUID uuid) {
        return uuid.getMostSignificantBits() == 0;
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
