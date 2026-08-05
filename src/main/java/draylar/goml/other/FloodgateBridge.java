package draylar.goml.other;

import draylar.goml.GetOffMyLawn;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

/**
 * Detects Bedrock players connected through Geyser/Floodgate, so the parts of the mod that rely on
 * Java-only client behaviour can fall back to something a Bedrock client understands.
 *
 * <p>Floodgate is an optional dependency and is reached reflectively, so this keeps working when it
 * isn't installed. If the API can't be reached the UUID layout is used instead: Floodgate packs the
 * Xbox user id into the low bits and leaves the high bits zeroed, which no Mojang-issued (version 4)
 * or offline-mode (version 3) UUID does.
 */
public class FloodgateBridge {
    public static final boolean IS_LOADED = FabricLoader.getInstance().isModLoaded("floodgate");

    /** Floodgate's own default, used only when it's installed but its config can't be read. */
    private static final String DEFAULT_PREFIX = ".";

    // Read from both the server thread and the async name lookup in NamePlayerSelectorGui.
    private static volatile boolean resolved = false;
    private static volatile Object apiInstance = null;
    private static volatile Method isFloodgatePlayer = null;
    private static volatile Method getPlayerPrefix = null;

    /**
     * @param uuid the player's UUID, or null
     * @return whether the player is connected through Floodgate
     */
    public static boolean isBedrockPlayer(UUID uuid) {
        if (uuid == null) {
            return false;
        }

        if (IS_LOADED) {
            resolveApi();

            if (apiInstance != null && isFloodgatePlayer != null) {
                try {
                    return (Boolean) isFloodgatePlayer.invoke(apiInstance, uuid);
                } catch (Throwable exception) {
                    // Fall through to the UUID layout check below
                }
            }
        }

        return hasFloodgateUuidLayout(uuid);
    }

    /**
     * Resolved lazily and only once, as Floodgate's API instance isn't available until it has
     * finished its own setup, which may happen after this class is first loaded.
     */
    private static void resolveApi() {
        if (resolved) {
            return;
        }

        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object instance = apiClass.getMethod("getInstance").invoke(null);

            // Floodgate is loaded but hasn't finished starting, so leave this unresolved and retry later.
            if (instance == null) {
                return;
            }

            isFloodgatePlayer = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            getPlayerPrefix = apiClass.getMethod("getPlayerPrefix");
            apiInstance = instance;
            resolved = true;
        } catch (Throwable exception) {
            // The API is missing or has changed shape, so stop retrying and use the fallbacks.
            GetOffMyLawn.LOGGER.info("Floodgate is present but its API couldn't be reached, falling back to UUID detection: {}", exception.toString());
            apiInstance = null;
            isFloodgatePlayer = null;
            getPlayerPrefix = null;
            resolved = true;
        }
    }

    private static boolean hasFloodgateUuidLayout(UUID uuid) {
        return uuid.getMostSignificantBits() == 0 && uuid.getLeastSignificantBits() != 0;
    }

    /**
     * @return the prefix Floodgate puts in front of Bedrock usernames, or an empty string when
     *         Floodgate isn't installed and no Bedrock players can be present
     */
    public static String getPlayerPrefix() {
        if (!IS_LOADED) {
            return "";
        }

        resolveApi();

        if (apiInstance != null && getPlayerPrefix != null) {
            try {
                String prefix = (String) getPlayerPrefix.invoke(apiInstance);

                if (prefix != null) {
                    return prefix;
                }
            } catch (Throwable exception) {
                // Fall through to the default below
            }
        }

        return DEFAULT_PREFIX;
    }

    /**
     * Looks a player up by name, retrying with the Floodgate prefix if the plain name misses.
     *
     * <p>Bedrock players are stored under a prefixed name, so a Java player trying to trust one
     * has to know to type {@code .Name} rather than the {@code Name} they see in chat.
     *
     * @param server the server whose name cache is searched
     * @param name the name as typed by the player
     * @return the matching profile, or empty if neither form matched
     */
    public static Optional<NameAndId> resolveName(MinecraftServer server, String name) {
        var cache = server.services().nameToIdCache();
        var profile = cache.get(name);

        if (profile.isPresent()) {
            return profile;
        }

        String prefix = getPlayerPrefix();

        if (!prefix.isEmpty() && !name.startsWith(prefix)) {
            return cache.get(prefix + name);
        }

        return profile;
    }
}
