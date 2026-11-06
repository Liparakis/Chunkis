package io.liparakis.chunkis.portal;

import java.util.ArrayDeque;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Arrival-tile and portal-egress search helpers for fallback portal travel.
 *
 * <p>This class owns only the "where can an entity safely stand" logic. Portal
 * footprint validation, frame construction, and teleport policy stay in
 * {@link PortalArrivalFallback}.</p>
 */
final class PortalArrivalSpots {

    /**
     * Portal interior width constant.
     */
    private static final int PORTAL_INTERIOR_WIDTH = 2;

    /**
     * Egress search radius in blocks.
     */
    private static final int EGRESS_SEARCH_RADIUS = 2;

    /**
     * Egress BFS lookup radius.
     */
    private static final int EGRESS_BFS_RADIUS = 2;

    /**
     * Minimum BFS count to consider a side viable.
     */
    private static final int EGRESS_MIN_BFS_COUNT = 6;

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private PortalArrivalSpots() {
        throw new AssertionError("Utility class");
    }

    /**
     * Searches both sides of a portal face for the best validated egress position.
     *
     * @param world  target world context
     * @param lowerX lower bounds X coordinate
     * @param lowerY lower bounds Y coordinate
     * @param lowerZ lower bounds Z coordinate
     * @param axis   portal Axis alignment
     * @param target original destination target pos
     * @return the better natural egress result, or {@code null} if neither side qualifies
     */
    static @Nullable EgressResult findBestEgressPosition(
            final ServerLevel world,
            final int lowerX,
            final int lowerY,
            final int lowerZ,
            final Direction.Axis axis,
            final BlockPos target
    ) {
        final Direction forward = egressForward(axis);
        final EgressResult front = findBestEgressSide(
                world,
                lowerX,
                lowerY,
                lowerZ,
                axis,
                forward,
                target
        );
        final EgressResult back = findBestEgressSide(
                world,
                lowerX,
                lowerY,
                lowerZ,
                axis,
                forward.getOpposite(),
                target
        );

        final EgressResult better = chooseBetterEgress(front, back);
        if (better == null || better.bfsCount() < EGRESS_MIN_BFS_COUNT) {
            return null;
        }
        return better;
    }

    /**
     * Builds a synthetic egress when no natural side has enough walkable space.
     *
     * @param lowerX lower bounds X coordinate
     * @param lowerY lower bounds Y coordinate
     * @param lowerZ lower bounds Z coordinate
     * @param axis   portal Axis alignment
     * @param target original destination target pos
     * @return constructed EgressResult fallback mapping coordinates
     */
    static EgressResult syntheticEgress(
            final int lowerX,
            final int lowerY,
            final int lowerZ,
            final Direction.Axis axis,
            final BlockPos target
    ) {
        final Direction normal = choosePreferredArrivalSide(axis, target, lowerX, lowerZ);
        final double centerX = lowerX + 0.5 + (axis == Direction.Axis.Z ? 0.5 : 0.0) + normal.getStepX();
        final double centerZ = lowerZ + 0.5 + (axis == Direction.Axis.X ? 0.5 : 0.0) + normal.getStepZ();
        final Vec3 arrival = new Vec3(centerX, lowerY, centerZ);
        final BlockPos arrivalPos = BlockPos.containing(arrival);
        final long distanceScore = egressDistanceScore(
                target,
                arrivalPos.getX(),
                lowerY,
                arrivalPos.getZ()
        );
        return new EgressResult(arrival, 0, distanceScore, 0);
    }

    /**
     * Returns the center of a valid two-block-high standing tile, or {@code null} when blocked.
     *
     * @param world target world context
     * @param x     X coordinate
     * @param y     Y coordinate
     * @param z     Z coordinate
     * @return safe standing spot coordinates vector, or null
     */
    static @Nullable Vec3 findStandingSpot(
            final ServerLevel world,
            final int x,
            final int y,
            final int z
    ) {
        final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        mutable.set(x, y, z);
        if (isImpassable(world.getBlockState(mutable), world.getBlockEntity(mutable))
                || !world.getFluidState(mutable)
                .isEmpty()) {
            return null;
        }

        mutable.set(x, y + 1, z);
        if (isImpassable(world.getBlockState(mutable), world.getBlockEntity(mutable))
                || !world.getFluidState(mutable)
                .isEmpty()) {
            return null;
        }

        mutable.set(x, y - 1, z);
        if (!world.getBlockState(mutable)
                .isFaceSturdy(world, mutable, Direction.UP)) {
            return null;
        }

        final Vec3 arrival = new Vec3(x + 0.5, y, z + 0.5);
        return isSafeArrivalPosition(world, arrival) ? arrival : null;
    }

