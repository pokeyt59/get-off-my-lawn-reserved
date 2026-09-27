package draylar.goml.test;

import draylar.goml.api.Claim;
import draylar.goml.api.ClaimUtils;
import draylar.goml.block.augment.ExplosionControllerAugmentBlock;
import draylar.goml.other.StatusEnum;
import draylar.goml.registry.GOMLBlocks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static draylar.goml.test.GomlTestUtil.*;

/**
 * Things that change blocks without a player: fluids, pistons, dispensers, falling blocks and explosions.
 * These run over several ticks, so they never touch the global config.
 */
public class WorldTests {
    private static void finish(GameTestHelper helper, long tick, List<Claim> claims, Runnable checks) {
        helper.runAtTickTime(tick, () -> {
            try {
                checks.run();
                helper.succeed();
            } finally {
                remove(helper, claims.toArray(Claim[]::new));
            }
        });
    }

    private static boolean isWater(GameTestHelper helper, BlockPos relative) {
        return blockAt(helper, relative) == Blocks.WATER;
    }

    /**
     * Claim A (x 1..3, z 3..5) and claim B (x 4..6, z 3..5) of different owners side by side, water in A next to B.
     */
    @GameTest(maxTicks = 60)
    public void waterDoesNotFlowIntoANeighbouringClaim(GameTestHelper helper) {
        floor(helper);
        var alice = player(helper, "FlAlice");
        var bob = player(helper, "FlBob");
        var claims = List.of(
                claim(helper, new BlockPos(2, 1, 4), 1, alice.getUUID()),
                claim(helper, new BlockPos(5, 1, 4), 1, bob.getUUID())
        );
        set(helper, new BlockPos(3, 1, 4), Blocks.WATER);

        finish(helper, 40, claims, () -> {
            check(helper, isWater(helper, new BlockPos(3, 1, 5)), "Water didn't spread inside its own claim");
            check(helper, isWater(helper, new BlockPos(3, 1, 2)), "Water didn't spread out of the claim into the wilderness");
            for (int z = 3; z <= 5; z++) {
                check(helper, !isWater(helper, new BlockPos(4, 1, z)), "Water flowed into another player's claim at z " + z);
            }
        });
    }

    @GameTest(maxTicks = 60)
    public void waterDoesNotFlowFromTheWildernessIntoAClaim(GameTestHelper helper) {
        floor(helper);
        var owner = player(helper, "FwOwner");
        var claims = List.of(claim(helper, new BlockPos(5, 1, 4), 1, owner.getUUID()));
        set(helper, new BlockPos(2, 1, 4), Blocks.WATER);

        finish(helper, 40, claims, () -> {
            check(helper, isWater(helper, new BlockPos(3, 1, 4)), "Water didn't spread in the wilderness");
            for (int z = 3; z <= 5; z++) {
                check(helper, !isWater(helper, new BlockPos(4, 1, z)), "Water flowed from the wilderness into a claim at z " + z);
            }
        });
    }

