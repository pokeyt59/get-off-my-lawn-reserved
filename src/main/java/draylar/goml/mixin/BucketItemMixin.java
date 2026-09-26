package draylar.goml.mixin;

import com.jamieswhiteshirt.rtree3i.Entry;
import com.jamieswhiteshirt.rtree3i.Selection;
import draylar.goml.api.ClaimBox;
import draylar.goml.api.Claim;
import draylar.goml.api.ClaimUtils;
import draylar.goml.api.PermissionReason;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * UseBlockCallback doesn't seem to cover buckets well.
 * This mixin serves as an extra protection layer against client desync when using buckets in claims you don't own.
 */
@Mixin(BucketItem.class)
public class BucketItemMixin extends Item {

    @Shadow @Final private Fluid content;

    public BucketItemMixin(Properties settings) {
        super(settings);
    }

    @Inject(at = @At("HEAD"), method = "use", cancellable = true)
    private void goml_preventBucketUsageInClaims(Level world, Player user, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (world.isClientSide()) {
            return;
        }

        HitResult hitResult = getPlayerPOVHitResult(world, user, this.content == Fluids.EMPTY ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE);

        var pos = ((BlockHitResult) hitResult).getBlockPos();
        if(!ClaimUtils.canModify(world, pos, user)) {
            // Named after the owner of the claim that's in the way
            var owner = new MutableObject<String>();
            if (world instanceof ServerLevel serverWorld) {
                ClaimUtils.getClaimsAt(world, pos).forEach(entry -> {
                    if (owner.getValue() == null && !entry.getValue().hasPermission(user)) {
                        owner.setValue(ClaimUtils.getOwnerName(serverWorld.getServer(), entry.getValue()));
                    }
                });
            }

            user.sendOverlayMessage(PermissionReason.BLOCK_PROTECTED.getReason(owner.getValue()));
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}