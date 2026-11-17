package draylar.goml.test;

import draylar.goml.compat.BedrockCompat;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.UUIDUtil;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.players.CachedUserNameToIdResolver;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;
import java.util.UUID;

import static draylar.goml.test.GomlTestUtil.*;

/**
 * Bedrock player support that doesn't need Geyser or Floodgate installed. What needs a real Bedrock client is
 * checked by the Bedrock end-to-end test (.github/bedrock-test).
 */
public class BedrockTests {
    /**
     * How Floodgate stores a Bedrock player: its prefix (a dot by default) before their name, and a UUID made from
     * their Xbox id, with a zero first half. These tests only add players to the name cache, so they never ask Mojang.
     */
    private static NameAndId floodgatePlayer(String name, long xuid) {
        return new NameAndId(new UUID(0, xuid), "." + name);
    }

    private static void expectFound(GameTestHelper helper, String typed, NameAndId expected) {
        var found = BedrockCompat.findByName(helper.getLevel().getServer(), typed, ".");
        check(helper, found.equals(Optional.of(expected)), "Typing \"" + typed + "\" found " + found.map(NameAndId::name).orElse("nobody") + ", not " + expected.name());
    }

    @GameTest
    public void bedrockPlayersAreFoundWithoutFloodgatesPrefix(GameTestHelper helper) throws IOException {
        var server = helper.getLevel().getServer();
        var names = server.services().nameToIdCache();
        var bedrock = floodgatePlayer("BtFriend", 0x9000000000001L);
        // Floodgate replaces spaces in Bedrock names with underscores
        var spaced = floodgatePlayer("Bt_Pal", 0x9000000000002L);
        // Logins without Xbox authentication (like the Bedrock test bot's) get Xbox id 0, the all-zero UUID
        var unauthenticated = floodgatePlayer("BtZero", 0);
        var java = new NameAndId(profile("BtJava"));
        // The cache also keeps offline players vanilla made up for names typed in commands, prefixed or not
        var madeUp = new NameAndId(UUIDUtil.createOfflinePlayerUUID(".BtJava"), ".BtJava");
        // A Java player who has played here, and a Bedrock player who chose the same name
        var javaTwin = new NameAndId(profile("BtTwin"));
        var bedrockTwin = floodgatePlayer("BtTwin", 0x9000000000003L);
        for (var player : new NameAndId[]{bedrock, spaced, unauthenticated, java, madeUp, javaTwin, bedrockTwin}) {
            names.add(player);
        }
        var playerData = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(javaTwin.id() + ".dat");
        Files.createDirectories(playerData.getParent());
        Files.write(playerData, new byte[0]);
        try {
            expectFound(helper, "BtFriend", bedrock);
            expectFound(helper, ".BtFriend", bedrock);
            if (names instanceof CachedUserNameToIdResolver) {
                // The server's name cache ignores case (the dev environment's game test server has a mock that doesn't)
                expectFound(helper, "btfriend", bedrock);
            }
            expectFound(helper, "Bt Pal", spaced);
            expectFound(helper, "BtZero", unauthenticated);
            expectFound(helper, "BtJava", java);
            expectFound(helper, "BtTwin", javaTwin);
            expectFound(helper, ".BtTwin", bedrockTwin);

            // Without Floodgate (no prefix), names are looked up as typed
            var withoutFloodgate = BedrockCompat.findByName(server, "BtJava", "");
            check(helper, withoutFloodgate.equals(Optional.of(java)), "Without a prefix, BtJava found " + withoutFloodgate);
            helper.succeed();
        } finally {
            Files.deleteIfExists(playerData);
        }
    }
}