    /**
     * Searches a single side of the portal face for safely walkable egress candidates.
     *
     * @param world  target world context
     * @param lowerX lower bounds X coordinate
     * @param lowerY lower bounds Y coordinate
     * @param lowerZ lower bounds Z coordinate
     * @param axis   portal axis alignment
     * @param normal normal offset direction to check
     * @param target original destination target pos
     * @return constructed EgressResult, or null
     */
    private static @Nullable EgressResult findBestEgressSide(
            final ServerLevel world,
            final int lowerX,
            final int lowerY,
            final int lowerZ,
            final Direction.Axis axis,
            final Direction normal,
            final BlockPos target
    ) {
        final Direction widthDirection = widthDirection(axis);

        Vec3 bestArrival = null;
        long bestDistanceScore = Long.MAX_VALUE;
        int bestBfs = 0;
        int walkableCount = 0;

        for (int step = 1; step <= EGRESS_SEARCH_RADIUS; step++) {
            for (int width = 0; width < PORTAL_INTERIOR_WIDTH; width++) {
                final int x = lowerX + widthDirection.getStepX() * width + normal.getStepX() * step;
                final int z = lowerZ + widthDirection.getStepZ() * width + normal.getStepZ() * step;
                final Vec3 arrival = findStandingSpot(world, x, lowerY, z);
                if (arrival == null) {
                    continue;
                }

                final int bfs = countConnectedWalkableTiles(world, x, lowerY, z);
                if (bfs < EGRESS_MIN_BFS_COUNT) {
                    continue;
                }

                walkableCount++;
                final long distanceScore = egressDistanceScore(target, x, lowerY, z);
                if (bfs > bestBfs || (bfs == bestBfs && distanceScore < bestDistanceScore)) {
                    bestArrival = arrival;
                    bestDistanceScore = distanceScore;
                    bestBfs = bfs;
                }
            }
        }

        return bestArrival == null ? null : new EgressResult(bestArrival, walkableCount, bestDistanceScore, bestBfs);
    }

    /**
     * Resolves the normal direction yielding the lower path distance score to the target coordinates.
     *
     * @param axis   portal axis alignment
     * @param target original destination target pos
     * @param lowerX lower bounds X coordinate
     * @param lowerZ lower bounds Z coordinate
     * @return resolved preferred direction
     */
    private static Direction choosePreferredArrivalSide(
            final Direction.Axis axis,
            final BlockPos target,
            final int lowerX,
            final int lowerZ
    ) {
        final Direction forward = egressForward(axis);
        final Direction backward = forward.getOpposite();

        final long forwardScore = egressDistanceScore(
                target,
                lowerX + forward.getStepX(),
                target.getY(),
                lowerZ + forward.getStepZ()
        );
        final long backwardScore = egressDistanceScore(
                target,
                lowerX + backward.getStepX(),
                target.getY(),
                lowerZ + backward.getStepZ()
        );

        return forwardScore <= backwardScore ? forward : backward;
    }

