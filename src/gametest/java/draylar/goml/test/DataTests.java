package draylar.goml.test;

import com.jamieswhiteshirt.rtree3i.Box;
import draylar.goml.GetOffMyLawn;
import draylar.goml.api.Claim;
import draylar.goml.api.ClaimBox;
import draylar.goml.api.ClaimUtils;
import draylar.goml.block.augment.GreeterAugmentBlock;
import draylar.goml.registry.GOMLBlocks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static draylar.goml.test.GomlTestUtil.*;

/**
 * Claim storage: the R-tree lookups against brute force, claim boxes, and saving/loading claims.
 */
public class DataTests {
    // Far away from every test structure, so the random claims can't touch other tests
    private static final int AREA_X = 1_000_000;
    private static final int AREA_Z = 1_000_000;

    private static boolean containsBlock(Claim claim, BlockPos pos) {
        // Independent of the tree and of Box: a claim covers origin +- radius, the upper side included
        var box = claim.getClaimBox();
        var origin = box.origin();
        return Math.abs(pos.getX() - origin.getX()) <= box.radius()
                && Math.abs(pos.getY() - origin.getY()) <= box.radiusY()
                && Math.abs(pos.getZ() - origin.getZ()) <= box.radius();
    }

    private static Claim randomClaim(GameTestHelper helper, Random random, Set<BlockPos> usedOrigins, List<UUID> owners) {
        BlockPos origin;
        do {
            origin = new BlockPos(AREA_X + random.nextInt(400), random.nextInt(-64, 320), AREA_Z + random.nextInt(400));
        } while (!usedOrigins.add(origin));

        // Mostly small claims, some big ones overlapping many others
        var radius = random.nextInt(10) == 0 ? random.nextInt(40, 120) : random.nextInt(1, 25);
        var claim = new Claim(helper.getLevel().getServer(), Set.of(owners.get(random.nextInt(owners.size()))), Set.of(), origin);
        claim.internal_setWorld(helper.getLevel().dimension().identifier());
        claim.internal_setClaimBox(new ClaimBox(origin, radius, random.nextInt(1, radius + 1)));
        return claim;
    }

    private static Set<Claim> ours(Set<Claim> claims, Iterable<com.jamieswhiteshirt.rtree3i.Entry<ClaimBox, Claim>> entries) {
        var found = new HashSet<Claim>();
        for (var entry : entries) {
            if (claims.contains(entry.getValue())) {
                found.add(entry.getValue());
            }
        }
        return found;
    }

    private static Set<Claim> found(Set<Claim> claims, com.jamieswhiteshirt.rtree3i.Selection<com.jamieswhiteshirt.rtree3i.Entry<ClaimBox, Claim>> selection) {
        var list = new ArrayList<com.jamieswhiteshirt.rtree3i.Entry<ClaimBox, Claim>>();
        selection.forEach(list::add);
        return ours(claims, list);
    }

    @GameTest(maxTicks = 100)
    public void claimLookupsMatchBruteForce(GameTestHelper helper) {
        var level = helper.getLevel();
        var component = GetOffMyLawn.CLAIM.get(level);
        var random = new Random(0x60_4d_4c);
        var owners = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var usedOrigins = new HashSet<BlockPos>();
        var live = new HashSet<Claim>();
        var all = new ArrayList<Claim>();

        try {
            for (int i = 0; i < 400; i++) {
                var claim = randomClaim(helper, random, usedOrigins, owners);
                component.add(claim);
                live.add(claim);
                all.add(claim);
            }
            // Removing shuffles the tree around, then more are added in between
            for (int i = 0; i < 150; i++) {
                var claim = all.get(random.nextInt(all.size()));
                if (live.remove(claim)) {
                    component.remove(claim);
                }
            }
            for (int i = 0; i < 100; i++) {
                var claim = randomClaim(helper, random, usedOrigins, owners);
                component.add(claim);
                live.add(claim);
                all.add(claim);
            }
            var tracked = new HashSet<>(all);

            for (int i = 0; i < 5000; i++) {
                var pos = new BlockPos(AREA_X - 130 + random.nextInt(660), random.nextInt(-200, 460), AREA_Z - 130 + random.nextInt(660));
                var expected = new HashSet<Claim>();
                for (var claim : live) {
                    if (containsBlock(claim, pos)) {
                        expected.add(claim);
                    }
                }
                var actual = found(tracked, ClaimUtils.getClaimsAt(level, pos));
                if (!actual.equals(expected)) {
                    throw helper.assertionException("getClaimsAt " + pos + " found " + actual.size() + " claims instead of " + expected.size());
                }
            }

            for (int i = 0; i < 500; i++) {
                var x = AREA_X - 130 + random.nextInt(660);
                var y = random.nextInt(-200, 460);
                var z = AREA_Z - 130 + random.nextInt(660);
                var box = Box.create(x, y, z, x + random.nextInt(1, 60), y + random.nextInt(1, 60), z + random.nextInt(1, 60));
                var expected = new HashSet<Claim>();
                for (var claim : live) {
                    if (claim.getClaimBox().toBox().intersectsClosed(box)) {
                        expected.add(claim);
                    }
                }
                var actual = found(tracked, ClaimUtils.getClaimsInBox(level, box));
                if (!actual.equals(expected)) {
                    throw helper.assertionException("getClaimsInBox " + box + " found " + actual.size() + " claims instead of " + expected.size());
                }
            }

            for (var claim : all) {
                var withOrigin = found(tracked, ClaimUtils.getClaimsWithOrigin(level, claim.getOrigin()));
                check(helper, withOrigin.equals(live.contains(claim) ? Set.of(claim) : Set.of()), "getClaimsWithOrigin was wrong for " + claim.getOrigin());
            }
            for (var owner : owners) {
                var expected = new HashSet<Claim>();
                for (var claim : live) {
                    if (claim.isOwner(owner)) {
                        expected.add(claim);
                    }
                }
                check(helper, found(tracked, ClaimUtils.getClaimsOwnedBy(level, owner)).equals(expected), "getClaimsOwnedBy was wrong");
            }
            helper.succeed();
        } finally {
            for (var claim : live) {
                component.remove(claim);
            }
        }
    }

