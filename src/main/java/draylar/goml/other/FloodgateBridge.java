package draylar.goml.other;

import draylar.goml.GetOffMyLawn;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;
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

    private static boolean resolved = false;
    private static Object apiInstance = null;
    private static Method isFloodgatePlayer = null;

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
            apiInstance = instance;
            resolved = true;
        } catch (Throwable exception) {
            // The API is missing or has changed shape, so stop retrying and use the fallbacks.
            GetOffMyLawn.LOGGER.info("Floodgate is present but its API couldn't be reached, falling back to UUID detection: {}", exception.toString());
            apiInstance = null;
            isFloodgatePlayer = null;
            resolved = true;
        }
    }

    private static boolean hasFloodgateUuidLayout(UUID uuid) {
        return uuid.getMostSignificantBits() == 0 && uuid.getLeastSignificantBits() != 0;
    }
}
