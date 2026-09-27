package draylar.goml.mixin;

import com.jamieswhiteshirt.rtree3i.Box;
import com.jamieswhiteshirt.rtree3i.Entry;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import draylar.goml.api.Claim;
import draylar.goml.api.ClaimBox;
import draylar.goml.api.ClaimUtils;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ServerExplosion;

@Mixin(value = ServerExplosion.class, priority = 800)
public abstract class ServerExplosionMixin {

    @Shadow @Nullable public abstract LivingEntity getIndirectSourceEntity();

    @Shadow @Final private ServerLevel level;

    /**
     * Filters the list explode() works with (breaking blocks, placing fire), after every mod is done building it.
     * Lithium, for example, adds all the blocks at the end of calculateExplodedPositions, so filtering there can
     * run before the list is filled and let explosions through.
     */
    @ModifyExpressionValue(method = "explode", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/ServerExplosion;calculateExplodedPositions()Ljava/util/List;"))
    private List<BlockPos> goml_clearBlocks(List<BlockPos> positions) {
        if (positions.isEmpty()) {
            return positions;
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var pos : positions) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }

        // Query claims once for the whole explosion, instead of once per block
        var candidates = ClaimUtils.getClaimsInBox(this.level, Box.create(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1)).collect(Collectors.toList());
        if (candidates.isEmpty()) {
            return positions;
        }

        var player = ClaimUtils.getExplosionPlayer(this.getIndirectSourceEntity());
        var claimsAtPos = new ArrayList<Entry<ClaimBox, Claim>>();
        // A new list, as other mods may hand back one that can't be changed
        var allowed = new ArrayList<BlockPos>(positions.size());

        for (var b : positions) {
            // Same check as ClaimUtils.getClaimsAt, limited to claims touching the explosion
            var checkBox = Box.create(b.getX(), b.getY(), b.getZ(), b.getX() + 1, b.getY() + 1, b.getZ() + 1);
            claimsAtPos.clear();
            for (var entry : candidates) {
                if (entry.getKey().toBox().contains(checkBox)) {
                    claimsAtPos.add(entry);
                }
            }

            if (ClaimUtils.canExplosionDestroy(this.level, b, player, claimsAtPos)) {
                allowed.add(b);
            }
        }

        return allowed;
    }

    @ModifyVariable(method = "hurtEntities", at = @At("STORE"), ordinal = 0)
    private List<Entity> goml_clearEntities(List<Entity> x) {
        var source = this.getIndirectSourceEntity();
        x.removeIf((e) -> !ClaimUtils.canExplosionDestroy(this.level, e.blockPosition(), source));
        return x;
    }
}
