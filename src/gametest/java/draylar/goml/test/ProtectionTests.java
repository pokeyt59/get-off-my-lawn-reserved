package draylar.goml.test;

import draylar.goml.GetOffMyLawn;
import draylar.goml.block.SelectiveClaimAugmentBlock;
import draylar.goml.api.ClaimUtils;
import draylar.goml.other.GomlPlayer;
import draylar.goml.other.StatusEnum;
import draylar.goml.registry.GOMLBlocks;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;

import static draylar.goml.test.GomlTestUtil.*;

/**
 * Who can break, use and attack what, through the same Fabric events a real player triggers.
 * Claim at CENTER with radius 2 covers relative x/z 2..6 and y -1..3.
 */
public class ProtectionTests {
    private static boolean canBreak(GameTestHelper helper, Player player, BlockPos relative) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(relative);
        return PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, player, pos, level.getBlockState(pos), level.getBlockEntity(pos));
    }

    private static InteractionResult use(GameTestHelper helper, Player player, BlockPos relative) {
        var pos = helper.absolutePos(relative);
        return UseBlockCallback.EVENT.invoker().interact(player, helper.getLevel(), InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    private static InteractionResult attackBlock(GameTestHelper helper, Player player, BlockPos relative) {
        return AttackBlockCallback.EVENT.invoker().interact(player, helper.getLevel(), InteractionHand.MAIN_HAND, helper.absolutePos(relative), Direction.UP);
    }

    private static InteractionResult attack(GameTestHelper helper, Player player, Entity entity) {
        return AttackEntityCallback.EVENT.invoker().interact(player, helper.getLevel(), InteractionHand.MAIN_HAND, entity, new EntityHitResult(entity));
    }

    private static InteractionResult useEntity(GameTestHelper helper, Player player, Entity entity) {
        return UseEntityCallback.EVENT.invoker().interact(player, helper.getLevel(), InteractionHand.MAIN_HAND, entity, new EntityHitResult(entity));
    }

    private static boolean denied(InteractionResult result) {
        return result instanceof InteractionResult.Fail;
    }

    @GameTest
    public void breakingDependsOnRoleAndPosition(GameTestHelper helper) {
        var owner = player(helper, "BrOwner");
        var trusted = player(helper, "BrTrusted");
        var stranger = player(helper, "BrStranger");
        var claim = claim(helper, CENTER, 2, owner.getUUID(), trusted.getUUID());
        try {
            var inside = CENTER.east().above();
            var edge = CENTER.east(2).above();
            var outside = CENTER.east(3).above();
            var above = CENTER.above(3);
            for (var pos : new BlockPos[]{inside, edge, outside, above}) {
                set(helper, pos, Blocks.DIRT);
            }

            check(helper, !canBreak(helper, stranger, inside), "A stranger could break a block inside a claim");
            check(helper, !canBreak(helper, stranger, edge), "A stranger could break a block on the claim's edge");
            check(helper, canBreak(helper, stranger, outside), "A stranger couldn't break a block just outside the claim");
            check(helper, canBreak(helper, stranger, above), "A stranger couldn't break a block above the claim's height");
            check(helper, canBreak(helper, owner, inside), "The owner couldn't break a block in their claim");
            check(helper, canBreak(helper, trusted, inside), "A trusted player couldn't break a block in the claim");
            check(helper, denied(attackBlock(helper, stranger, inside)), "A stranger could start breaking a block inside a claim");
            check(helper, !denied(attackBlock(helper, owner, inside)), "The owner couldn't start breaking a block in their claim");
            helper.succeed();
        } finally {
            remove(helper, claim);
        }
    }

    @GameTest
    public void adminModeNeedsPermission(GameTestHelper helper) {
        var owner = player(helper, "AdOwner");
        var stranger = player(helper, "AdStranger");
        var claim = claim(helper, CENTER, 2, owner.getUUID());
        try {
            set(helper, CENTER.east().above(), Blocks.DIRT);
            ((GomlPlayer) stranger).goml_setAdminMode(true);
            // The flag alone isn't enough, the player also needs the admin permission (a fake player isn't op)
            check(helper, !ClaimUtils.isInAdminMode(stranger), "Admin mode worked without the admin permission");
            check(helper, !canBreak(helper, stranger, CENTER.east().above()), "Admin mode without permission bypassed the claim");
            helper.succeed();
        } finally {
            remove(helper, claim);
        }
    }

    @GameTest
    public void usingBlocksAndAllowedInteractions(GameTestHelper helper) {
        var owner = player(helper, "UsOwner");
        var stranger = player(helper, "UsStranger");
        var claim = claim(helper, CENTER, 2, owner.getUUID());
        var lever = CENTER.east().above();
        try {
            set(helper, lever, Blocks.LEVER);
            check(helper, denied(use(helper, stranger, lever)), "A stranger could use a lever inside a claim");
            check(helper, !denied(use(helper, owner, lever)), "The owner couldn't use a lever in their claim");
            check(helper, !denied(use(helper, stranger, new BlockPos(7, 2, 4))), "A stranger couldn't use a block outside the claim");

            var previous = GetOffMyLawn.CONFIG.allowedBlockInteraction;
            GetOffMyLawn.CONFIG.allowedBlockInteraction = new HashSet<>(previous);
            GetOffMyLawn.CONFIG.allowedBlockInteraction.add(Blocks.LEVER);
            try {
                check(helper, !denied(use(helper, stranger, lever)), "allowedBlockInteraction didn't let a stranger use a lever");
            } finally {
                GetOffMyLawn.CONFIG.allowedBlockInteraction = previous;
            }
            helper.succeed();
        } finally {
            remove(helper, claim);
        }
    }

    @GameTest
    public void animalsProtectedMonstersNot(GameTestHelper helper) {
        var owner = player(helper, "EnOwner");
        var stranger = player(helper, "EnStranger");
        var claim = claim(helper, CENTER, 2, owner.getUUID());
        var pig = helper.spawn(EntityTypes.PIG, CENTER.east().above());
        var zombie = helper.spawn(EntityTypes.ZOMBIE, CENTER.west().above());
        try {
            check(helper, denied(attack(helper, stranger, pig)), "A stranger could attack a pig inside a claim");
            check(helper, !denied(attack(helper, owner, pig)), "The owner couldn't attack a pig in their claim");
            check(helper, !denied(attack(helper, stranger, zombie)), "A stranger couldn't attack a zombie inside a claim (allowDamagingUnnamedHostileMobs)");
            helper.succeed();
        } finally {
            pig.discard();
            zombie.discard();
            remove(helper, claim);
        }
    }

    @GameTest
    public void fakePlayerSetting(GameTestHelper helper) {
        var owner = player(helper, "FkOwner");
        // FakePlayer subclasses count as fake players for allowFakePlayersToModify
        var machine = player(helper, "FkMachine");
        var claim = claim(helper, CENTER, 2, owner.getUUID());
        var pos = helper.absolutePos(CENTER.east().above());
        try {
            check(helper, !GetOffMyLawn.CONFIG.allowFakePlayersToModify, "allowFakePlayersToModify is on in the test config");
            check(helper, !ClaimUtils.canModify(helper.getLevel(), pos, machine), "A fake player could modify a claim with allowFakePlayersToModify off");
            var previous = GetOffMyLawn.CONFIG.allowFakePlayersToModify;
            GetOffMyLawn.CONFIG.allowFakePlayersToModify = true;
            try {
                check(helper, ClaimUtils.canModify(helper.getLevel(), pos, machine), "allowFakePlayersToModify didn't let a fake player modify the claim");
            } finally {
                GetOffMyLawn.CONFIG.allowFakePlayersToModify = previous;
            }
            helper.succeed();
        } finally {
            remove(helper, claim);
        }
    }

    @GameTest
    public void deniedMessageIsTranslated(GameTestHelper helper) {
        var owner = player(helper, "MsOwner");
        var stranger = player(helper, "MsStranger");
        var claim = claim(helper, CENTER, 2, owner.getUUID());
        try {
            set(helper, CENTER.east().above(), Blocks.LEVER);
            use(helper, stranger, CENTER.east().above());

            var overlays = stranger.messages(true);
            check(helper, !overlays.isEmpty(), "A denied stranger didn't get an action bar message");
            var keys = translationKeys(overlays.getFirst());
            check(helper, keys.contains("text.goml.area_protected.owner"), "The denied message doesn't name the owner: " + keys);
            check(helper, mentions(overlays.getFirst(), "MsOwner"), "The denied message names someone else than MsOwner");
            check(helper, missingTranslations(overlays.getFirst()).isEmpty(), "Denied message uses missing translations: " + missingTranslations(overlays.getFirst()));
            helper.succeed();
        } finally {
            remove(helper, claim);
        }
    }

    @GameTest
    public void overlappingClaimsOfTheSameOwner(GameTestHelper helper) {
        var owner = player(helper, "OvOwner");
        var stranger = player(helper, "OvStranger");
        var big = claim(helper, CENTER, 2, owner.getUUID());
        var small = claim(helper, CENTER.east(), 1, owner.getUUID());
        try {
            set(helper, CENTER.east().above(), Blocks.DIRT);
            check(helper, canBreak(helper, owner, CENTER.east().above()), "The owner couldn't break inside their overlapping claims");
            check(helper, !canBreak(helper, stranger, CENTER.east().above()), "A stranger could break inside overlapping claims");

            remove(helper, small);
            small = null;
            check(helper, !canBreak(helper, stranger, CENTER.east().above()), "Removing the inner claim unprotected the outer one");
            helper.succeed();
        } finally {
            remove(helper, big, small);
        }
    }

    /**
     * GOML's default allowed_interactions entity tag lets anyone ride minecarts and boats in claims, but not break them.
     */
    @GameTest
    public void minecartsCanBeRiddenButNotBroken(GameTestHelper helper) {
        var owner = player(helper, "McOwner");
        var stranger = player(helper, "McStranger");
        var claim = claim(helper, CENTER, 2, owner.getUUID());
        var minecart = helper.spawn(EntityTypes.MINECART, CENTER.east().above());
        var cow = helper.spawn(EntityTypes.COW, CENTER.west().above());
        try {
            check(helper, !denied(useEntity(helper, stranger, minecart)), "A stranger couldn't ride a minecart in a claim (goml:allowed_interactions entity tag)");
            check(helper, denied(attack(helper, stranger, minecart)), "A stranger could break a minecart in a claim");
            check(helper, denied(useEntity(helper, stranger, cow)), "A stranger could interact with a cow in a claim");
            check(helper, !denied(useEntity(helper, owner, cow)), "The owner couldn't interact with a cow in their claim");
            helper.succeed();
        } finally {
            minecart.discard();
            cow.discard();
            remove(helper, claim);
        }
    }

    @GameTest
    public void pvpOnlyInPvpArenas(GameTestHelper helper) {
        var owner = player(helper, "PvOwner");
        var stranger = player(helper, "PvStranger");
        var claim = claim(helper, CENTER, 2, owner.getUUID());
        var arena = (SelectiveClaimAugmentBlock) GOMLBlocks.PVP_ARENA.getFirst();
        try {
            check(helper, denied(attack(helper, stranger, owner)), "A player could attack another player in a claim without a PvP Arena");

            claim.addAugment(helper.absolutePos(CENTER.east()), arena);
            check(helper, !denied(attack(helper, stranger, owner)), "A player couldn't attack another player in a PvP Arena");

            claim.setData(arena.key, StatusEnum.TargetPlayer.DISABLED);
            check(helper, denied(attack(helper, stranger, owner)), "A disabled PvP Arena allowed PvP");

            claim.setData(arena.key, StatusEnum.TargetPlayer.TRUSTED);
            check(helper, denied(attack(helper, stranger, owner)), "A PvP Arena for trusted players let a stranger attack");
            helper.succeed();
        } finally {
            remove(helper, claim);
        }
    }
}
