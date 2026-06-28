package io.liparakis.chunkis.portal;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
import net.minecraft.world.dimension.NetherPortal;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestType;
import net.minecraft.world.poi.PointOfInterestTypes;

import java.util.Set;

/**
 * Controlled fallback for Nether portal travel when vanilla cannot link to an existing destination
 * portal. Searches near the intended destination for a safe, bounded arrival. A portal is built
 * only when the full frame footprint is validated as non-destructive, and the teleport target is
 * set to a validated walkable egress tile beside it.
 *
 * <p>Fallback order: safe portal frame → validated egress tile → safe standing position →
 * emergency surface arrival.
 *
 * @author Liparakis
 */
public final class PortalArrivalFallback {

    /**
     * Max height for portal creation in the Nether to avoid placing portals above the ceiling.
     */
    private static final int NETHER_MAX_CREATED_PORTAL_Y = 127;
    /**
     * Width of the portal interior in blocks.
     */
    private static final int PORTAL_INTERIOR_WIDTH = 2;
    /**
     * Height of the portal interior in blocks.
     */
    private static final int PORTAL_INTERIOR_HEIGHT = 3;
    /**
     * Horizontal radius for searching portal placement sites.
     */
    private static final int PORTAL_SEARCH_RADIUS =
            Integer.getInteger("chunkis.portal.fallbackRadius", 20);
    /**
     * Vertical range around the target height to scan for portal sites.
     */
    private static final int PORTAL_VERTICAL_RANGE =
            Integer.getInteger("chunkis.portal.fallbackVerticalRange", 24);
    /**
     * Horizontal radius for searching safe standing spots when portal placement fails.
     */
    private static final int SAFE_SPOT_SEARCH_RADIUS =
            Integer.getInteger("chunkis.portal.safeSpotRadius", 20);
    /**
     * Ticks of portal cooldown applied after a fallback teleport.
     */
    private static final int FALLBACK_PORTAL_COOLDOWN_TICKS =
            Integer.getInteger("chunkis.portal.fallbackCooldownTicks", 40);

    /**
     * Steps outward from the portal face to search for a valid egress tile.
     */

    /**
     * BFS radius used when counting connected walkable tiles from a candidate egress position.
     * A fixed radius of 2 gives a 5×5 tile budget, sufficient to distinguish open areas from
     * narrow ledges without excessive world queries.
     */

    /**
     * Minimum connected walkable tiles required to accept an egress position.
     */

    /**
     * Default state for obsidian blocks used in portal frames.
     */
    private static final BlockState OBSIDIAN_STATE = Blocks.OBSIDIAN.getDefaultState();

    private PortalArrivalFallback() {
        throw new AssertionError("Utility class");
    }

    /**
     * Computes a controlled teleport target when vanilla cannot link to an existing destination
     * portal. Attempts portal site placement first, then safe standing fallback, then emergency
     * surface arrival.
     *
     * @param destinationWorld     target world
     * @param entity               teleporting entity
     * @param sourceWorld          world the entity is leaving
     * @param sourcePortalPos      source portal position
     * @param scaledDestinationPos vanilla-scaled destination position
     * @return controlled teleport target
     */
    public static TeleportTarget create(
            final ServerWorld destinationWorld,
            final Entity entity,
            final ServerWorld sourceWorld,
            final BlockPos sourcePortalPos,
            final BlockPos scaledDestinationPos
    ) {
        final Direction.Axis axis = resolvePortalAxis(
                sourceWorld.getBlockState(sourcePortalPos));

        final PortalSite portalSite = findPortalSite(destinationWorld, scaledDestinationPos, axis);

        if (portalSite != null) {
            buildPortal(destinationWorld, portalSite.lowerCorner(), portalSite.axis());
            PortalLinkManager.registerBidirectional(
                    sourceWorld,
                    sourcePortalPos,
                    destinationWorld,
                    portalSite.lowerCorner(),
                    portalSite.axis()
            );
            return createTeleportTarget(destinationWorld, entity, portalSite.arrival(), true);
        }

        final Vec3d safeArrival = findSafeArrival(destinationWorld, scaledDestinationPos);

        if (safeArrival != null) {
            return createTeleportTarget(destinationWorld, entity, safeArrival, false);
        }

        return createTeleportTarget(
                destinationWorld,
                entity,
                emergencyArrival(destinationWorld, entity, scaledDestinationPos),
                false
        );
    }