    /**
     * Evaluates two Egress results choosing the better candidate.
     *
     * @param first  first candidate EgressResult
     * @param second second candidate EgressResult
     * @return chosen EgressResult
     */
    private static @Nullable EgressResult chooseBetterEgress(
            final @Nullable EgressResult first,
            final @Nullable EgressResult second
    ) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        if (first.walkableCount() != second.walkableCount()) {
            return first.walkableCount() > second.walkableCount() ? first : second;
        }
        return first.distanceScore() <= second.distanceScore() ? first : second;
    }

    /**
     * Validates that arrival position coordinates does not expose entity to hazards (e.g. lava, drowning).
     *
     * @param world target world context
     * @param pos   feet coordinates position
     * @return true if position is safe
     */
    private static boolean isSafeArrivalPosition(final ServerLevel world, final Vec3 pos) {
        final BlockPos feet = BlockPos.containing(pos);
        final BlockPos head = feet.above();
        return world.getFluidState(feet)
                .isEmpty()
                && world.getFluidState(head)
                .isEmpty()
                && !world.getBlockState(feet)
                .is(Blocks.LAVA)
                && !world.getBlockState(head)
                .is(Blocks.LAVA);
    }

    /**
     * Checks if a block state or block entity prevents entity standing.
     *
     * @param state       block state to query
     * @param blockEntity block entity instance, if any, may be null
     * @return true if blocked
     */
    private static boolean isImpassable(final BlockState state, final BlockEntity blockEntity) {
        return blockEntity != null
                || (!state.isAir()
                && !state.canBeReplaced()
                && !state.is(Blocks.FIRE)
                && !state.is(Blocks.NETHER_PORTAL));
    }

    /**
     * Computes a distance score prioritizing horizontal alignment over vertical alignment.
     *
     * @param target original target coordinates
     * @param x      current X coordinate
     * @param y      current Y coordinate
     * @param z      current Z coordinate
     * @return computed distance score metric
     */
    private static long egressDistanceScore(final BlockPos target, final int x, final int y, final int z) {
        final long dx = x - (long) target.getX();
        final long dz = z - (long) target.getZ();
        final long dy = Math.abs(y - target.getY());
        return dx * dx + dz * dz + dy * 4L;
    }

    /**
     * Counts horizontally connected safely walkable tiles from a start position using BFS.
     *
     * @param world  target world context
     * @param startX start X coordinate
     * @param startY start Y coordinate
     * @param startZ start Z coordinate
     * @return count of connected walkable tiles
     */
    private static int countConnectedWalkableTiles(
            final ServerLevel world,
            final int startX,
            final int startY,
            final int startZ
    ) {
        final int maxTiles = (2 * EGRESS_BFS_RADIUS + 1) * (2 * EGRESS_BFS_RADIUS + 1);
        final ArrayDeque<Long> queue = new ArrayDeque<>(maxTiles);
        final HashSet<Long> visited = new HashSet<>(maxTiles);

        final long startKey = BlockPos.asLong(startX, startY, startZ);
        queue.add(startKey);
        visited.add(startKey);

        int count = 0;
        while (!queue.isEmpty()) {
            final long key = queue.poll();
            final int x = BlockPos.getX(key);
            final int z = BlockPos.getZ(key);
            count++;

            for (final Direction direction : Direction.Plane.HORIZONTAL) {
                final int nextX = x + direction.getStepX();
                final int nextZ = z + direction.getStepZ();
                if (Math.abs(nextX - startX) > EGRESS_BFS_RADIUS
                        || Math.abs(nextZ - startZ) > EGRESS_BFS_RADIUS) {
                    continue;
                }

                final long nextKey = BlockPos.asLong(nextX, startY, nextZ);
                if (!visited.add(nextKey)) {
                    continue;
                }
                if (findStandingSpot(world, nextX, startY, nextZ) != null) {
                    queue.add(nextKey);
                }
            }
        }

        return count;
    }

    /**
     * Resolves the width-facing direction matching portal Axis.
     *
     * @param axis portal Axis alignment
     * @return horizontal offset direction representing portal width direction
     */
    private static Direction widthDirection(final Direction.Axis axis) {
        return axis == Direction.Axis.X ? Direction.WEST : Direction.SOUTH;
    }

    /**
     * Resolves the forward-egress offset direction perpendicular to the portal face.
     *
     * @param axis portal Axis alignment
     * @return perpendicular egress direction
     */
    private static Direction egressForward(final Direction.Axis axis) {
        return axis == Direction.Axis.X ? Direction.NORTH : Direction.EAST;
    }

    /**
     * Result of an egress search on one side of a portal face.
     *
     * @param arrival       vector position coordinates
     * @param walkableCount total count of walkable coordinates adjacent
     * @param distanceScore calculated distance score
     * @param bfsCount      BFS evaluation count
     */
    record EgressResult(Vec3 arrival, int walkableCount, long distanceScore, int bfsCount) {

    }
}
