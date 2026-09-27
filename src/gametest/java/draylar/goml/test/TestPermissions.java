package draylar.goml.test;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.permission.v1.PermissionContext;
import net.fabricmc.fabric.api.permission.v1.PermissionEvents;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gives chosen test players every GOML permission (admin mode, admin commands), like a permissions mod would.
 * Nobody else is affected, so it's harmless on the compat and Bedrock test servers too.
 */
public final class TestPermissions {
    private static final Set<UUID> ADMINS = ConcurrentHashMap.newKeySet();

    private TestPermissions() {
    }

    static void init() {
        PermissionEvents.ON_REQUEST.register(new PermissionEvents.OnRequest() {
            @Override
            @Nullable
            public <T> T handlePermissionRequest(PermissionContext context, PermissionNode<T> permission) {
                if (ADMINS.isEmpty() || permission.codec() != Codec.BOOL || !permission.key().getNamespace().equals("goml")) {
                    return null;
                }

                var entity = context.get(PermissionContext.ENTITY);
                if (ADMINS.contains(context.uuid()) || (entity != null && ADMINS.contains(entity.getUUID()))) {
                    return permission.cast(true);
                }
                return null;
            }
        });
    }

    public static void grant(UUID player) {
        ADMINS.add(player);
    }

    public static void revoke(UUID player) {
        ADMINS.remove(player);
    }
}