    /**
     * Builds a teleport target that arrives at a linked portal whose lower-corner and axis are
     * already known, without triggering portal creation.
     *
     * @param world       destination world
     * @param entity      teleporting entity
     * @param lowerCorner bottom-left interior corner of the destination portal
     * @param axis        orientation axis of the destination portal
     * @return teleport target positioned beside the linked portal
     */
    public static TeleportTarget createLinkedTeleportTarget(
            final ServerWorld world,
            final Entity entity,
            final BlockPos lowerCorner,
            final Direction.Axis axis
    ) {
        final Vec3d arrival = findLinkedPortalArrival(world, entity, lowerCorner, axis);
        return new TeleportTarget(
                world,
                arrival,
                entity.getVelocity(),
                entity.getYaw(),
                entity.getPitch(),
                Set.of(),
                TeleportTarget.SEND_TRAVEL_THROUGH_PORTAL_PACKET.then(TeleportTarget.ADD_PORTAL_CHUNK_TICKET)
        );
    }

    /**
     * Resolves the horizontal axis of a nether portal block.
     *
     * @param sourcePortalState state of the portal block
     * @return the resolved axis, defaulting to X if missing
     */
    private static Direction.Axis resolvePortalAxis(final BlockState sourcePortalState) {
        return sourcePortalState
                .getOrEmpty(Properties.HORIZONTAL_AXIS)
                .orElse(Direction.Axis.X);
    }

    /**
     * Visits every column in an outward square-ring search, computing {@code startY} (clamped
     * target height) and {@code surfaceY} (motion-blocking surface) for each. The visitor receives
     * both Y values and returns {@code true} to stop the search early.
     */
    @FunctionalInterface
    private interface ColumnVisitor {
        boolean visit(int worldX, int worldZ, int startY, int surfaceY);
    }

