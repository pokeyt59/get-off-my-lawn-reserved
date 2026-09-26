package draylar.goml.block.augment;

import draylar.goml.api.Claim;
import draylar.goml.block.ClaimAugmentBlock;
import draylar.goml.block.SelectiveClaimAugmentBlock;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;

public class ChaosZoneAugmentBlock extends SelectiveClaimAugmentBlock {

    public ChaosZoneAugmentBlock(Properties settings, String texture) {
        super("chaos_zone", settings, texture);
    }

    @Override
    public boolean ticks() {
        return true;
    }

    @Override
    public void playerTick(Claim claim, Player player) {
        if (canApply(claim, player)) {
            AugmentEffects.keep(player, MobEffects.STRENGTH, false);
        }
    }

    @Override
    public void removeEffect(Player player) {
        AugmentEffects.remove(player, MobEffects.STRENGTH);
    }
}
