package draylar.goml.test;

import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.ProfileLookupCallback;
import draylar.goml.compat.BedrockCompat;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.UUIDUtil;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.players.CachedUserNameToIdResolver;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserNameToIdResolver;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static draylar.goml.test.GomlTestUtil.*;

/**
 * Bedrock player support that doesn't need Geyser or Floodgate installed. What needs a real Bedrock client is
 * checked by the Bedrock end-to-end test (.github/bedrock-test).
 */
public class BedrockTests {
    /**
     * How Floodgate stores a Bedrock player: its prefix (a dot by default) before their name, and a UUID made from
     * their Xbox id, with a zero first half.
     */
    private static NameAndId floodgatePlayer(String name, long xuid) {
        return new NameAndId(new UUID(0, xuid), "." + name);
    }

    /**
     * Stands in for Mojang behind a real name cache, and remembers what it was asked. It knows nobody.
     */
    private static final class RecordingMojang implements GameProfileRepository {
        private final List<String> asked = new CopyOnWriteArrayList<>();

        @Override
        public void findProfilesByNames(String[] names, ProfileLookupCallback callback) {
            for (var name : names) {
                this.asked.add(name);
                callback.onProfileLookupFailed(name, new IllegalStateException("The test's Mojang knows nobody"));
            }
        }

        @Override
        public Optional<com.mojang.authlib.services.response.NameAndId> findProfileByName(String name) {
            this.asked.add(name);
            return Optional.empty();
        }
    }

    private static void expectFound(GameTestHelper helper, UserNameToIdResolver names, String typed, NameAndId expected) {
        var found = BedrockCompat.findByName(helper.getLevel().getServer(), names, typed, ".");
        check(helper, found.equals(Optional.of(expected)), names.getClass().getSimpleName() + ": typing \"" + typed + "\" found "
                + found.map(NameAndId::name).orElse("nobody") + ", not " + expected.name());
    }

    @GameTest
    public void bedrockPlayersAreFoundWithoutFloodgatesPrefix(GameTestHelper helper) throws IOException {
        var server = helper.getLevel().getServer();
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
        var players = List.of(bedrock, spaced, unauthenticated, java, madeUp, javaTwin, bedrockTwin);

        // Game test servers look names up with a mock that never asks Mojang, so the real name cache of a server is
        // tested too, with a Mojang that knows nobody, in offline mode like the test servers
        var mojang = new RecordingMojang();
        var cacheFile = server.getWorldPath(LevelResource.ROOT).resolve("goml-test-usercache.json");
        Files.deleteIfExists(cacheFile);
        var realCache = new CachedUserNameToIdResolver(mojang, cacheFile.toFile());
        realCache.resolveOfflineUsers(true);

        var playerData = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(javaTwin.id() + ".dat");
        Files.createDirectories(playerData.getParent());
        Files.write(playerData, new byte[0]);
        try {
            for (var names : List.of(server.services().nameToIdCache(), realCache)) {
                players.forEach(names::add);
                expectFound(helper, names, "BtFriend", bedrock);
                expectFound(helper, names, ".BtFriend", bedrock);
                if (names instanceof CachedUserNameToIdResolver) {
                    // The real name cache ignores case, the game test server's mock doesn't
                    expectFound(helper, names, "btfriend", bedrock);
                }
                expectFound(helper, names, "Bt Pal", spaced);
                expectFound(helper, names, "BtZero", unauthenticated);
                expectFound(helper, names, "BtJava", java);
                expectFound(helper, names, "BtTwin", javaTwin);
                expectFound(helper, names, ".BtTwin", bedrockTwin);

                // Without Floodgate (no prefix), names are looked up as typed
                var withoutFloodgate = BedrockCompat.findByName(server, names, "BtJava", "");
                check(helper, withoutFloodgate.equals(Optional.of(java)), "Without a prefix, BtJava found " + withoutFloodgate);
            }

            // Everyone above is in the cache, so Mojang was never asked. A name nobody has is asked about as typed,
            // but never with the prefix: Mojang can't know Bedrock players.
            check(helper, mojang.asked.isEmpty(), "Looking up known players asked Mojang about " + mojang.asked);
            BedrockCompat.findByName(server, realCache, "BtNobody", ".");
            check(helper, mojang.asked.stream().noneMatch(name -> name.startsWith(".")), "Mojang was asked about a Bedrock name: " + mojang.asked);
            check(helper, mojang.asked.stream().anyMatch(name -> name.equalsIgnoreCase("BtNobody")), "Mojang wasn't asked about an unknown name: " + mojang.asked);
            helper.succeed();
        } finally {
            Files.deleteIfExists(playerData);
            Files.deleteIfExists(cacheFile);
        }
    }
}