    /**
     * Iterates through columns in an expanding square ring pattern around a target position.
     *
     * @param world        destination world
     * @param target       center of the search
     * @param searchRadius max horizontal distance to search
     * @param minY         lower height bound for sampling
     * @param maxY         upper height bound for sampling
     * @param visitor      callback invoked for each column; returns true to abort search
     */
    private static void searchColumns(
            final ServerWorld world,
            final BlockPos target,
            final int searchRadius,
            final int minY,
            final int maxY,
            final ColumnVisitor visitor
    ) {
        for (int radius = 0; radius <= searchRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (isNotOnSearchRing(dx, dz, radius)) {
                        continue;
                    }

                    final int worldX = target.getX() + dx;
                    final int worldZ = target.getZ() + dz;

                    forceLoadColumn(world, worldX, worldZ);

                    final int surfaceY = MathHelper.clamp(
                            world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ),
                            minY, maxY
                    );
                    final int startY = MathHelper.clamp(target.getY(), minY, surfaceY);

                    if (visitor.visit(worldX, worldZ, startY, surfaceY)) {
                        return;
                    }
                }
            }
        }
    }

    /**
     * Searches the destination area for the best candidate site to place a new portal.
     *
     * <p>Each column is sampled at the clamped target Y, the surface Y, and within
     * {@value #PORTAL_VERTICAL_RANGE} blocks vertically of the target Y. The lowest-scoring
     * valid site is returned.
     *
     * @param world  destination world
     * @param target vanilla-scaled arrival position
     * @param axis   preferred portal orientation axis
     * @return the best valid site, or {@code null} if none was found
     */
    private static PortalSite findPortalSite(
            final ServerWorld world,
            final BlockPos target,
            final Direction.Axis axis
    ) {
        final int minY = world.getBottomY() + 1;
        final int maxY = worldCeilingY(world) - PORTAL_INTERIOR_HEIGHT - 1;
        final PortalSite[] best = { null };

        searchColumns(
                world, target, PORTAL_SEARCH_RADIUS, minY, maxY, (worldX, worldZ, startY, surfaceY) -> {
                    best[0] = chooseBetter(
                            best[0],
                            validatePortalSiteBothAxes(world, target, worldX, startY, worldZ, axis)
                    );

                    if (surfaceY != startY) {
                        best[0] = chooseBetter(
                                best[0],
                                validatePortalSiteBothAxes(world, target, worldX, surfaceY, worldZ, axis)
                        );
                    }

                    best[0] = searchVerticalPortalSites(
                            world, target, worldX, startY, worldZ, minY, maxY, axis, best[0]);

                    return false;
                }
        );

        return best[0];
    }

    /**
     * Scans upward and downward from {@code startY} within {@value #PORTAL_VERTICAL_RANGE} blocks,
     * keeping the best-scoring site found.
     */
    private static PortalSite searchVerticalPortalSites(
            final ServerWorld world,
            final BlockPos target,
            final int x,
            final int startY,
            final int z,
            final int minY,
            final int maxY,
            final Direction.Axis axis,
            PortalSite bestSite
    ) {
        for (int deltaY = 1; deltaY <= PORTAL_VERTICAL_RANGE; deltaY++) {
            final int upwardY = startY + deltaY;
            if (upwardY <= maxY) {
                bestSite = chooseBetter(
                        bestSite,
                        validatePortalSiteBothAxes(world, target, x, upwardY, z, axis)
                );
            }

            final int downwardY = startY - deltaY;
            if (downwardY >= minY) {
                bestSite = chooseBetter(
                        bestSite,
                        validatePortalSiteBothAxes(world, target, x, downwardY, z, axis)
                );
            }
        }

        return bestSite;
    }

    /**
     * Tries both portal axes at a candidate position and returns the better-scoring site.
     * The preferred axis wins on a score tie.
     */
    private static PortalSite validatePortalSiteBothAxes(
            final ServerWorld world,
            final BlockPos target,
            final int lowerX,
            final int lowerY,
            final int lowerZ,
            final Direction.Axis preferredAxis
    ) {
        final Direction.Axis otherAxis = preferredAxis == Direction.Axis.X
                ? Direction.Axis.Z
                : Direction.Axis.X;

        return chooseBetter(
                validatePortalSite(world, target, lowerX, lowerY, lowerZ, preferredAxis),
                validatePortalSite(world, target, lowerX, lowerY, lowerZ, otherAxis)
        );
    }

    /**
     * Validates a candidate portal footprint. The frame must be non-destructive.
     *
     * <p>Natural walkable egress is preferred when available, but not required.
     * Fallback portal creation is allowed to build its own landing support so
     * high Nether placements remain valid.
     *
     * @param world  destination world
     * @param target vanilla-scaled arrival position used for scoring
     * @param lowerX X coordinate of the bottom-left interior corner
     * @param lowerY Y coordinate of the bottom-left interior corner
     * @param lowerZ Z coordinate of the bottom-left interior corner
     * @param axis   portal orientation axis to validate
     * @return a scored portal site, or {@code null} if the footprint is unsafe
     */
    private static PortalSite validatePortalSite(
            final ServerWorld world,
            final BlockPos target,
            final int lowerX,
            final int lowerY,
            final int lowerZ,
            final Direction.Axis axis
    ) {
        final Direction widthDirection = widthDirection(axis);

        if (!isPortalFootprintSafe(world, lowerX, lowerY, lowerZ, widthDirection)) {
            return null;
        }

        final PortalArrivalSpots.EgressResult egress = PortalArrivalSpots.findBestEgressPosition(
                world, lowerX, lowerY, lowerZ, axis, target);

        final int supportScore = countSupportBelowPortal(world, lowerX, lowerY, lowerZ, widthDirection);

        final PortalArrivalSpots.EgressResult effectiveEgress = egress != null
                ? egress
                : PortalArrivalSpots.syntheticEgress(lowerX, lowerY, lowerZ, axis, target);

        return new PortalSite(
                new BlockPos(lowerX, lowerY, lowerZ),
                effectiveEgress.arrival(),
                scoreCandidate(
                        target,
                        lowerX,
                        lowerY,
                        lowerZ,
                        supportScore,
                        effectiveEgress.walkableCount(),
                        effectiveEgress.bfsCount()
                ),
                axis
        );
    }

    /**
     * Returns {@code true} if every block in the proposed portal frame and interior can be
     * safely overwritten (i.e. is not bedrock, reinforced deepslate, or a block entity).
     */
    private static boolean isPortalFootprintSafe(
            final ServerWorld world,
            final int lowerX,
            final int lowerY,
            final int lowerZ,
            final Direction widthDirection
    ) {
        final BlockPos.Mutable mutable = new BlockPos.Mutable();

        for (int width = -1; width <= PORTAL_INTERIOR_WIDTH; width++) {
            for (int height = -1; height <= PORTAL_INTERIOR_HEIGHT; height++) {
                mutable.set(
                        lowerX + widthDirection.getOffsetX() * width,
                        lowerY + height,
                        lowerZ + widthDirection.getOffsetZ() * width
                );

                if (isUnsafeToReplace(world.getBlockState(mutable), world.getBlockEntity(mutable))) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Determines if a coordinate within the 4x5 portal bounds (including frame) is on the boundary.
     *
     * @param width  horizontal offset from lower corner ({@code -1} to {@code PORTAL_INTERIOR_WIDTH})
     * @param height vertical offset from lower corner ({@code -1} to {@code PORTAL_INTERIOR_HEIGHT})
     * @return {@code true} if the coordinate is part of the obsidian frame
     */
    private static boolean isPortalBoundary(final int width, final int height) {
        return width == -1 || width == PORTAL_INTERIOR_WIDTH
                || height == -1 || height == PORTAL_INTERIOR_HEIGHT;
    }

    /**
     * Returns {@code true} if a block is considered indestructible by portal generation.
     * Only bedrock and reinforced deepslate cannot be overwritten by a portal frame.
     *
     * @param state       block state to check
     * @param blockEntity block entity at the position, if any
     * @return {@code true} if the block cannot be replaced
     */
    private static boolean isUnsafeToReplace(final BlockState state, final BlockEntity blockEntity) {
        return blockEntity != null
                || state.isOf(Blocks.BEDROCK)
                || state.isOf(Blocks.REINFORCED_DEEPSLATE);
    }

    /**
     * Searches the destination area for any safe two-block-high standing position, without
     * building a portal.
     *
     * @param world  destination world
     * @param target vanilla-scaled arrival position
     * @return a valid standing position, or {@code null} if none was found
     */
    private static Vec3d findSafeArrival(
            final ServerWorld world,
            final BlockPos target
    ) {
        final int minY = world.getBottomY() + 1;
        final int maxY = worldCeilingY(world) - 2;
        final Vec3d[] result = { null };

        searchColumns(
                world, target, SAFE_SPOT_SEARCH_RADIUS, minY, maxY, (worldX, worldZ, startY, surfaceY) -> {
                    result[0] = PortalArrivalSpots.findStandingSpot(world, worldX, startY, worldZ);
                    if (result[0] != null) {
                        return true;
                    }

                    if (surfaceY != startY) {
                        result[0] = PortalArrivalSpots.findStandingSpot(world, worldX, surfaceY, worldZ);
                        return result[0] != null;
                    }

                    return false;
                }
        );

        return result[0];
    }

    
    /**
     * Strict passability check for egress tiles — the player must physically fit here.
     * Checks if a block state prevents an entity from standing in its space.
     *
     * @param state       block state to check
     * @param blockEntity block entity at the position, if any
     * @return {@code true} if impassable
     */
    /**
     * Last-resort arrival at the motion-blocking surface above the target column.
     * Delegates final position refinement to {@link NetherPortal#findOpenPosition}.
     */
    private static Vec3d emergencyArrival(
            final ServerWorld world,
            final Entity entity,
            final BlockPos target
    ) {
        final int topY = MathHelper.clamp(
                world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, target.getX(), target.getZ()),
                world.getBottomY() + 1,
                worldCeilingY(world) - 2
        );

        final Vec3d anchor = new Vec3d(target.getX() + 0.5, topY, target.getZ() + 0.5);
        return NetherPortal.findOpenPosition(anchor, world, entity, entity.getDimensions(entity.getPose()));
    }

    /**
     * Creates a teleport target with appropriate packets and cooldowns.
     *
     * @param world       destination world
     * @param entity      teleporting entity
     * @param position    arrival position
     * @param builtPortal whether a new portal was constructed at the arrival
     * @return teleport target
     */
    private static TeleportTarget createTeleportTarget(
            final ServerWorld world,
            final Entity entity,
            final Vec3d position,
            final boolean builtPortal
    ) {
        final TeleportTarget.PostDimensionTransition cooldown =
                teleported -> teleported.setPortalCooldown(FALLBACK_PORTAL_COOLDOWN_TICKS);

        final TeleportTarget.PostDimensionTransition transition = builtPortal
                ? TeleportTarget.SEND_TRAVEL_THROUGH_PORTAL_PACKET.then(TeleportTarget.ADD_PORTAL_CHUNK_TICKET)
                : TeleportTarget.SEND_TRAVEL_THROUGH_PORTAL_PACKET;

        return new TeleportTarget(
                world, position, entity.getVelocity(),
                entity.getYaw(), entity.getPitch(),
                Set.of(), transition.then(cooldown)
        );
    }

    /**
     * Finds an open standing position near the center of a linked portal.
     *
     * @param world       destination world
     * @param entity      teleporting entity
     * @param lowerCorner bottom-left interior corner of the portal
     * @param axis        portal axis
     * @return arrival vector
     */
    private static Vec3d findLinkedPortalArrival(
            final ServerWorld world,
            final Entity entity,
            final BlockPos lowerCorner,
            final Direction.Axis axis
    ) {
        final Direction widthDirection = widthDirection(axis);
        final Vec3d center = new Vec3d(
                lowerCorner.getX() + 0.5 + widthDirection.getOffsetX() * 0.5,
                lowerCorner.getY(),
                lowerCorner.getZ() + 0.5 + widthDirection.getOffsetZ() * 0.5
        );

        return NetherPortal.findOpenPosition(center, world, entity, entity.getDimensions(entity.getPose()));
    }

    /**
     * Places the obsidian frame first (so portal blocks are never inserted into an incomplete frame
     * and then invalidated by neighbor updates), then fills the interior with portal blocks, then
     * registers POIs for each interior block.
     */
    private static void buildPortal(
            final ServerWorld world,
            final BlockPos lowerCorner,
            final Direction.Axis axis
    ) {
        final Direction widthDirection = widthDirection(axis);
        final BlockState portalState = Blocks.NETHER_PORTAL
                .getDefaultState()
                .with(NetherPortalBlock.AXIS, axis);

        for (int width = -1; width <= PORTAL_INTERIOR_WIDTH; width++) {
            for (int height = -1; height <= PORTAL_INTERIOR_HEIGHT; height++) {
                if (!isPortalBoundary(width, height)) {
                    continue;
                }

                world.setBlockState(
                        lowerCorner.offset(widthDirection, width).up(height),
                        OBSIDIAN_STATE,
                        Block.NOTIFY_ALL
                );
            }
        }

        for (int width = 0; width < PORTAL_INTERIOR_WIDTH; width++) {
            for (int height = 0; height < PORTAL_INTERIOR_HEIGHT; height++) {
                world.setBlockState(
                        lowerCorner.offset(widthDirection, width).up(height),
                        portalState,
                        Block.NOTIFY_ALL
                );
            }
        }

        addPortalPois(world, lowerCorner, widthDirection);
    }

    /**
     * Registers Nether Portal POIs for all interior blocks of a portal.
     *
     * @param world          destination world
     * @param lowerCorner    bottom-left interior corner
     * @param widthDirection horizontal direction of the portal face
     */
    private static void addPortalPois(
            final ServerWorld world,
            final BlockPos lowerCorner,
            final Direction widthDirection
    ) {
        final PointOfInterestStorage poiStorage = world.getPointOfInterestStorage();
        final RegistryEntry<PointOfInterestType> portalPoiType = world
                .getRegistryManager()
                .getOrThrow(RegistryKeys.POINT_OF_INTEREST_TYPE)
                .getOrThrow(PointOfInterestTypes.NETHER_PORTAL);

        for (int width = 0; width < PORTAL_INTERIOR_WIDTH; width++) {
            for (int height = 0; height < PORTAL_INTERIOR_HEIGHT; height++) {
                poiStorage.add(lowerCorner.offset(widthDirection, width).up(height), portalPoiType);
            }
        }
    }

    /**
     * Computes a composite score for a portal site candidate. Lower is better.
     * Factors in distance to target, vertical displacement, support blocks, and egress quality.
     */
    private static long scoreCandidate(
            final BlockPos target,
            final int lowerX,
            final int lowerY,
            final int lowerZ,
            final int supportScore,
            final int egressScore,
            final int bfsCount
    ) {
        final long dx = lowerX - (long) target.getX();
        final long dz = lowerZ - (long) target.getZ();
        final long horizontalDistanceSq = dx * dx + dz * dz;
        final long verticalDistance = Math.abs(lowerY - target.getY());
        final long supportPenalty = (4L - Math.min(supportScore, 4)) * 2_000L;
        final long egressPenalty = (8L - Math.min(egressScore, 8)) * 5_000L;
        final long bfsPenalty = (20L - Math.min(bfsCount, 20)) * 10_000L;
        return horizontalDistanceSq * 16L + verticalDistance * 64L + supportPenalty + egressPenalty + bfsPenalty;
    }

    /**
     * Counts how many solid support blocks exist two blocks below the portal interior.
     * This helps avoid portals hovering over gaps without any landing area.
     *
     * @param world          destination world
     * @param lowerX         bottom-left interior X
     * @param lowerY         bottom-left interior Y
     * @param lowerZ         bottom-left interior Z
     * @param widthDirection horizontal direction of the portal
     * @return count of solid blocks in the support row
     */
    private static int countSupportBelowPortal(
            final ServerWorld world,
            final int lowerX,
            final int lowerY,
            final int lowerZ,
            final Direction widthDirection
    ) {
        int support = 0;
        final BlockPos.Mutable mutable = new BlockPos.Mutable();

        for (int width = -1; width <= PORTAL_INTERIOR_WIDTH; width++) {
            mutable.set(
                    lowerX + widthDirection.getOffsetX() * width,
                    lowerY - 2,
                    lowerZ + widthDirection.getOffsetZ() * width
            );

            if (world.getBlockState(mutable).isSideSolidFullSquare(world, mutable, Direction.UP)) {
                support++;
            }
        }

        return support;
    }

    /**
     * Counts walkable tiles reachable from ({@code startX}, {@code startY}, {@code startZ}) via
     * horizontal BFS, bounded to a {@value #EGRESS_BFS_RADIUS}-block Chebyshev radius.
     *
     * <p>Y is fixed throughout — all tiles are checked at the same height as the starting
     * position. Visited positions are encoded as packed longs to avoid per-node heap allocation.
     *
     * @param world  destination world
     * @param startX X coordinate of the starting tile
     * @param startY Y coordinate of the starting tile
     * @param startZ Z coordinate of the starting tile
     * @return number of connected walkable tiles including the start tile
     */
    /**
     * Compares two portal sites and returns the one with the better (lower) score.
     */
    private static PortalSite chooseBetter(final PortalSite current, final PortalSite candidate) {
        if (candidate == null) return current;
        return current == null || candidate.score() < current.score() ? candidate : current;
    }

    /**
     * Checks if a coordinate offset (dx, dz) is on the perimeter of a square with the given radius.
     */
    private static boolean isNotOnSearchRing(final int dx, final int dz, final int radius) {
        return Math.max(Math.abs(dx), Math.abs(dz)) != radius;
    }

    /**
     * Ensures the chunk containing a column is loaded.
     */
    private static void forceLoadColumn(final ServerWorld world, final int worldX, final int worldZ) {
        world.getChunk(worldX >> 4, worldZ >> 4);
    }

    /**
     * Returns the horizontal direction vector for the portal's width based on its axis.
     */
    private static Direction widthDirection(final Direction.Axis axis) {
        return axis == Direction.Axis.X ? Direction.WEST : Direction.SOUTH;
    }

    /**
     * Returns the effective ceiling height for the world, capping it in the Nether.
     */
    private static int worldCeilingY(final ServerWorld world) {
        return world.getRegistryKey() == World.NETHER
                ? Math.min(world.getTopYInclusive(), NETHER_MAX_CREATED_PORTAL_Y)
                : world.getTopYInclusive();
    }

    /**
     * A validated candidate site for portal placement.
     *
     * @param lowerCorner bottom-left interior corner of the proposed portal
     * @param arrival     exact validated egress arrival position
     * @param score       composite placement score; lower is better
     * @param axis        portal orientation axis for this site
     */
    private record PortalSite(BlockPos lowerCorner, Vec3d arrival, long score, Direction.Axis axis) {
    }

}
