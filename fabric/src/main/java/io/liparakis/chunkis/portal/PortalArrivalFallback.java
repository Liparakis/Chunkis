package io.liparakis.chunkis.portal;

import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Controlled fallback for Nether portal travel when vanilla cannot link to an existing destination
 * portal. Searches near the intended destination for a safe, bounded arrival. A portal is built
 * only when the full frame footprint is validated as non-destructive, and the teleport target is
 * set to a validated walkable egress tile beside it.
 *
 * <p>Fallback order: safe portal frame -> validated egress tile -> safe standing position ->
 * emergency surface arrival.</p>
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
     * Default state for obsidian blocks used in portal frames.
     */
    private static final BlockState OBSIDIAN_STATE = Blocks.OBSIDIAN.defaultBlockState();

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
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
    public static TeleportTransition create(
            final ServerLevel destinationWorld,
            final Entity entity,
            final ServerLevel sourceWorld,
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

        final Vec3 safeArrival = findSafeArrival(destinationWorld, scaledDestinationPos);

        final Vec3 arrival = Objects.requireNonNullElseGet(safeArrival,
                () -> emergencyArrival(destinationWorld, entity, scaledDestinationPos));
        return createTeleportTarget(destinationWorld, entity, arrival, false);
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
    public static TeleportTransition createLinkedTeleportTarget(
            final ServerLevel world,
            final Entity entity,
            final BlockPos lowerCorner,
            final Direction.Axis axis
    ) {
        final Vec3 arrival = findLinkedPortalArrival(world, entity, lowerCorner, axis);
        return new TeleportTransition(
                world,
                arrival,
                entity.getDeltaMovement(),
                entity.getYRot(),
                entity.getXRot(),
                Set.of(),
                TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET)
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
                .getOptionalValue(BlockStateProperties.HORIZONTAL_AXIS)
                .orElse(Direction.Axis.X);
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
            final ServerLevel world,
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

                    final int surfaceY = Mth.clamp(
                            world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ),
                            minY, maxY
                    );
                    final int startY = Mth.clamp(target.getY(), minY, surfaceY);

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
     * {@code PORTAL_VERTICAL_RANGE} blocks vertically of the target Y. Rings are scanned
     * outward and the search stops after the first ring that contains a valid site; the
     * lowest-scoring site seen so far is returned.</p>
     *
     * @param world  destination world
     * @param target vanilla-scaled arrival position
     * @param axis   preferred portal orientation axis
     * @return the best valid site, or {@code null} if none was found
     */
    private static PortalSite findPortalSite(
            final ServerLevel world,
            final BlockPos target,
            final Direction.Axis axis
    ) {
        final int minY = world.getMinY() + 1;
        final int maxY = worldCeilingY(world) - PORTAL_INTERIOR_HEIGHT - 1;
        final PortalSite[] best = {null};
        final int[] foundRing = {-1};

        searchColumns(
                world, target, PORTAL_SEARCH_RADIUS, minY, maxY, (worldX, worldZ, startY, surfaceY) -> {
                    // Rings are visited by distance: once the ring holding the first valid site is
                    // complete, farther rings cannot be closer, so stop instead of scanning all of them.
                    final int ring = Math.max(Math.abs(worldX - target.getX()), Math.abs(worldZ - target.getZ()));
                    if (foundRing[0] >= 0 && ring > foundRing[0]) {
                        return true;
                    }
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
                    if (best[0] != null && foundRing[0] < 0) {
                        foundRing[0] = ring;
                    }
                    return false;
                }
        );

        return best[0];
    }

    /**
     * Scans upward and downward from {@code startY} within {@code PORTAL_VERTICAL_RANGE} blocks,
     * keeping the best-scoring site found.
     *
     * @param world    destination world
     * @param target   arrival position
     * @param x        X coordinate
     * @param startY   starting Y coordinate
     * @param z        Z coordinate
     * @param minY     lower Y limit
     * @param maxY     upper Y limit
     * @param axis     portal orientation axis
     * @param bestSite preceding best site candidate
     * @return chosen PortalSite candidate
     */
    private static PortalSite searchVerticalPortalSites(
            final ServerLevel world,
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
     *
     * @param world         destination world
     * @param target        arrival position
     * @param lowerX        lower bounds X
     * @param lowerY        lower bounds Y
     * @param lowerZ        lower bounds Z
     * @param preferredAxis preferred orientation axis
     * @return chosen PortalSite candidate
     */
    private static PortalSite validatePortalSiteBothAxes(
            final ServerLevel world,
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
     * high Nether placements remain valid.</p>
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
            final ServerLevel world,
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
     *
     * @param world          destination world
     * @param lowerX         lower bounds X
     * @param lowerY         lower bounds Y
     * @param lowerZ         lower bounds Z
     * @param widthDirection width offset direction
     * @return true if footprint blocks can be safely overwritten
     */
    private static boolean isPortalFootprintSafe(
            final ServerLevel world,
            final int lowerX,
            final int lowerY,
            final int lowerZ,
            final Direction widthDirection
    ) {
        final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (int width = -1; width <= PORTAL_INTERIOR_WIDTH; width++) {
            for (int height = -1; height <= PORTAL_INTERIOR_HEIGHT; height++) {
                mutable.set(
                        lowerX + widthDirection.getStepX() * width,
                        lowerY + height,
                        lowerZ + widthDirection.getStepZ() * width
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
     * Returns {@code true} if a portal frame must not overwrite this block.
     *
     * <p>Only air, replaceable blocks (grass, snow layers, fluids) and natural terrain
     * may be overwritten, which is what makes the fallback non-destructive: player-placed
     * blocks such as planks, iron blocks, doors or redstone reject the site instead of
     * being silently deleted.</p>
     *
     * @param state       block state to check
     * @param blockEntity block entity at the position, if any
     * @return {@code true} if the block cannot be replaced
     */
    static boolean isUnsafeToReplace(final BlockState state, final BlockEntity blockEntity) {
        if (blockEntity != null) {
            return true;
        }
        if (state.isAir() || state.canBeReplaced()) {
            return false;
        }
        return !(state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.BASE_STONE_NETHER)
                || state.is(BlockTags.STONE_ORE_REPLACEABLES)
                || state.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)
                || state.is(BlockTags.DIRT)
                || state.is(BlockTags.SAND)
                || state.is(BlockTags.NYLIUM)
                || state.is(BlockTags.SOUL_SPEED_BLOCKS)
                || state.is(BlockTags.TERRACOTTA)
                || state.is(BlockTags.ICE)
                || state.is(BlockTags.SNOW)
                || state.is(Blocks.GRAVEL));
    }

    /**
     * Searches the destination area for any safe two-block-high standing position, without
     * building a portal.
     *
     * @param world  destination world
     * @param target vanilla-scaled arrival position
     * @return a valid standing position, or {@code null} if none was found
     */
    private static Vec3 findSafeArrival(
            final ServerLevel world,
            final BlockPos target
    ) {
        final int minY = world.getMinY() + 1;
        final int maxY = worldCeilingY(world) - 2;
        final Vec3[] result = {null};

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
     * Last-resort arrival at the motion-blocking surface above the target column.
     * Delegates final position refinement to {@link PortalShape#findCollisionFreePosition}.
     *
     * @param world  destination world
     * @param entity teleporting entity
     * @param target arrival coordinates target
     * @return coordinates vector representing emergency target
     */
    private static Vec3 emergencyArrival(
            final ServerLevel world,
            final Entity entity,
            final BlockPos target
    ) {
        final int topY = Mth.clamp(
                world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, target.getX(), target.getZ()),
                world.getMinY() + 1,
                worldCeilingY(world) - 2
        );

        final Vec3 anchor = new Vec3(target.getX() + 0.5, topY, target.getZ() + 0.5);
        return PortalShape.findCollisionFreePosition(anchor, world, entity, entity.getDimensions(entity.getPose()));
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
    private static TeleportTransition createTeleportTarget(
            final ServerLevel world,
            final Entity entity,
            final Vec3 position,
            final boolean builtPortal
    ) {
        final TeleportTransition.PostTeleportTransition cooldown =
                teleported -> teleported.setPortalCooldown(FALLBACK_PORTAL_COOLDOWN_TICKS);

        final TeleportTransition.PostTeleportTransition transition = builtPortal
                ? TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET)
                : TeleportTransition.PLAY_PORTAL_SOUND;

        return new TeleportTransition(
                world, position, entity.getDeltaMovement(),
                entity.getYRot(), entity.getXRot(),
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
    private static Vec3 findLinkedPortalArrival(
            final ServerLevel world,
            final Entity entity,
            final BlockPos lowerCorner,
            final Direction.Axis axis
    ) {
        final Direction widthDirection = widthDirection(axis);
        final Vec3 center = new Vec3(
                lowerCorner.getX() + 0.5 + widthDirection.getStepX() * 0.5,
                lowerCorner.getY(),
                lowerCorner.getZ() + 0.5 + widthDirection.getStepZ() * 0.5
        );

        return PortalShape.findCollisionFreePosition(center, world, entity, entity.getDimensions(entity.getPose()));
    }

    /**
     * Places the obsidian frame first, then fills the interior with portal blocks, then
     * registers POIs for each interior block.
     *
     * @param world       destination world
     * @param lowerCorner bottom-left interior corner of the portal
     * @param axis        portal axis alignment
     */
    private static void buildPortal(
            final ServerLevel world,
            final BlockPos lowerCorner,
            final Direction.Axis axis
    ) {
        final Direction widthDirection = widthDirection(axis);
        final BlockState portalState = Blocks.NETHER_PORTAL
                .defaultBlockState()
                .setValue(NetherPortalBlock.AXIS, axis);

        for (int width = -1; width <= PORTAL_INTERIOR_WIDTH; width++) {
            for (int height = -1; height <= PORTAL_INTERIOR_HEIGHT; height++) {
                if (!isPortalBoundary(width, height)) {
                    continue;
                }

                world.setBlock(
                        lowerCorner.relative(widthDirection, width)
                                .above(height),
                        OBSIDIAN_STATE,
                        Block.UPDATE_ALL
                );
            }
        }

        for (int width = 0; width < PORTAL_INTERIOR_WIDTH; width++) {
            for (int height = 0; height < PORTAL_INTERIOR_HEIGHT; height++) {
                world.setBlock(
                        lowerCorner.relative(widthDirection, width)
                                .above(height),
                        portalState,
                        Block.UPDATE_ALL
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
            final ServerLevel world,
            final BlockPos lowerCorner,
            final Direction widthDirection
    ) {
        final PoiManager poiStorage = world.getPoiManager();
        final Holder<PoiType> portalPoiType = world
                .registryAccess()
                .lookupOrThrow(Registries.POINT_OF_INTEREST_TYPE)
                .getOrThrow(PoiTypes.NETHER_PORTAL);

        for (int width = 0; width < PORTAL_INTERIOR_WIDTH; width++) {
            for (int height = 0; height < PORTAL_INTERIOR_HEIGHT; height++) {
                poiStorage.add(lowerCorner.relative(widthDirection, width)
                        .above(height), portalPoiType);
            }
        }
    }

    /**
     * Computes a composite score for a portal site candidate. Lower is better.
     * Factors in distance to target, vertical displacement, support blocks, and egress quality.
     *
     * @param target       target coordinates position
     * @param lowerX       lower bounds X
     * @param lowerY       lower bounds Y
     * @param lowerZ       lower bounds Z
     * @param supportScore calculated support blocks score
     * @param egressScore  calculated egress blocks score
     * @param bfsCount     connected walkable coordinates count
     * @return calculated long score metric
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
            final ServerLevel world,
            final int lowerX,
            final int lowerY,
            final int lowerZ,
            final Direction widthDirection
    ) {
        int support = 0;
        final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (int width = -1; width <= PORTAL_INTERIOR_WIDTH; width++) {
            mutable.set(
                    lowerX + widthDirection.getStepX() * width,
                    lowerY - 2,
                    lowerZ + widthDirection.getStepZ() * width
            );

            if (world.getBlockState(mutable)
                    .isFaceSturdy(world, mutable, Direction.UP)) {
                support++;
            }
        }

        return support;
    }

    /**
     * Compares two portal sites and returns the one with the better (lower) score.
     *
     * @param current   current best site candidate, may be null
     * @param candidate new site candidate, may be null
     * @return the better PortalSite candidate
     */
    private static PortalSite chooseBetter(final PortalSite current, final PortalSite candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.score() < current.score() ? candidate : current;
    }

    /**
     * Checks if a coordinate offset (dx, dz) is on the perimeter of a square with the given radius.
     *
     * @param dx     offset X
     * @param dz     offset Z
     * @param radius boundary radius
     * @return true if coordinate lies on the boundary perimeter
     */
    private static boolean isNotOnSearchRing(final int dx, final int dz, final int radius) {
        return Math.max(Math.abs(dx), Math.abs(dz)) != radius;
    }

    /**
     * Ensures the chunk containing a column is loaded.
     *
     * @param world  destination world
     * @param worldX target block coordinate X
     * @param worldZ target block coordinate Z
     */
    private static void forceLoadColumn(final ServerLevel world, final int worldX, final int worldZ) {
        world.getChunk(worldX >> 4, worldZ >> 4);
    }

    /**
     * Returns the horizontal direction vector for the portal's width based on its axis.
     *
     * @param axis portal Axis alignment
     * @return width horizontal offset direction
     */
    private static Direction widthDirection(final Direction.Axis axis) {
        return axis == Direction.Axis.X ? Direction.WEST : Direction.SOUTH;
    }

    /**
     * Returns the effective ceiling height for the world, capping it in the Nether.
     *
     * @param world target server world
     * @return ceiling limit height Y coordinate
     */
    private static int worldCeilingY(final ServerLevel world) {
        return world.dimension() == Level.NETHER
                ? Math.min(world.getMaxY(), NETHER_MAX_CREATED_PORTAL_Y)
                : world.getMaxY();
    }

    /**
     * Visits every column in an outward square-ring search, computing {@code startY} (clamped
     * target height) and {@code surfaceY} (motion-blocking surface) for each. The visitor receives
     * both Y values and returns {@code true} to stop the search early.
     */
    @FunctionalInterface
    private interface ColumnVisitor {

        /**
         * Invoked to evaluate column targets.
         *
         * @param worldX   column block X coordinates
         * @param worldZ   column block Z coordinates
         * @param startY   calculated start Y limit
         * @param surfaceY calculated surface Y limit
         * @return true to cancel further visitation
         */
        boolean visit(int worldX, int worldZ, int startY, int surfaceY);
    }

    /**
     * A validated candidate site for portal placement.
     *
     * @param lowerCorner bottom-left interior corner of the proposed portal
     * @param arrival     exact validated egress arrival position
     * @param score       composite placement score; lower is better
     * @param axis        portal orientation axis for this site
     */
    private record PortalSite(BlockPos lowerCorner, Vec3 arrival, long score, Direction.Axis axis) {

    }
}
