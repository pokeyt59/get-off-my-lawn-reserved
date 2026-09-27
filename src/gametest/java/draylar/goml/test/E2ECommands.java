package draylar.goml.test;

import com.mojang.brigadier.arguments.StringArgumentType;
import draylar.goml.GetOffMyLawn;
import draylar.goml.api.Augment;
import draylar.goml.api.Claim;
import draylar.goml.api.ClaimUtils;
import draylar.goml.compat.BedrockCompat;
import draylar.goml.registry.GOMLBlocks;
import draylar.goml.registry.GOMLItems;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Server side of the end-to-end tests, only registered with -Dgoml.e2e=true. "/gomltest <step>" runs a step and answers
 * "[gomltest] <step> ok ..." (or "error ...") to whoever ran it, which for the console ends up in the server log.
 * <ul>
 * <li>Player steps, used by the Bedrock bot (.github/bedrock-test/bot.mjs) that joins through Geyser: it then checks
 * what it received as a Bedrock client (messages, particles, effects, menus).</li>
 * <li>Console steps, used by the full server test (test_server.py server): a grid of claims on a real world, and a
 * check that they are all still found, unchanged and with the right loaded chunk count (also after a restart).</li>
 * </ul>
 */
final class E2ECommands {
    private static final NameAndId OWNER = owner("E2EOwner", "goml-e2e:owner");
    private static final int RADIUS = 8;

    // The claim grid: far from spawn (and the bot's claim), the same on every start so it needs no saved state
    private static final int GRID_X = 1000;
    private static final int GRID_Z = 0;
    private static final int GRID_SIZE = 5;
    private static final int GRID_SPACING = 40;
    private static final int GRID_RADIUS = 12;
    private static final List<NameAndId> GRID_OWNERS = List.of(
            owner("GridOwnerA", "goml-e2e:grid-a"), owner("GridOwnerB", "goml-e2e:grid-b"), owner("GridOwnerC", "goml-e2e:grid-c"));

    @Nullable
    private static Claim claim;
    @Nullable
    private static BlockPos home;

    private E2ECommands() {
    }

