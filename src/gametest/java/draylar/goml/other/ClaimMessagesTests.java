package draylar.goml.other;

import draylar.goml.test.GomlTestUtil;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

import static draylar.goml.test.GomlTestUtil.*;

/**
 * The enter/leave action bar text for every kind of move between claims (in GOML's package, to reach
 * ClaimMessages#message).
 */
public class ClaimMessagesTests {
    private static void expect(GameTestHelper helper, @Nullable Component message, @Nullable String key, @Nullable String name, String what) {
        if (key == null) {
            check(helper, message == null, what + " showed a message: " + (message != null ? translationKeys(message) : null));
            return;
        }
        check(helper, message != null, what + " showed no message");
        var keys = translationKeys(message);
        check(helper, keys.contains(key), what + " showed " + keys + " instead of " + key);
        check(helper, missingTranslations(message).isEmpty(), what + " uses missing translations " + missingTranslations(message));
        if (name != null) {
            check(helper, mentions(message, name), what + " doesn't name " + name);
        }
    }

    @GameTest
    public void enterAndLeaveMessages(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var owner = player(helper, "CmsOwner");
        var neighbour = player(helper, "CmsNeighbour");
        var visitor = player(helper, "CmsVisitor");
        var home = claim(helper, new BlockPos(2, 1, 2), 1, owner.getUUID());
        var shed = claim(helper, new BlockPos(2, 1, 6), 1, owner.getUUID());
        var next = claim(helper, new BlockPos(6, 1, 2), 1, neighbour.getUUID());
        // Nobody on the server knows this owner's name
        var abandoned = claim(helper, new BlockPos(6, 1, 6), 1, UUID.nameUUIDFromBytes("goml-test:nobody".getBytes()));
        try {
            expect(helper, ClaimMessages.message(server, visitor, null, home), "text.goml.claim_enter", "CmsOwner", "Entering a claim");
            expect(helper, ClaimMessages.message(server, visitor, home, null), "text.goml.claim_leave", "CmsOwner", "Leaving a claim");
            expect(helper, ClaimMessages.message(server, owner, null, home), "text.goml.claim_enter.own", null, "Entering your own claim");
            expect(helper, ClaimMessages.message(server, owner, home, null), "text.goml.claim_leave.own", null, "Leaving your own claim");
            expect(helper, ClaimMessages.message(server, visitor, home, home), null, null, "Moving inside a claim");
            expect(helper, ClaimMessages.message(server, visitor, home, shed), null, null, "Moving between claims of the same owner");
            expect(helper, ClaimMessages.message(server, visitor, home, next), "text.goml.claim_enter", "CmsNeighbour", "Moving into a neighbour's claim");
            expect(helper, ClaimMessages.message(server, visitor, null, abandoned), "text.goml.claim_enter.unknown", null, "Entering a claim of an unknown owner");
            expect(helper, ClaimMessages.message(server, visitor, null, null), null, null, "Moving in the wilderness");
            helper.succeed();
        } finally {
            GomlTestUtil.remove(helper, home, shed, next, abandoned);
        }
    }
}