    @GameTest
    public void claimBoxesMatchTheProtectedBlocks(GameTestHelper helper) {
        for (var noShift : new boolean[]{false, true}) {
            for (var radius : new int[]{1, 2, 8, 24}) {
                var origin = new BlockPos(100, 64, -100);
                var box = new ClaimBox(origin, radius, radius, noShift);
                var tree = box.toBox();
                var aabb = box.minecraftBox();
                check(helper, aabb.minX == tree.x1() && aabb.minY == tree.y1() && aabb.minZ == tree.z1()
                                && aabb.maxX == tree.x2() && aabb.maxY == tree.y2() && aabb.maxZ == tree.z2(),
                        "The player box " + aabb + " doesn't match the protected blocks " + tree + (noShift ? " (chunk bound)" : ""));
            }
        }

        // A claim of radius 2 protects 5 blocks across, a player standing just past the edge isn't in it
        var box = new ClaimBox(BlockPos.ZERO, 2, 2);
        check(helper, box.minecraftBox().contains(2.5, 0.5, 0.5), "A player on the claim's edge isn't in the claim's box");
        check(helper, !box.minecraftBox().contains(3.5, 0.5, 0.5), "A player a block outside the claim is in the claim's box");
        check(helper, !box.minecraftBox().contains(-2.5, 0.5, 0.5), "A player a block outside the claim is in the claim's box");
        helper.succeed();
    }

    /**
     * Claims count their loaded chunks (augments only tick above 0): recounted from the loaded chunks when made or
     * loaded, then kept up by chunk load and unload events. A chunk already on its way out isn't loaded for the
     * recount but still sends its unload event afterwards, which must not push the count below 0: chunks loading again
     * would then leave it at 0 and the claim's augments would stop working.
     */
    @GameTest
    public void loadedChunkCountNeverGoesBelowZero(GameTestHelper helper) {
        var claim = claim(helper, CENTER, 2, UUID.randomUUID());
        try {
            int loaded = claim.internal_getLoadedChunks();
            check(helper, loaded > 0, "The claim's own chunk isn't counted as loaded");

            // Unload events for all of them, and for two the recount didn't see
            for (int i = 0; i < loaded + 2; i++) {
                claim.internal_decrementChunks();
            }
            check(helper, claim.internal_getLoadedChunks() == 0, "Unload events took the loaded chunk count to " + claim.internal_getLoadedChunks());

            claim.internal_incrementChunks();
            check(helper, claim.internal_getLoadedChunks() == 1, "A chunk loading again counts " + claim.internal_getLoadedChunks() + " loaded chunks, not 1");
        } finally {
            remove(helper, claim);
        }
        helper.succeed();
    }

    @GameTest
    public void claimsSurviveSavingAndLoading(GameTestHelper helper) {
        var level = helper.getLevel();
        var owner = UUID.randomUUID();
        var trusted = UUID.randomUUID();
        var origin = new BlockPos(AREA_X + 5000, 70, AREA_Z + 5000);
        var augmentPos = origin.east();

        var claim = new Claim(level.getServer(), Set.of(owner), Set.of(trusted), origin);
        claim.internal_setIcon(new ItemStack(GOMLBlocks.CRYSTAL_CLAIM_ANCHOR.getSecond()));
        claim.internal_setType(GOMLBlocks.CRYSTAL_CLAIM_ANCHOR.getFirst());
        claim.internal_setWorld(level.dimension().identifier());
        claim.internal_setClaimBox(new ClaimBox(origin, 20, 20));
        claim.addAugment(augmentPos, GOMLBlocks.GREETER.getFirst());
        claim.setData(GreeterAugmentBlock.MESSAGE_KEY, "Hello there %player");

        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        claim.writeData(output);
        var tag = output.buildResult();
        var loaded = Claim.readData(level.getServer(), TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag), 1);

        check(helper, loaded.getOwners().equals(Set.of(owner)), "Owners changed: " + loaded.getOwners());
        check(helper, loaded.getTrusted().equals(Set.of(trusted)), "Trusted players changed: " + loaded.getTrusted());
        check(helper, loaded.getOrigin().equals(origin), "Origin changed: " + loaded.getOrigin());
        check(helper, loaded.getClaimBox().equals(claim.getClaimBox()), "Claim box changed: " + loaded.getClaimBox() + " vs " + claim.getClaimBox());
        check(helper, loaded.getType() == GOMLBlocks.CRYSTAL_CLAIM_ANCHOR.getFirst(), "Anchor type changed: " + loaded.getType());
        check(helper, loaded.getIcon().is(GOMLBlocks.CRYSTAL_CLAIM_ANCHOR.getSecond()), "Icon changed: " + loaded.getIcon());
        check(helper, loaded.getAugments().get(augmentPos) == GOMLBlocks.GREETER.getFirst(), "Augments changed: " + loaded.getAugments());
        check(helper, "Hello there %player".equals(loaded.getData(GreeterAugmentBlock.MESSAGE_KEY)), "Greeter message changed: " + loaded.getData(GreeterAugmentBlock.MESSAGE_KEY));
        helper.succeed();
    }
}