    private static NameAndId owner(String name, String seed) {
        return new NameAndId(UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)), name);
    }

    static void init() {
        GetOffMyLawn.LOGGER.warn("GOML end-to-end test commands are enabled (goml.e2e), don't use this on a real server");
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("gomltest").then(Commands.argument("step", StringArgumentType.word()).executes(context -> {
                    var source = context.getSource();
                    var step = StringArgumentType.getString(context, "step");
                    String result;
                    try {
                        result = run(source, step);
                    } catch (Exception e) {
                        GetOffMyLawn.LOGGER.error("GOML end-to-end step {} failed", step, e);
                        result = "error " + e;
                    }
                    source.sendSystemMessage(Component.literal("[gomltest] " + step + " " + result));
                    return 1;
                }))
        ));
    }

    private static String run(CommandSourceStack source, String step) throws Exception {
        return switch (step) {
            case "claims" -> createGrid(source.getLevel());
            case "check" -> checkGrid(source.getLevel());
            default -> "ok " + runPlayerStep(source.getPlayerOrException(), step);
        };
    }

    private static String runPlayerStep(ServerPlayer player, String step) {
        var level = player.level();
        return switch (step) {
            case "detect" -> "bedrock=" + BedrockCompat.isBedrock(player);
            case "setup" -> {
                cleanup(player);
                player.setGameMode(GameType.SURVIVAL);
                level.getServer().services().nameToIdCache().add(OWNER);
                home = player.blockPosition();
                claim = GomlTestUtil.createClaim(level, home.below(), RADIUS, OWNER.id());
                yield "claim at " + home.below().toShortString();
            }
            case "deny" -> {
                // Same event as right-clicking a block in the claim
                var target = requireHome().below().east(2);
                var result = UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false));
                yield "result=" + result.getClass().getSimpleName();
            }
            case "leave" -> {
                var away = requireHome().east(RADIUS * 3);
                teleport(player, away);
                yield "at " + player.blockPosition().toShortString();
            }
            case "enter" -> {
                teleport(player, requireHome());
                yield "at " + player.blockPosition().toShortString();
            }
            case "goggles" -> {
                player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(GOMLItems.GOGGLES));
                yield "wearing goggles";
            }
            case "chaos" -> {
                requireClaim().addAugment(requireHome().below().west(), GOMLBlocks.CHAOS_ZONE.getFirst());
                yield "chaos zone added";
            }
            case "nochaos" -> {
                requireClaim().removeAugment(requireHome().below().west());
                yield "chaos zone removed";
            }
            case "cleanup" -> {
                cleanup(player);
                yield "removed";
            }
            default -> throw new IllegalArgumentException("unknown step");
        };
    }

    private static int gridX(int index) {
        return GRID_X + (index % GRID_SIZE - GRID_SIZE / 2) * GRID_SPACING;
    }

    private static int gridZ(int index) {
        return GRID_Z + (index / GRID_SIZE - GRID_SIZE / 2) * GRID_SPACING;
    }

    private static NameAndId gridOwner(int index) {
        return GRID_OWNERS.get(index % GRID_OWNERS.size());
    }

    @Nullable
    private static Augment gridAugment(int index) {
        return switch (index % 4) {
            case 0 -> GOMLBlocks.GREETER.getFirst();
            case 1 -> GOMLBlocks.CHAOS_ZONE.getFirst();
            case 2 -> GOMLBlocks.EXPLOSION_CONTROLLER.getFirst();
            default -> null;
        };
    }

    @Nullable
    private static Claim findGridClaim(ServerLevel level, int index) {
        var found = new ArrayList<Claim>();
        ClaimUtils.getClaimsOwnedBy(level, gridOwner(index).id()).forEach(entry -> {
            var origin = entry.getValue().getOrigin();
            if (origin.getX() == gridX(index) && origin.getZ() == gridZ(index)) {
                found.add(entry.getValue());
            }
        });
        return found.size() == 1 ? found.getFirst() : null;
    }

    /**
     * Claims on the surface of a real (generated) world, like players make them. Loading their chunks here and letting
     * them unload again already exercises the claims' loaded chunk counts.
     */
    private static String createGrid(ServerLevel level) {
        var names = level.getServer().services().nameToIdCache();
        GRID_OWNERS.forEach(names::add);
        int created = 0;
        for (int index = 0; index < GRID_SIZE * GRID_SIZE; index++) {
            if (findGridClaim(level, index) != null) {
                continue;
            }
            int x = gridX(index), z = gridZ(index);
            // getHeight only knows loaded chunks, this generates it if needed
            level.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
            var origin = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            var gridClaim = GomlTestUtil.createClaim(level, origin, GRID_RADIUS, gridOwner(index).id());
            var augment = gridAugment(index);
            if (augment != null) {
                gridClaim.addAugment(origin.above(), augment);
            }
            created++;
        }
        return "ok created=" + created + " center=" + GRID_X + "," + GRID_Z + " claims=" + GRID_SIZE * GRID_SIZE;
    }

    /**
     * Every grid claim is still there, found by position lookups, with its owner and augment, and its loaded chunk
     * count (which decides whether its augments tick) matches the chunks actually loaded.
     */
    private static String checkGrid(ServerLevel level) {
        var problems = new ArrayList<String>();
        int found = 0, augments = 0, loadedChunks = 0;
        for (int index = 0; index < GRID_SIZE * GRID_SIZE; index++) {
            var where = gridX(index) + "," + gridZ(index);
            var gridClaim = findGridClaim(level, index);
            if (gridClaim == null) {
                problems.add("no claim of " + gridOwner(index).name() + " at " + where);
                continue;
            }
            found++;

            var origin = gridClaim.getOrigin();
            if (!ClaimUtils.getClaimsAt(level, origin).anyMatch(entry -> entry.getValue() == gridClaim)) {
                problems.add("getClaimsAt doesn't find the claim at " + where);
            }

            var expected = gridAugment(index);
            var actual = gridClaim.getAugments();
            if (expected == null ? !actual.isEmpty() : actual.size() != 1 || !gridClaim.hasAugment(expected)) {
                problems.add("claim at " + where + " has augments " + actual.values() + " instead of " + expected);
            }
            augments += actual.size();

            var box = gridClaim.getClaimBox().toBox();
            int loaded = 0;
            for (int x = SectionPos.blockToSectionCoord(box.x1()); x <= SectionPos.blockToSectionCoord(box.x2()); x++) {
                for (int z = SectionPos.blockToSectionCoord(box.z1()); z <= SectionPos.blockToSectionCoord(box.z2()); z++) {
                    if (level.hasChunk(x, z)) {
                        loaded++;
                    }
                }
            }
            if (gridClaim.internal_getLoadedChunks() != loaded) {
                problems.add("claim at " + where + " counts " + gridClaim.internal_getLoadedChunks() + " loaded chunks, " + loaded + " are loaded");
            }
            loadedChunks += loaded;
        }

        var counts = "claims=" + found + " augments=" + augments + " loadedChunks=" + loadedChunks;
        if (problems.isEmpty()) {
            return "ok " + counts;
        }
        return "error " + counts + " problems=" + problems.size() + ": " + String.join("; ", problems.subList(0, Math.min(problems.size(), 8)));
    }

    private static void teleport(ServerPlayer player, BlockPos pos) {
        var level = player.level();
        var y = Math.max(pos.getY(), level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()));
        player.teleportTo(level, pos.getX() + 0.5, y, pos.getZ() + 0.5, Set.of(), player.getYRot(), player.getXRot(), false);
    }

    private static void cleanup(ServerPlayer player) {
        player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        if (claim != null) {
            GomlTestUtil.remove(player.level(), claim);
            claim = null;
        }
    }

    private static BlockPos requireHome() {
        if (home == null) {
            throw new IllegalStateException("run setup first");
        }
        return home;
    }

    private static Claim requireClaim() {
        if (claim == null) {
            throw new IllegalStateException("run setup first");
        }
        return claim;
    }
}
