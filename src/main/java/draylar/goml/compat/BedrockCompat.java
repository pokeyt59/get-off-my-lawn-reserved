package draylar.goml.compat;

import draylar.goml.GetOffMyLawn;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;

/**
 * Detects Bedrock players connected through Floodgate or Geyser, so they can receive visuals their client can display.
 * Both APIs are looked up by reflection, so there is no build dependency on either.
 */
public final class BedrockCompat {
    @Nullable
    private static MethodHandle isBedrockPlayer;

    private BedrockCompat() {}

    public static void init(MinecraftServer server) {
        isBedrockPlayer = find("org.geysermc.floodgate.api.FloodgateApi", "getInstance", "isFloodgatePlayer");

        if (isBedrockPlayer == null) {
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
     * Creates a (UUID)boolean handle calling {@code check} on the api instance.
     * Instance is requested on every call, as these mods might set it up after this runs.
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
            var lookup = MethodHandles.publicLookup();
            var getter = lookup.findStatic(clazz, instanceGetter, MethodType.methodType(clazz));
            var checker = lookup.findVirtual(clazz, check, MethodType.methodType(boolean.class, UUID.class));
            var handle = MethodHandles.foldArguments(checker, getter);
            GetOffMyLawn.LOGGER.info("Bedrock player detection enabled using {}", className);
            return handle;
        } catch (Throwable e) {
            GetOffMyLawn.LOGGER.warn("Found {}, but couldn't use it to detect Bedrock players!", className, e);
            return null;
        }
    }
}
