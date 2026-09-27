package draylar.goml.test;

import draylar.goml.GetOffMyLawn;
import draylar.goml.api.ClaimUtils;
import draylar.goml.other.GomlPlayer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

import static draylar.goml.test.GomlTestUtil.*;

/**
 * Runs /goml commands the way a player does, then checks the result and every message they got back:
 * no vanilla failure ("Unknown command", "An unexpected error occurred") and no missing GOML translation.
 */
public class CommandTests {
    /**
     * @return the chat messages the command sent to the player
     */
    private static List<Component> run(GameTestHelper helper, RecordingPlayer player, String command) {
        var before = player.messages(false).size();
        helper.getLevel().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
        var all = player.messages(false);
        var sent = new ArrayList<>(all.subList(before, all.size()));

        for (var message : sent) {
            var keys = translationKeys(message);
            for (var key : keys) {
                if (key.startsWith("command.") || key.startsWith("argument.") || key.startsWith("commands.")) {
                    throw helper.assertionException("/" + command + " failed with " + key + ": " + message.getString());
                }
            }
            var missing = missingTranslations(message);
            if (!missing.isEmpty()) {
                throw helper.assertionException("/" + command + " sent untranslated text: " + missing);
            }
        }
        return sent;
    }

    private static boolean sentKey(List<Component> messages, String key) {
        return messages.stream().anyMatch(message -> translationKeys(message).contains(key));
    }

    private static void expectKey(GameTestHelper helper, List<Component> messages, String key, String command) {
        if (!sentKey(messages, key)) {
            var keys = messages.stream().map(GomlTestUtil::translationKeys).toList();
            throw helper.assertionException("/" + command + " didn't answer with " + key + ", got " + keys);
        }
    }

    @GameTest
    public void trustNeedsAnOwnedClaim(GameTestHelper helper) {
        var owner = player(helper, "TnOwner");
        var stranger = player(helper, "TnStranger");
        player(helper, "TnFriend");
        var claim = claim(helper, CENTER, 2, owner.getUUID());
        try {
            expectKey(helper, run(helper, stranger, "goml trust TnFriend"), "text.goml.command.not_in_owned_claim", "goml trust");
            expectKey(helper, run(helper, stranger, "goml trust TnFriend all"), "text.goml.command.no_owned_claims", "goml trust all");
            check(helper, claim.getTrusted().isEmpty(), "A stranger trusted someone in another player's claim");
            helper.succeed();
        } finally {
            remove(helper, claim);
        }
    }

    @GameTest
    public void trustAndUntrustInTheClaimYouStandIn(GameTestHelper helper) {
        var owner = player(helper, "TuOwner");
        var friend = player(helper, "TuFriend");
        var claim = claim(helper, CENTER, 2, owner.getUUID());
        try {
            expectKey(helper, run(helper, owner, "goml trust TuFriend"), "text.goml.command.trusted", "goml trust");
            check(helper, claim.getTrusted().contains(friend.getUUID()), "/goml trust didn't trust the player");
            expectKey(helper, run(helper, owner, "goml trust TuFriend"), "text.goml.command.already_added", "goml trust (again)");

            expectKey(helper, run(helper, owner, "goml untrust TuFriend"), "text.goml.command.untrusted", "goml untrust");
            check(helper, !claim.getTrusted().contains(friend.getUUID()), "/goml untrust didn't untrust the player");

            expectKey(helper, run(helper, owner, "goml untrust TuOwner"), "text.goml.command.remove_self", "goml untrust (self)");
            check(helper, claim.isOwner(owner), "The owner removed themselves from their claim");

            expectKey(helper, run(helper, owner, "goml addowner TuFriend"), "text.goml.command.owner_added", "goml addowner");
            check(helper, claim.isOwner(friend), "/goml addowner didn't add the owner");
            helper.succeed();
        } finally {
            remove(helper, claim);
        }
    }

    @GameTest
    public void trustAllCoversEveryOwnedClaim(GameTestHelper helper) {
        var owner = player(helper, "TaOwner");
        var friend = player(helper, "TaFriend");
        var other = player(helper, "TaOther");
        var first = claim(helper, new BlockPos(2, 1, 2), 1, owner.getUUID());
        var second = claim(helper, new BlockPos(6, 1, 6), 1, owner.getUUID());
        var notTheirs = claim(helper, new BlockPos(2, 1, 6), 1, other.getUUID());
        try {
            // Standing outside of every claim is fine with "all"
            moveTo(helper, owner, new BlockPos(6, 1, 2));
            expectKey(helper, run(helper, owner, "goml trust TaFriend all"), "text.goml.command.trusted_all", "goml trust all");
            check(helper, first.getTrusted().contains(friend.getUUID()) && second.getTrusted().contains(friend.getUUID()), "/goml trust all missed a claim");
            check(helper, !notTheirs.getTrusted().contains(friend.getUUID()), "/goml trust all changed someone else's claim");

            expectKey(helper, run(helper, owner, "goml untrust TaFriend all"), "text.goml.command.untrusted_all", "goml untrust all");
            check(helper, !first.getTrusted().contains(friend.getUUID()) && !second.getTrusted().contains(friend.getUUID()), "/goml untrust all missed a claim");
            helper.succeed();
        } finally {
            remove(helper, first, second, notTheirs);
        }
    }

