package draylar.goml.test;

import com.mojang.brigadier.arguments.StringArgumentType;
import draylar.goml.GetOffMyLawn;
import draylar.goml.api.Claim;
import draylar.goml.compat.BedrockCompat;
import draylar.goml.registry.GOMLBlocks;
import draylar.goml.registry.GOMLItems;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
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
import java.util.Set;
import java.util.UUID;

/**
 * Server side of the Bedrock end-to-end test (.github/bedrock-test/bot.mjs). The bot joins through Geyser and runs
 * "/gomltest <step>"; each step sets something up for it and answers "[gomltest] <step> ok ...". The bot then checks
 * what it received as a Bedrock client (messages, particles, effects, menus).
 */
final class BedrockE2E {
    private static final NameAndId OWNER = new NameAndId(UUID.nameUUIDFromBytes("goml-e2e:owner".getBytes(StandardCharsets.UTF_8)), "E2EOwner");
    private static final int RADIUS = 8;

    @Nullable
    private static Claim claim;
    @Nullable
    private static BlockPos home;

    private BedrockE2E() {
    }

    static void init() {
        GetOffMyLawn.LOGGER.warn("GOML end-to-end test commands are enabled (goml.e2e), don't use this on a real server");
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("gomltest").then(Commands.argument("step", StringArgumentType.word()).executes(context -> {
                    var player = context.getSource().getPlayerOrException();
                    var step = StringArgumentType.getString(context, "step");
                    String result;
                    try {
                        result = "ok " + run(player, step);
                    } catch (Exception e) {
                        GetOffMyLawn.LOGGER.error("GOML end-to-end step {} failed", step, e);
                        result = "error " + e;
                    }
                    player.sendSystemMessage(Component.literal("[gomltest] " + step + " " + result));
                    return 1;
                }))
        ));
    }

    private static String run(ServerPlayer player, String step) {
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
