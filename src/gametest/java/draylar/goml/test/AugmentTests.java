package draylar.goml.test;

import draylar.goml.block.SelectiveClaimAugmentBlock;
import draylar.goml.block.augment.GreeterAugmentBlock;
import draylar.goml.other.StatusEnum;
import draylar.goml.registry.GOMLBlocks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;

import static draylar.goml.test.GomlTestUtil.*;

/**
 * Augments are called the way Claim#tick calls them for players inside, with players that record what they're sent.
 */
public class AugmentTests {
    // AugmentEffects.DURATION, the longest an augment's own effect lasts
    private static final int AUGMENT_EFFECT_DURATION = 100;

    /**
     * 10 seconds in a Chaos Zone, one claim tick and one player tick per server tick like on a real server (so effect
     * speed-ups like TT20's apply the way they would there), then the effect's edge cases.
     */
    @GameTest(maxTicks = 240)
    public void chaosZoneKeepsAShortEffectWithoutSpammingUpdates(GameTestHelper helper) {
        var owner = player(helper, "CzOwner");
        var visitor = player(helper, "CzVisitor");
        var claim = claim(helper, CENTER, 2, owner.getUUID());
        var chaosZone = (SelectiveClaimAugmentBlock) GOMLBlocks.CHAOS_ZONE.getFirst();
        claim.addAugment(helper.absolutePos(CENTER.east()), chaosZone);
        chaosZone.onPlayerEnter(claim, visitor);
        var updatesBefore = visitor.count(ClientboundUpdateMobEffectPacket.class);
        var ticks = 200;

        for (int tick = 1; tick <= ticks; tick++) {
            helper.runAtTickTime(tick, () -> {
                chaosZone.playerTick(claim, visitor);
                visitor.baseTick();
            });
        }

        helper.runAtTickTime(ticks + 1, () -> {
            try {
                var strength = visitor.getEffect(MobEffects.STRENGTH);
                check(helper, strength != null, "Chaos Zone didn't keep Strength on the player");
                check(helper, strength.getDuration() <= AUGMENT_EFFECT_DURATION && strength.isAmbient() && !strength.isVisible(), "Chaos Zone's Strength isn't a short ambient effect: " + strength);
                var updates = visitor.count(ClientboundUpdateMobEffectPacket.class) - updatesBefore;
                // Refreshing it every tick sent one update per tick, about one a second is expected
                check(helper, updates >= 1 && updates <= 20, "Chaos Zone sent " + updates + " effect updates in " + ticks + " ticks");

                // An effect that's about to run out is refreshed
                visitor.removeEffect(MobEffects.STRENGTH);
                visitor.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 5, 0, true, false, false));
                chaosZone.playerTick(claim, visitor);
                check(helper, visitor.getEffect(MobEffects.STRENGTH).getDuration() > 5, "Chaos Zone didn't refresh its effect before it ran out");

                chaosZone.onPlayerExit(claim, visitor);
                check(helper, !visitor.hasEffect(MobEffects.STRENGTH), "Chaos Zone's Strength stayed after leaving the claim");

                // A potion is left alone, both while inside and when leaving
                var potion = new MobEffectInstance(MobEffects.STRENGTH, 3600, 1);
                visitor.addEffect(potion);
                chaosZone.onPlayerEnter(claim, visitor);
                chaosZone.playerTick(claim, visitor);
                chaosZone.onPlayerExit(claim, visitor);
                var after = visitor.getEffect(MobEffects.STRENGTH);
                check(helper, after != null && after.getAmplifier() == 1 && after.getDuration() > 3000, "Chaos Zone changed or removed a Strength II potion: " + after);
                helper.succeed();
            } finally {
                remove(helper, claim);
            }
        });
    }

    @GameTest
    public void chaosZoneModeOnlyAffectsTheChosenPlayers(GameTestHelper helper) {
        var owner = player(helper, "CmOwner");
        var visitor = player(helper, "CmVisitor");
        var claim = claim(helper, CENTER, 2, owner.getUUID());
        var chaosZone = (SelectiveClaimAugmentBlock) GOMLBlocks.CHAOS_ZONE.getFirst();
        try {
            claim.addAugment(helper.absolutePos(CENTER.east()), chaosZone);
            claim.setData(chaosZone.key, StatusEnum.TargetPlayer.TRUSTED);
            chaosZone.playerTick(claim, visitor);
            chaosZone.playerTick(claim, owner);
            check(helper, !visitor.hasEffect(MobEffects.STRENGTH), "Chaos Zone set to trusted players affected a visitor");
            check(helper, owner.hasEffect(MobEffects.STRENGTH), "Chaos Zone set to trusted players didn't affect the owner");

            claim.setData(chaosZone.key, StatusEnum.TargetPlayer.UNTRUSTED);
            chaosZone.onPlayerExit(claim, owner);
            chaosZone.playerTick(claim, visitor);
            chaosZone.playerTick(claim, owner);
            check(helper, visitor.hasEffect(MobEffects.STRENGTH), "Chaos Zone set to untrusted players didn't affect a visitor");
            check(helper, !owner.hasEffect(MobEffects.STRENGTH), "Chaos Zone set to untrusted players affected the owner");
            helper.succeed();
        } finally {
            remove(helper, claim);
        }
    }

    @GameTest(maxTicks = 40)
    public void heavenWingsLetsPlayersFallSafelyWhenRemoved(GameTestHelper helper) {
        var owner = player(helper, "HwOwner");
        // Creative players can always fly, which would hide what the augment does
        owner.setGameMode(GameType.SURVIVAL);
        var claim = claim(helper, CENTER, 2, owner.getUUID());
        var wings = (SelectiveClaimAugmentBlock) GOMLBlocks.HEAVEN_WINGS.getFirst();
        claim.addAugment(helper.absolutePos(CENTER.east()), wings);

        wings.onPlayerEnter(claim, owner);
        check(helper, owner.getAbilities().mayfly, "Heaven's Wings didn't let the owner fly");

        owner.getAbilities().flying = true;
        wings.onPlayerExit(claim, owner);
        check(helper, !owner.getAbilities().mayfly, "Heaven's Wings didn't take flight away after leaving");
        check(helper, owner.hasEffect(MobEffects.SLOW_FALLING), "Losing Heaven's Wings mid-flight didn't give Slow Falling");

        // Still falling a second later, so the effect has to be kept up
        helper.runAtTickTime(25, () -> {
            try {
                check(helper, owner.hasEffect(MobEffects.SLOW_FALLING), "Slow Falling ran out while the player was still falling");
                owner.setOnGround(true);
            } catch (RuntimeException e) {
                remove(helper, claim);
                throw e;
            }
        });
        helper.runAtTickTime(28, () -> {
            try {
                check(helper, !owner.hasEffect(MobEffects.SLOW_FALLING), "Slow Falling stayed after the player landed");
                helper.succeed();
            } finally {
                remove(helper, claim);
            }
        });
    }

    @GameTest
    public void heavenWingsLeavesGroundedPlayersAlone(GameTestHelper helper) {
        var owner = player(helper, "HgOwner");
        owner.setGameMode(GameType.SURVIVAL);
        var claim = claim(helper, CENTER, 2, owner.getUUID());
        var wings = (SelectiveClaimAugmentBlock) GOMLBlocks.HEAVEN_WINGS.getFirst();
        try {
            claim.addAugment(helper.absolutePos(CENTER.east()), wings);
            wings.onPlayerEnter(claim, owner);
            wings.onPlayerExit(claim, owner);
            check(helper, !owner.getAbilities().mayfly, "Heaven's Wings didn't take flight away after leaving");
            check(helper, !owner.hasEffect(MobEffects.SLOW_FALLING), "A player who wasn't flying got Slow Falling");
            helper.succeed();
        } finally {
            remove(helper, claim);
        }
    }

    @GameTest
    public void witheringSealRemovesWither(GameTestHelper helper) {
        var owner = player(helper, "WsOwner");
        var visitor = player(helper, "WsVisitor");
        var claim = claim(helper, CENTER, 2, owner.getUUID());
        var seal = (SelectiveClaimAugmentBlock) GOMLBlocks.WITHERING_SEAL.getFirst();
        try {
            claim.addAugment(helper.absolutePos(CENTER.east()), seal);
            visitor.addEffect(new MobEffectInstance(MobEffects.WITHER, 200));
            seal.playerTick(claim, visitor);
            check(helper, !visitor.hasEffect(MobEffects.WITHER), "Withering Seal didn't remove Wither");

            claim.setData(seal.key, StatusEnum.TargetPlayer.TRUSTED);
            visitor.addEffect(new MobEffectInstance(MobEffects.WITHER, 200));
            seal.playerTick(claim, visitor);
            check(helper, visitor.hasEffect(MobEffects.WITHER), "Withering Seal set to trusted players removed Wither from a visitor");
            helper.succeed();
        } finally {
            remove(helper, claim);
        }
    }

    @GameTest
    public void greeterGreetsByName(GameTestHelper helper) {
        var owner = player(helper, "GrOwner");
        var visitor = player(helper, "GrVisitor");
        var claim = claim(helper, CENTER, 2, owner.getUUID());
        var greeter = GOMLBlocks.GREETER.getFirst();
        try {
            claim.addAugment(helper.absolutePos(CENTER.east()), greeter);
            claim.setData(GreeterAugmentBlock.MESSAGE_KEY, "Hi %player, welcome!");
            greeter.onPlayerEnter(claim, visitor);
            var messages = visitor.messages(false);
            check(helper, messages.size() == 1, "The greeter sent " + messages.size() + " messages");
            var text = messages.getFirst().getString();
            check(helper, text.contains("Hi GrVisitor, welcome!"), "Unexpected greeting: " + text);

            claim.setData(GreeterAugmentBlock.MESSAGE_KEY, " ");
            greeter.onPlayerEnter(claim, visitor);
            check(helper, visitor.messages(false).size() == 1, "The greeter sent a blank greeting");
            helper.succeed();
        } finally {
            remove(helper, claim);
        }
    }
}