    @GameTest
    public void helpOnlyListsUsableCommands(GameTestHelper helper) {
        var player = player(helper, "HpPlayer");
        var admin = player(helper, "HpAdmin");
        TestPermissions.grant(admin.getUUID());
        try {
            var help = run(helper, player, "goml help");
            expectKey(helper, help, "text.goml.command.help.trust", "goml help");
            check(helper, !sentKey(help, "text.goml.command.help.admin"), "/goml help showed admin commands to a regular player");

            expectKey(helper, run(helper, admin, "goml help"), "text.goml.command.help.admin", "goml help (admin)");
            helper.succeed();
        } finally {
            TestPermissions.revoke(admin.getUUID());
        }
    }

    @GameTest
    public void escapeMovesStrangersOut(GameTestHelper helper) {
        floor(helper);
        var owner = player(helper, "EsOwner");
        var stranger = player(helper, "EsStranger");
        var claim = claim(helper, CENTER, 1, owner.getUUID());
        try {
            expectKey(helper, run(helper, owner, "goml escape"), "text.goml.command.cant_escape", "goml escape (owner)");

            expectKey(helper, run(helper, stranger, "goml escape"), "text.goml.command.escaped", "goml escape");
            check(helper, ClaimUtils.getClaimsAt(helper.getLevel(), stranger.blockPosition()).filter(x -> x.getValue() == claim).isEmpty(),
                    "/goml escape left the stranger inside the claim at " + stranger.blockPosition());
            helper.succeed();
        } finally {
            remove(helper, claim);
        }
    }

    @GameTest
    public void adminCommandsAndAdminMode(GameTestHelper helper) {
        var owner = player(helper, "AcOwner");
        var admin = player(helper, "AcAdmin");
        var claim = claim(helper, CENTER, 2, owner.getUUID());
        TestPermissions.grant(admin.getUUID());
        try {
            var info = run(helper, admin, "goml admin info");
            check(helper, info.stream().anyMatch(message -> message.getString().contains("Origin")), "/goml admin info didn't describe the claim");
            expectKey(helper, run(helper, admin, "goml admin general"), "text.goml.command.number_all", "goml admin general");

            var update = run(helper, admin, "goml admin update");
            check(helper, sentKey(update, "text.goml.command.update.disabled") || sentKey(update, "text.goml.command.update.checking"),
                    "/goml admin update didn't answer");

            // Admin mode lets them build in someone else's claim, until it's turned off again
            var pos = helper.absolutePos(CENTER.east().above());
            helper.getLevel().setBlockAndUpdate(pos, Blocks.DIRT.defaultBlockState());
            var state = helper.getLevel().getBlockState(pos);
            check(helper, !PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(helper.getLevel(), admin, pos, state, null), "An admin could break blocks in a claim without admin mode");
            expectKey(helper, run(helper, admin, "goml admin adminmode"), "text.goml.admin_mode.enabled", "goml admin adminmode");
            check(helper, ClaimUtils.isInAdminMode(admin), "/goml admin adminmode didn't enable admin mode");
            check(helper, PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(helper.getLevel(), admin, pos, state, null), "Admin mode didn't let an admin break blocks in a claim");
            expectKey(helper, run(helper, admin, "goml admin adminmode"), "text.goml.admin_mode.disabled", "goml admin adminmode (off)");
            check(helper, !ClaimUtils.isInAdminMode(admin), "/goml admin adminmode didn't disable admin mode");

            // Admin mode also allows managing other players' claims
            ((GomlPlayer) admin).goml_setAdminMode(true);
            player(helper, "AcFriend");
            expectKey(helper, run(helper, admin, "goml trust AcFriend"), "text.goml.command.trusted", "goml trust (admin mode)");
            helper.succeed();
        } finally {
            ((GomlPlayer) admin).goml_setAdminMode(false);
            TestPermissions.revoke(admin.getUUID());
            remove(helper, claim);
        }
    }

    @GameTest
    public void reloadKeepsTheConfigWorking(GameTestHelper helper) {
        var admin = player(helper, "RlAdmin");
        TestPermissions.grant(admin.getUUID());
        try {
            run(helper, admin, "goml admin reload");
            check(helper, GetOffMyLawn.CONFIG != null, "/goml admin reload left no config");
            helper.succeed();
        } finally {
            TestPermissions.revoke(admin.getUUID());
        }
    }
}
