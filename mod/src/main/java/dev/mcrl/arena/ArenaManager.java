package dev.mcrl.arena;

import com.google.gson.JsonObject;
import dev.mcrl.EpisodeTracker;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.UnbreakableComponent;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;

import java.util.ArrayList;
import java.util.List;

/** Writes generated arenas into the world and reports server-side facts. Server thread only. */
public final class ArenaManager {
    /** Corner of the arena shell (local 0,0,0). */
    public static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    /** Server/client tick rate; 4 ticks per step = 40 ms of game time per step. */
    public static final float TICK_RATE = 100f;
    private static final int SET_FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;

    private final EpisodeTracker tracker;
    private final List<BlockPos> diamonds = new ArrayList<>();
    private MinecraftServer preparedFor;

    public ArenaManager(EpisodeTracker tracker) {
        this.tracker = tracker;
    }

    public void reset(MinecraftServer server, ServerPlayerEntity player, long seed, int stage) {
        if (player == null) throw new IllegalStateException("server player not found");
        ServerWorld world = player.getServerWorld();
        if (preparedFor != server) {
            prepareWorld(server, world);
            preparedFor = server;
        }
        ArenaLayout layout = ArenaGenerator.generate(seed, StageConfig.forStage(stage));
        clearRegion(world);
        placeLayout(world, layout);
        resetPlayer(player, ORIGIN.add(layout.spawnX(), layout.spawnY(), layout.spawnZ()));
        tracker.reset();
    }

    public JsonObject observe(MinecraftServer server, ServerPlayerEntity player) {
        if (player == null) throw new IllegalStateException("server player not found");
        ServerWorld world = player.getServerWorld();
        diamonds.removeIf(pos -> !isDiamond(world.getBlockState(pos)));
        boolean dead = tracker.isDead();

        JsonObject h = new JsonObject();
        h.addProperty("health", dead ? 0f : player.getHealth());
        h.addProperty("food", player.getHungerManager().getFoodLevel());
        h.addProperty("on_fire", player.isOnFire());
        h.addProperty("y", player.getY());
        h.addProperty("yaw", player.getYaw());
        h.addProperty("pitch", player.getPitch());
        h.addProperty("nearest_diamond_dist", nearestDiamond(player.getEyePos()));
        h.addProperty("diamonds_remaining", diamonds.size());
        h.add("events", tracker.drainEvents());
        h.addProperty("dead", dead);
        h.addProperty("tick", server.getTicks());
        return h;
    }

    private void prepareWorld(MinecraftServer server, ServerWorld world) {
        GameRules rules = server.getGameRules();
        rules.get(GameRules.DO_MOB_SPAWNING).set(false, server);
        rules.get(GameRules.DO_DAYLIGHT_CYCLE).set(false, server);
        rules.get(GameRules.DO_WEATHER_CYCLE).set(false, server);
        rules.get(GameRules.DO_TILE_DROPS).set(false, server);
        world.setTimeOfDay(6000);
        world.setWeather(1_000_000, 0, false, false);
        server.getTickManager().setTickRate(TICK_RATE);
        server.getTickManager().setFrozen(true);
    }

    private void clearRegion(ServerWorld world) {
        StageConfig big = StageConfig.largest();
        BlockState air = Blocks.AIR.getDefaultState();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int y = 0; y < big.height(); y++)
            for (int z = 0; z < big.depth(); z++)
                for (int x = 0; x < big.width(); x++)
                    world.setBlockState(pos.set(ORIGIN.getX() + x, ORIGIN.getY() + y, ORIGIN.getZ() + z), air, SET_FLAGS);
    }

    private void placeLayout(ServerWorld world, ArenaLayout layout) {
        diamonds.clear();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int y = 0; y < layout.height(); y++)
            for (int z = 0; z < layout.depth(); z++)
                for (int x = 0; x < layout.width(); x++) {
                    Cell cell = layout.get(x, y, z);
                    if (cell == Cell.AIR) continue; // region already cleared
                    pos.set(ORIGIN.getX() + x, ORIGIN.getY() + y, ORIGIN.getZ() + z);
                    world.setBlockState(pos, toState(cell, layout.isDeep(y)), SET_FLAGS);
                    if (cell == Cell.DIAMOND) diamonds.add(pos.toImmutable());
                }
    }

    static BlockState toState(Cell cell, boolean deep) {
        Block block = switch (cell) {
            case AIR -> Blocks.AIR;
            case BEDROCK -> Blocks.BEDROCK;
            case STONE -> deep ? Blocks.DEEPSLATE : Blocks.STONE;
            case COAL -> deep ? Blocks.DEEPSLATE_COAL_ORE : Blocks.COAL_ORE;
            case IRON -> deep ? Blocks.DEEPSLATE_IRON_ORE : Blocks.IRON_ORE;
            case GRAVEL -> Blocks.GRAVEL;
            case DIAMOND -> deep ? Blocks.DEEPSLATE_DIAMOND_ORE : Blocks.DIAMOND_ORE;
            case LAVA -> Blocks.LAVA;
        };
        return block.getDefaultState();
    }

    private void resetPlayer(ServerPlayerEntity player, BlockPos feet) {
        player.changeGameMode(GameMode.SURVIVAL);
        player.clearStatusEffects();
        // Without night vision, dug tunnels render black and the pixels carry no information.
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION,
                StatusEffectInstance.INFINITE, 0, false, false));
        player.setHealth(player.getMaxHealth());
        HungerManager hunger = player.getHungerManager();
        hunger.setFoodLevel(20);
        hunger.setSaturationLevel(5.0f);
        player.setFireTicks(0);
        player.extinguish();
        player.fallDistance = 0;
        player.setVelocity(Vec3d.ZERO);

        player.getInventory().clear();
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        pickaxe.set(DataComponentTypes.UNBREAKABLE, new UnbreakableComponent(false));
        player.getInventory().setStack(0, pickaxe);
        player.getInventory().selectedSlot = 0;
        player.networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(0));

        player.networkHandler.requestTeleport(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0f, 30f);
    }

    private double nearestDiamond(Vec3d eye) {
        double best = -1;
        for (BlockPos pos : diamonds) {
            double d = Vec3d.ofCenter(pos).distanceTo(eye);
            if (best < 0 || d < best) best = d;
        }
        return best;
    }

    private static boolean isDiamond(BlockState state) {
        return state.isOf(Blocks.DIAMOND_ORE) || state.isOf(Blocks.DEEPSLATE_DIAMOND_ORE);
    }
}
