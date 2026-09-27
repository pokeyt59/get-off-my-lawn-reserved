package draylar.goml.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import draylar.goml.GetOffMyLawn;
import draylar.goml.api.Claim;
import draylar.goml.api.ClaimUtils;
import draylar.goml.api.event.ClaimEvents;
import draylar.goml.block.entity.ClaimAnchorBlockEntity;
import draylar.goml.registry.GOMLBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class GomlTestUtil {
    /**
     * Middle of the default 8x8x8 test structure, one block above the floor tests put at y = 0.
     * Claims made here with radius 2 cover relative x/z 2..6 and y -1..3.
     */
    public static final BlockPos CENTER = new BlockPos(4, 1, 4);

    private static JsonObject english;

    private GomlTestUtil() {
    }

    public static GameProfile profile(String name) {
        return new GameProfile(UUID.nameUUIDFromBytes(("goml-test:" + name).getBytes(StandardCharsets.UTF_8)), name);
    }

    /**
     * A new player standing above {@link #CENTER} (claims made at CENTER put their anchor there), known to the
     * server's name cache so messages and commands can find their name.
     */
    public static RecordingPlayer player(GameTestHelper helper, String name) {
        var player = new RecordingPlayer(helper.getLevel(), profile(name));
        moveTo(helper, player, CENTER.above());
        var names = helper.getLevel().getServer().services().nameToIdCache();
        if (names != null) {
            names.add(player.nameAndId());
        }
        return player;
    }

    public static void moveTo(GameTestHelper helper, RecordingPlayer player, BlockPos relative) {
        var pos = helper.absolutePos(relative);
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    /**
     * Creates a claim like placing an anchor does (ClaimAnchorBlock#setPlacedBy), but with a small radius so it fits
     * in the test's area. The anchor block is placed at the origin.
     */
    public static Claim claim(GameTestHelper helper, BlockPos relativeOrigin, int radius, UUID owner, UUID... trusted) {
        return createClaim(helper.getLevel(), helper.absolutePos(relativeOrigin), radius, owner, trusted);
    }

    public static Claim createClaim(ServerLevel level, BlockPos origin, int radius, UUID owner, UUID... trusted) {
        var anchor = GOMLBlocks.MAKESHIFT_CLAIM_ANCHOR.getFirst();
        level.setBlockAndUpdate(origin, anchor.defaultBlockState());

        var claim = new Claim(level.getServer(), Set.of(owner), new HashSet<>(Arrays.asList(trusted)), origin);
        claim.internal_setIcon(new ItemStack(GOMLBlocks.MAKESHIFT_CLAIM_ANCHOR.getSecond()));
        claim.internal_setType(anchor);
        claim.internal_setWorld(level.dimension().identifier());
        var box = ClaimUtils.createClaimBox(origin, radius);
        claim.internal_setClaimBox(box);
        GetOffMyLawn.CLAIM.get(level).add(claim);
        if (level.getBlockEntity(origin) instanceof ClaimAnchorBlockEntity anchorEntity) {
            anchorEntity.setClaim(claim, box);
        }
        claim.internal_updateChunkCount(level);
        ClaimEvents.CLAIM_CREATED.invoker().onEvent(claim);
        claim.internal_enableUpdates();
        return claim;
    }

    /**
     * Removes claims made by {@link #claim}, so they can't affect other tests.
     */
    public static void remove(GameTestHelper helper, Claim... claims) {
        remove(helper.getLevel(), claims);
    }

    public static void remove(ServerLevel level, Claim... claims) {
        for (var claim : claims) {
            if (claim == null) {
                continue;
            }
            claim.destroy();
            level.setBlockAndUpdate(claim.getOrigin(), Blocks.AIR.defaultBlockState());
        }
    }

    /**
     * Stone floor under the whole test structure, at relative y = 0.
     */
    public static void floor(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    public static void set(GameTestHelper helper, BlockPos relative, Block block) {
        helper.getLevel().setBlockAndUpdate(helper.absolutePos(relative), block.defaultBlockState());
    }

    public static Block blockAt(GameTestHelper helper, BlockPos relative) {
        return helper.getLevel().getBlockState(helper.absolutePos(relative)).getBlock();
    }

    public static Vec3 center(GameTestHelper helper, BlockPos relative) {
        return Vec3.atCenterOf(helper.absolutePos(relative));
    }

    public static void check(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }

    // Translations

    /**
     * @return GOML translation keys used by the component (and its arguments/siblings) that en_us.json doesn't have
     */
    public static List<String> missingTranslations(Component component) {
        var keys = new ArrayList<String>();
        collectKeys(component, keys);
        var lang = english();
        return keys.stream()
                .filter(key -> key.startsWith("text.goml") || key.startsWith("block.goml") || key.startsWith("item.goml"))
                .filter(key -> !lang.has(key))
                .toList();
    }

    public static List<String> translationKeys(Component component) {
        var keys = new ArrayList<String>();
        collectKeys(component, keys);
        return keys;
    }

    /**
     * @return whether the text, a translation argument or a sibling contains the given text
     */
    public static boolean mentions(Component component, String text) {
        if (component.getContents() instanceof PlainTextContents plain && plain.text().contains(text)) {
            return true;
        }
        if (component.getContents() instanceof TranslatableContents translatable) {
            for (var arg : translatable.getArgs()) {
                if (arg instanceof Component argComponent ? mentions(argComponent, text) : String.valueOf(arg).contains(text)) {
                    return true;
                }
            }
        }
        for (var sibling : component.getSiblings()) {
            if (mentions(sibling, text)) {
                return true;
            }
        }
        return false;
    }

    private static void collectKeys(Component component, List<String> keys) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            keys.add(translatable.getKey());
            for (var arg : translatable.getArgs()) {
                if (arg instanceof Component argComponent) {
                    collectKeys(argComponent, keys);
                }
            }
        }
        for (var sibling : component.getSiblings()) {
            collectKeys(sibling, keys);
        }
    }

    private static synchronized JsonObject english() {
        if (english == null) {
            try (var stream = GetOffMyLawn.class.getResourceAsStream("/data/goml/lang/en_us.json")) {
                if (stream == null) {
                    throw new IllegalStateException("GOML's en_us.json isn't in the jar");
                }
                english = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return english;
    }
}