    private static void piston(GameTestHelper helper, BlockPos relative, Direction facing) {
        helper.getLevel().setBlockAndUpdate(helper.absolutePos(relative), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, facing));
    }

    /**
     * Claim covers x 5..7, z 3..5. Row z = 4 pushes from the wilderness into it, row z = 1 stays in the wilderness,
     * row z = 5 pushes inside the claim.
     */
    @GameTest(maxTicks = 40)
    public void pistonsCantPushIntoAClaimFromOutside(GameTestHelper helper) {
        floor(helper);
        var owner = player(helper, "PsOwner");
        var claims = List.of(claim(helper, new BlockPos(6, 1, 4), 1, owner.getUUID()));

        // Into the claim
        piston(helper, new BlockPos(2, 1, 4), Direction.EAST);
        set(helper, new BlockPos(3, 1, 4), Blocks.DIRT);
        set(helper, new BlockPos(4, 1, 4), Blocks.DIRT);
        // Wilderness only
        piston(helper, new BlockPos(1, 1, 1), Direction.EAST);
        set(helper, new BlockPos(2, 1, 1), Blocks.DIRT);
        set(helper, new BlockPos(3, 1, 1), Blocks.DIRT);
        // Inside the claim
        piston(helper, new BlockPos(5, 1, 5), Direction.EAST);
        set(helper, new BlockPos(6, 1, 5), Blocks.DIRT);

        set(helper, new BlockPos(1, 1, 4), Blocks.REDSTONE_BLOCK);
        set(helper, new BlockPos(0, 1, 1), Blocks.REDSTONE_BLOCK);
        set(helper, new BlockPos(5, 2, 5), Blocks.REDSTONE_BLOCK);

        finish(helper, 20, claims, () -> {
            check(helper, blockAt(helper, new BlockPos(4, 1, 1)) == Blocks.DIRT, "A piston in the wilderness couldn't push blocks in the wilderness");
            check(helper, blockAt(helper, new BlockPos(7, 1, 5)) == Blocks.DIRT, "A piston in a claim couldn't push blocks inside the same claim");
            check(helper, blockAt(helper, new BlockPos(5, 1, 4)) != Blocks.DIRT, "A piston pushed a block from the wilderness into a claim");
            check(helper, blockAt(helper, new BlockPos(4, 1, 4)) == Blocks.DIRT, "The blocks in front of a blocked piston moved");
        });
    }

    private static DispenserBlockEntity dispenser(GameTestHelper helper, BlockPos relative, Direction facing) {
        var pos = helper.absolutePos(relative);
        helper.getLevel().setBlockAndUpdate(pos, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, facing));
        if (!(helper.getLevel().getBlockEntity(pos) instanceof DispenserBlockEntity dispenser)) {
            throw helper.assertionException("No dispenser block entity at " + relative);
        }
        dispenser.setItem(0, new ItemStack(Items.WATER_BUCKET));
        return dispenser;
    }

    @GameTest(maxTicks = 40)
    public void dispensersCantPlaceIntoAClaimFromOutside(GameTestHelper helper) {
        floor(helper);
        var owner = player(helper, "DsOwner");
        var claims = List.of(claim(helper, new BlockPos(6, 1, 4), 1, owner.getUUID()));

        var blocked = dispenser(helper, new BlockPos(4, 1, 4), Direction.EAST);
        dispenser(helper, new BlockPos(1, 1, 1), Direction.EAST);
        set(helper, new BlockPos(3, 1, 4), Blocks.REDSTONE_BLOCK);
        set(helper, new BlockPos(0, 1, 1), Blocks.REDSTONE_BLOCK);

        finish(helper, 12, claims, () -> {
            check(helper, isWater(helper, new BlockPos(2, 1, 1)), "A dispenser in the wilderness couldn't empty a water bucket");
            check(helper, !isWater(helper, new BlockPos(5, 1, 4)), "A dispenser outside a claim emptied a water bucket into it");
            check(helper, blocked.getItem(0).is(Items.WATER_BUCKET), "A blocked dispenser used up its water bucket");
        });
    }

    /**
     * Sand dropped above a claim's height (it only reaches y 2) must not land inside it, sand in the wilderness lands.
     */
    @GameTest(maxTicks = 80)
    public void fallingBlocksDontLandInAClaimFromOutside(GameTestHelper helper) {
        floor(helper);
        var owner = player(helper, "FbOwner");
        var claims = List.of(claim(helper, new BlockPos(5, 1, 5), 1, owner.getUUID()));

        set(helper, new BlockPos(4, 6, 4), Blocks.SAND);
        set(helper, new BlockPos(1, 6, 1), Blocks.SAND);

        finish(helper, 60, claims, () -> {
            check(helper, blockAt(helper, new BlockPos(1, 1, 1)) == Blocks.SAND, "Sand didn't land in the wilderness");
            check(helper, blockAt(helper, new BlockPos(4, 1, 4)) != Blocks.SAND, "Sand that fell from outside landed inside a claim");
        });
    }

    private static void explodeAt(GameTestHelper helper, BlockPos relative, @Nullable Entity source) {
        var center = center(helper, relative);
        helper.getLevel().explode(source, center.x, center.y, center.z, 3f, Level.ExplosionInteraction.TNT);
    }

    private static void dirt(GameTestHelper helper, BlockPos... positions) {
        for (var pos : positions) {
            set(helper, pos, Blocks.DIRT);
        }
    }

    /**
     * Claim covers x 5..7, z 3..5 (the anchor sits at the far end). Explosions go off at x 3, two blocks from the
     * claimed dirt at x 5 and the unclaimed dirt at x 1.
     */
    @GameTest(maxTicks = 20)
    public void explosionsOnlyBreakClaimedBlocksWhenAllowed(GameTestHelper helper) {
        floor(helper);
        var owner = player(helper, "ExOwner");
        var stranger = player(helper, "ExStranger");
        var claim = claim(helper, new BlockPos(6, 1, 4), 1, owner.getUUID());
        var boom = new BlockPos(3, 1, 4);
        var claimed = new BlockPos(5, 1, 4);
        var unclaimed = new BlockPos(1, 1, 4);
        var failures = new ArrayList<String>();
        try {
            dirt(helper, claimed, unclaimed);
            explodeAt(helper, boom, null);
            if (blockAt(helper, claimed) != Blocks.DIRT) failures.add("an explosion without a player broke a claimed block");
            if (blockAt(helper, unclaimed) == Blocks.DIRT) failures.add("an explosion didn't break an unclaimed block (the test explosion is too weak)");

            dirt(helper, claimed, unclaimed);
            explodeAt(helper, boom, stranger);
            if (blockAt(helper, claimed) != Blocks.DIRT) failures.add("an explosion caused by a stranger broke a claimed block");

            dirt(helper, claimed, unclaimed);
            explodeAt(helper, boom, owner);
            if (blockAt(helper, claimed) == Blocks.DIRT) failures.add("an explosion caused by the owner didn't break a block in their claim");

            // Explosion Controller set to disabled lets every explosion through
            dirt(helper, claimed, unclaimed);
            var controllerPos = helper.absolutePos(new BlockPos(7, 1, 5));
            set(helper, new BlockPos(7, 1, 5), GOMLBlocks.EXPLOSION_CONTROLLER.getFirst());
            claim.addAugment(controllerPos, GOMLBlocks.EXPLOSION_CONTROLLER.getFirst());
            claim.setData(ExplosionControllerAugmentBlock.KEY, StatusEnum.Toggle.DISABLED);
            explodeAt(helper, boom, null);
            if (blockAt(helper, claimed) == Blocks.DIRT) failures.add("an explosion didn't break a claimed block with the Explosion Controller disabled");

            dirt(helper, claimed, unclaimed);
            claim.setData(ExplosionControllerAugmentBlock.KEY, StatusEnum.Toggle.ENABLED);
            explodeAt(helper, boom, null);
            if (blockAt(helper, claimed) != Blocks.DIRT) failures.add("an explosion broke a claimed block with the Explosion Controller enabled");

            check(helper, blockAt(helper, new BlockPos(6, 1, 4)) != Blocks.AIR, "An explosion broke the claim anchor");
            check(helper, !claim.isDestroyed(), "An explosion destroyed the claim");
            check(helper, failures.isEmpty(), String.join("; ", failures));
            helper.succeed();
        } finally {
            claim.removeAugment(helper.absolutePos(new BlockPos(7, 1, 5)));
            set(helper, new BlockPos(7, 1, 5), Blocks.AIR);
            remove(helper, claim);
        }
    }

    /**
     * Breaking the anchor through the world (not by a player) must not leave the claim behind as a ghost,
     * and a claim destroyed through the API must disappear from lookups.
     */
    @GameTest
    public void destroyedClaimsDisappearFromLookups(GameTestHelper helper) {
        var owner = player(helper, "DeOwner");
        var stranger = player(helper, "DeStranger");
        var claim = claim(helper, CENTER, 2, owner.getUUID());
        var pos = helper.absolutePos(CENTER.east());
        check(helper, ClaimUtils.getClaimsAt(helper.getLevel(), pos).isNotEmpty(), "A new claim wasn't found at its position");
        check(helper, !ClaimUtils.canModify(helper.getLevel(), pos, stranger), "A stranger could modify a new claim");

        remove(helper, claim);
        check(helper, claim.isDestroyed(), "The claim wasn't marked as destroyed");
        check(helper, ClaimUtils.getClaimsAt(helper.getLevel(), pos).isEmpty(), "A destroyed claim was still found at its position");
        check(helper, ClaimUtils.canModify(helper.getLevel(), pos, stranger), "A destroyed claim still protected its area");
        helper.succeed();
    }
}
