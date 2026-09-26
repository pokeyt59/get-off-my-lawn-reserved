package draylar.goml.block.augment;

import draylar.goml.api.Claim;
import draylar.goml.block.ClaimAugmentBlock;
import draylar.goml.block.SelectiveClaimAugmentBlock;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;

public class LakeSpiritGraceAugmentBlock extends SelectiveClaimAugmentBlock {

    public LakeSpiritGraceAugmentBlock(Properties settings, String texture) {
        super("lake_spirit", settings, texture);
    }

    @Override
    public void playerTick(Claim claim, Player player) {
        if (this.canApply(claim, player)) {
            AugmentEffects.keep(player, MobEffects.WATER_BREATHING, false);
            AugmentEffects.keep(player, MobEffects.DOLPHINS_GRACE, false);
        }
    }

    @Override
    public void removeEffect(Player player) {
        AugmentEffects.remove(player, MobEffects.WATER_BREATHING);
        AugmentEffects.remove(player, MobEffects.DOLPHINS_GRACE);
    }

    @Override
    public boolean ticks() {
        return true;
    }
}
