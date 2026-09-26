package draylar.goml;

import com.jamieswhiteshirt.rtree3i.Box;
import eu.pb4.polymer.core.api.item.PolymerCreativeModeTabUtils;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistryV3;
import draylar.goml.api.Claim;
import draylar.goml.api.ClaimUtils;
import draylar.goml.api.GomlProtectionProvider;
import draylar.goml.cca.ClaimComponent;
import draylar.goml.cca.WorldClaimComponent;
import draylar.goml.compat.ArgonautsCompat;
import draylar.goml.compat.BedrockCompat;
import draylar.goml.compat.webmap.WebmapCompat;
import draylar.goml.other.CardboardWarning;
import draylar.goml.other.ClaimCommand;
import draylar.goml.other.ClaimMessages;
import draylar.goml.config.GOMLConfig;
import draylar.goml.other.PlaceholdersReg;
import draylar.goml.other.UpdateChecker;
import draylar.goml.other.VanillaTeamGroups;
import draylar.goml.registry.GOMLBlocks;
import draylar.goml.registry.GOMLEntities;
import draylar.goml.registry.GOMLItems;
import eu.pb4.common.protection.api.CommonProtection;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ladysnake.cca.api.v8.level.LevelComponentFactoryRegistry;
import org.ladysnake.cca.api.v8.level.LevelComponentInitializer;
import org.spongepowered.asm.mixin.MixinEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class GetOffMyLawn implements ModInitializer, LevelComponentInitializer {
    public static final String MOD_ID = "goml";
    public static final String REPOSITORY = "pokeyt59/get-off-my-lawn-reserved";
    public static final String SOURCE_URL = "https://github.com/" + REPOSITORY;
    public static final ComponentKey<ClaimComponent> CLAIM = ComponentRegistryV3.INSTANCE.getOrCreate(id("claims"), ClaimComponent.class);
    public static final CreativeModeTab GROUP = CreativeModeTab.builder(null, -1)
            .title(Component.translatable("itemGroup.goml.group"))
            .icon(() -> new ItemStack(GOMLBlocks.WITHERED_CLAIM_ANCHOR.getSecond()))
            .displayItems((ctx, c) -> {
                GOMLBlocks.ANCHORS.forEach(c::accept);
                GOMLBlocks.AUGMENTS.forEach(c::accept);
                GOMLItems.BASE_ITEMS.forEach(c::accept);
            })
            .build();
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    public static GOMLConfig CONFIG = new GOMLConfig();

    public static List<Runnable> NEXT_TICK_TASK = new ArrayList<>();

    public static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath(MOD_ID, name);
    }

    @Override
    public void onInitialize() {
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            MixinEnvironment.getCurrentEnvironment().audit();
        }
        CardboardWarning.checkAndAnnounce();
        GOMLBlocks.init();
        GOMLItems.init();
        GOMLEntities.init();
        EventHandlers.init();
        ClaimCommand.init();
        PlaceholdersReg.init();

        PolymerCreativeModeTabUtils.registerPolymerCreativeModeTab(id("group"), GROUP);

        CommonProtection.register(Identifier.fromNamespaceAndPath(MOD_ID, "claim_protection"), GomlProtectionProvider.INSTANCE);

        ServerLifecycleEvents.SERVER_STARTING.register((s) -> {
            CardboardWarning.checkAndAnnounce();
            GetOffMyLawn.CONFIG = GOMLConfig.loadOrCreateConfig();
        });

        ServerTickEvents.END_LEVEL_TICK.register((world) -> CLAIM.get(world).getClaims().values().forEach(x -> x.tick(world)));
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            for (var task : NEXT_TICK_TASK) {
                task.run();
            }
            NEXT_TICK_TASK.clear();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(x -> NEXT_TICK_TASK.clear());

        VanillaTeamGroups.init();
        if (FabricLoader.getInstance().isModLoaded("argonauts")) {
            ArgonautsCompat.init();
        }

        ServerLifecycleEvents.SERVER_STARTED.register(WebmapCompat::init);
        ServerLifecycleEvents.SERVER_STARTED.register(BedrockCompat::init);
        UpdateChecker.init();
        ClaimMessages.init();

        ServerChunkEvents.CHUNK_LOAD.register((world, chunk, created) -> GetOffMyLawn.onChunkEvent(world, chunk, Claim::internal_incrementChunks));
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> GetOffMyLawn.onChunkEvent(world, chunk, Claim::internal_decrementChunks));
    }

    @Override
    public void registerLevelComponentFactories(LevelComponentFactoryRegistry registry) {
        registry.register(CLAIM, WorldClaimComponent::new);
    }

    private static void onChunkEvent(ServerLevel world, LevelChunk chunk, Consumer<Claim> chunkHandler) {
        // Matches claims where (x1 >> 4) <= chunkX <= (x2 >> 4) (same for z), like Claim#internal_updateChunkCount,
        // but lets the R-tree skip claims far away from the chunk.
        var minX = chunk.getPos().x() << 4;
        var minZ = chunk.getPos().z() << 4;
        var chunkBox = Box.create(minX, Integer.MIN_VALUE, minZ, minX + 15, Integer.MAX_VALUE, minZ + 15);

        ClaimUtils.getClaimsInOpenBox(world, chunkBox).forEach(x -> chunkHandler.accept(x.getValue()));
    }
}
