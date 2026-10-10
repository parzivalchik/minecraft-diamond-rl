package dev.mcrl.arena;

import dev.mcrl.EpisodeTracker;
import net.minecraft.block.BlockState;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.EnumSet;

/**
 * World side of action 12, tunnel_forward (spec §5). Server thread only.
 *
 * <p>Breaks the blocks in front of the player at foot and head level (no drops) and reports how long
 * the step must last: the vanilla survival break time of each broken block with the held tool, plus
 * {@link TunnelPlan#WALK_TICKS} of walking (skipped when lava is exposed). Breaks are instant on the server; the step's tick count is
 * what charges the agent for them.
 */
public final class Tunneler {
    private Tunneler() {}

    /**
     * @param cardinalYaw the yaw the client already snapped the player to (a multiple of 90)
     * @param minTicks    the normal step length; the step is never shorter
     */
    public static TunnelPlan dig(ServerPlayerEntity player, float cardinalYaw, EpisodeTracker tracker, int minTicks) {
        ServerWorld world = player.getServerWorld();
        int quarter = TunnelPlan.quarter(cardinalYaw);
        BlockPos front = player.getBlockPos().add(TunnelPlan.offsetX(quarter), 0, TunnelPlan.offsetZ(quarter));
        BlockPos[] targets = {front.up(), front}; // head, feet

        int breakTicks = 0;
        for (BlockPos pos : targets) {
            BlockState state = world.getBlockState(pos);
            if (state.getHardness(world, pos) < 0) return TunnelPlan.blocked(minTicks); // bedrock: no-op
            if (!isBreakable(state)) continue;
            float delta = state.calcBlockBreakingDelta(player, world, pos);
            if (!(delta > 0f)) return TunnelPlan.blocked(minTicks); // cannot make progress (e.g. mining fatigue)
            breakTicks += TunnelPlan.breakTicks(delta);
        }

        for (BlockPos pos : targets) {
            BlockState state = world.getBlockState(pos);
            if (!isBreakable(state)) continue;
            String id = Registries.BLOCK.getId(state.getBlock()).toString();
            // world.breakBlock does not fire Fabric's PlayerBlockBreakEvents, so record the event here, once.
            if (world.breakBlock(pos, false, player)) tracker.onBlockBroken(id);
        }
        centerInColumn(player, quarter, cardinalYaw);
        // Lava safety: never walk blind into lava the dig just exposed. Walking into it is still
        // possible with the plain movement actions, so the agent still has to learn to avoid it.
        if (lavaInOrNextTo(world, targets)) return TunnelPlan.halt(minTicks, breakTicks);
        return TunnelPlan.dig(minTicks, breakTicks);
    }

    /** True if lava is in a target cell or in any of its six neighbours (it could flow into the opening). */
    private static boolean lavaInOrNextTo(ServerWorld world, BlockPos[] targets) {
        for (BlockPos pos : targets) {
            if (isLava(world, pos)) return true;
            for (Direction dir : Direction.values()) {
                if (isLava(world, pos.offset(dir))) return true;
            }
        }
        return false;
    }

    private static boolean isLava(ServerWorld world, BlockPos pos) {
        return world.getFluidState(pos).isIn(FluidTags.LAVA);
    }

    /** Solid blocks are broken; air and fluids (lava, water) are passable and left alone. */
    private static boolean isBreakable(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    /**
     * Moves the player sideways to the centre of its block column (and sets the cardinal yaw) so the
     * 0.6-wide hitbox fits the 1-wide tunnel; otherwise an off-centre player walks into the tunnel's
     * side wall. The forward axis, height and pitch are sent as relative zero deltas, so whatever the
     * client is doing along them is untouched.
     */
    private static void centerInColumn(ServerPlayerEntity player, int quarter, float cardinalYaw) {
        boolean alongZ = TunnelPlan.offsetX(quarter) == 0;
        double x = alongZ ? TunnelPlan.columnCenter(player.getX()) : player.getX();
        double z = alongZ ? player.getZ() : TunnelPlan.columnCenter(player.getZ());
        if (Math.abs(x - player.getX()) < 1e-4 && Math.abs(z - player.getZ()) < 1e-4) return;
        EnumSet<PositionFlag> relative = EnumSet.of(PositionFlag.Y, PositionFlag.X_ROT,
                alongZ ? PositionFlag.Z : PositionFlag.X);
        player.networkHandler.requestTeleport(x, player.getY(), z, cardinalYaw, player.getPitch(), relative);
    }
}
