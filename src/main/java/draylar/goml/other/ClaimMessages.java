package draylar.goml.other;

import draylar.goml.GetOffMyLawn;
import draylar.goml.api.Claim;
import draylar.goml.api.ClaimUtils;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.apache.commons.lang3.mutable.MutableObject;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Shows "Entering/Leaving Steve's claim" in the action bar when claimEnterLeaveMessages is on.
 * Claims are only looked up when a player moves to another block.
 */
@ApiStatus.Internal
public final class ClaimMessages {
    private static final Map<ServerPlayer, State> STATES = new WeakHashMap<>();

    private ClaimMessages() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(ClaimMessages::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> STATES.remove(handler.player));
    }

    private static void tick(MinecraftServer server) {
        if (!GetOffMyLawn.CONFIG.claimEnterLeaveMessages) {
            if (!STATES.isEmpty()) {
                STATES.clear();
            }
            return;
        }

        for (var player : server.getPlayerList().getPlayers()) {
            var world = player.level();
            var pos = player.blockPosition();
            var previous = STATES.get(player);
            if (previous != null && previous.world == world && previous.pos.equals(pos)) {
                continue;
            }

            var claim = claimAt(world, pos);
            STATES.put(player, new State(world, pos, claim));
            if (previous == null) {
                // Just joined or respawned, where they are isn't news
                continue;
            }

            var message = message(server, player, previous.claim != null && !previous.claim.isDestroyed() ? previous.claim : null, claim);
            if (message != null) {
                player.sendOverlayMessage(message);
            }
        }
    }

    /**
     * @return the action bar message for moving from one claim (or none) to another, null if nothing changed
     */
    @Nullable
    static Component message(MinecraftServer server, ServerPlayer player, @Nullable Claim from, @Nullable Claim to) {
        if (from == to) {
            return null;
        }
        // Overlapping claims of the same owners count as one
        if (from != null && to != null && from.getOwners().equals(to.getOwners())) {
            return null;
        }

        return to != null ? text("text.goml.claim_enter", server, player, to) : text("text.goml.claim_leave", server, player, from);
    }

    private static Component text(String key, MinecraftServer server, ServerPlayer player, Claim claim) {
        if (claim.isOwner(player)) {
            return Component.translatable(key + ".own");
        }

        var owner = ClaimUtils.getOwnerName(server, claim);
        return owner != null ? Component.translatable(key, owner) : Component.translatable(key + ".unknown");
    }

    @Nullable
    private static Claim claimAt(ServerLevel world, BlockPos pos) {
        var found = new MutableObject<Claim>();
        ClaimUtils.getClaimsAt(world, pos).forEach(entry -> {
            if (found.getValue() == null && !entry.getValue().isDestroyed()) {
                found.setValue(entry.getValue());
            }
        });
        return found.getValue();
    }

    private record State(ServerLevel world, BlockPos pos, @Nullable Claim claim) {
    }
}
