package draylar.goml.block.augment;

import draylar.goml.GetOffMyLawn;
import draylar.goml.api.Claim;
import draylar.goml.api.ClaimUtils;
import draylar.goml.block.SelectiveClaimAugmentBlock;
import io.github.ladysnake.pal.AbilitySource;
import io.github.ladysnake.pal.Pal;
import io.github.ladysnake.pal.VanillaAbilities;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public class HeavenWingsAugmentBlock extends SelectiveClaimAugmentBlock {

    public static final AbilitySource HEAVEN_WINGS = Pal.getAbilitySource("goml", "heaven_wings");

    // Players who lost their wings mid-flight, they keep slow falling until they land
    private static final Set<ServerPlayer> FALLING = Collections.newSetFromMap(new WeakHashMap<>());

    public HeavenWingsAugmentBlock(Properties settings, String texture) {
        super("heaven_wings", settings, texture);
        ServerTickEvents.END_SERVER_TICK.register(server -> tickFalling());
        ServerPlayConnectionEvents.JOIN.register((handler, packetSender, minecraftServer) -> {
            GetOffMyLawn.NEXT_TICK_TASK.add(() -> {
                if (!handler.isAcceptingMessages()) {
                    return;
                }

                var canFly = ClaimUtils.getClaimsAt(handler.player.level(), handler.player.blockPosition())
                        .filter(x -> x.getValue().hasAugment(this) && this.canApply(x.getValue(), handler.player)).isNotEmpty();

                if (canFly) {
                    return;
                }

                this.removeEffect(handler.player);
            });
        });
    }

    @Override
    public void applyEffect(Player player) {
        HEAVEN_WINGS.grantTo(player, VanillaAbilities.ALLOW_FLYING);
    }

    @Override
    public void removeEffect(Player player) {
        var wasFlying = player.getAbilities().flying;
        HEAVEN_WINGS.revokeFrom(player, VanillaAbilities.ALLOW_FLYING);

        // Still flying means another source (creative, another mod) keeps them in the air
        if (wasFlying && !player.getAbilities().flying && player instanceof ServerPlayer serverPlayer) {
            FALLING.add(serverPlayer);
            AugmentEffects.keep(serverPlayer, MobEffects.SLOW_FALLING, true);
        }
    }

    private static void tickFalling() {
        if (FALLING.isEmpty()) {
            return;
        }

        var iterator = FALLING.iterator();
        while (iterator.hasNext()) {
            var player = iterator.next();
            if (player.isRemoved() || player.onGround() || player.isInWater() || player.isFallFlying() || player.getAbilities().flying) {
                iterator.remove();
                AugmentEffects.remove(player, MobEffects.SLOW_FALLING);
            } else {
                AugmentEffects.keep(player, MobEffects.SLOW_FALLING, true);
            }
        }
    }

    @Override
    public void onPlayerExit(Claim claim, Player player) {
        var canFly = ClaimUtils.getClaimsAt(player.level(), player.blockPosition())
                .filter(x -> x.getValue() != claim && x.getValue().hasAugment(this) && this.canApply(x.getValue(), player)).isNotEmpty();

        if (!canFly) {
            super.onPlayerExit(claim, player);
        }
    }
}
