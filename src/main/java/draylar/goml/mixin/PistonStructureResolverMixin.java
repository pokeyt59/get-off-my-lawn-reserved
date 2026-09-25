package draylar.goml.mixin;

import com.jamieswhiteshirt.rtree3i.Box;
import com.jamieswhiteshirt.rtree3i.Entry;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import draylar.goml.api.Claim;
import draylar.goml.api.ClaimBox;
import draylar.goml.api.ClaimUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;

@Mixin(PistonStructureResolver.class)
public class PistonStructureResolverMixin {
    @Shadow @Final private List<BlockPos> toPush;
    @Shadow @Final private List<BlockPos> toDestroy;
    @Shadow @Final private Level level;
    @Shadow @Final private Direction pushDirection;
    @Unique
    private boolean claimsEmpty;
    @Unique
    private HashSet<UUID> trusted;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void storeClaimInfo(Level world, BlockPos pos, Direction dir, boolean retracted, CallbackInfo ci) {
        if (world.isClientSide()) {
            return;
        }
        var claimsEmpty = new MutableBoolean(true);
        this.trusted = new HashSet<>();
        ClaimUtils.getClaimsAt(world, pos).forEach(x -> {
            claimsEmpty.setFalse();
            this.trusted.addAll(x.getValue().getOwners());
            this.trusted.addAll(x.getValue().getTrusted());
        });
        this.claimsEmpty = claimsEmpty.booleanValue();
    }

    @ModifyReturnValue(method = "resolve", at = @At("RETURN"))
    private boolean preventMovement(boolean value) {
        if (level.isClientSide()) {
            return value;
        }
        if (value) {
            if (!goml$canMoveBlocks()) {
                this.toPush.clear();
                this.toDestroy.clear();
                return false;
            }
            return true;
        }

        return false;
    }

    /**
     * Checks both the current and target position of every moved block.
     * Claims are queried once for the area of the whole structure, instead of twice per block.
     */
    @Unique
    private boolean goml$canMoveBlocks() {
        if (this.toPush.isEmpty() && this.toDestroy.isEmpty()) {
            return true;
        }

        var stepX = this.pushDirection.getStepX();
        var stepY = this.pushDirection.getStepY();
        var stepZ = this.pushDirection.getStepZ();
        var lists = List.of(this.toPush, this.toDestroy);

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var list : lists) {
            for (var pos : list) {
                minX = Math.min(minX, Math.min(pos.getX(), pos.getX() + stepX));
                minY = Math.min(minY, Math.min(pos.getY(), pos.getY() + stepY));
                minZ = Math.min(minZ, Math.min(pos.getZ(), pos.getZ() + stepZ));
                maxX = Math.max(maxX, Math.max(pos.getX(), pos.getX() + stepX));
                maxY = Math.max(maxY, Math.max(pos.getY(), pos.getY() + stepY));
                maxZ = Math.max(maxZ, Math.max(pos.getZ(), pos.getZ() + stepZ));
            }
        }

        var candidates = ClaimUtils.getClaimsInBox(this.level, Box.create(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1)).collect(Collectors.toList());
        if (candidates.isEmpty()) {
            // Everything is unclaimed, which is only allowed for pistons outside of claims
            return this.claimsEmpty;
        }

        var allowed = new boolean[candidates.size()];
        for (int i = 0; i < allowed.length; i++) {
            allowed[i] = candidates.get(i).getValue().hasPermission(this.trusted);
        }

        for (var list : lists) {
            for (var pos : list) {
                if (!goml$canMoveAt(pos.getX(), pos.getY(), pos.getZ(), candidates, allowed)
                        || !goml$canMoveAt(pos.getX() + stepX, pos.getY() + stepY, pos.getZ() + stepZ, candidates, allowed)) {
                    return false;
                }
            }
        }

        return true;
    }

    @Unique
    private boolean goml$canMoveAt(int x, int y, int z, List<Entry<ClaimBox, Claim>> candidates, boolean[] allowed) {
        // Same check as ClaimUtils.getClaimsAt, limited to claims touching the structure
        var checkBox = Box.create(x, y, z, x + 1, y + 1, z + 1);
        boolean claimed = false;

        for (int i = 0; i < allowed.length; i++) {
            if (candidates.get(i).getKey().toBox().contains(checkBox)) {
                if (allowed[i]) {
                    return true;
                }
                claimed = true;
            }
        }

        // Unclaimed positions are only allowed for pistons outside of claims
        return !claimed && this.claimsEmpty;
    }
}
