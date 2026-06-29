package io.liparakis.chunkis.mixin.world.dimension;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.portal.PortalChunkIndexManager;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.dimension.PortalForcer;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestType;
import net.minecraft.world.poi.PointOfInterestTypes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ensures Chunkis-restored nether portals are visible to vanilla portal lookup.
 *
 * <p>Vanilla portal lookup is POI based. Chunkis restores POIs when a chunk is
 * loaded, but vanilla may query the destination POI area before those chunks have
 * been loaded through Chunkis. This mixin forces only the vanilla portal search
 * area to load before lookup, preserving the existing save/storage lifecycle.</p>
 *
 * @author Liparakis
 * @version 1.0
 *
 */
@Mixin(PortalForcer.class)
public abstract class PortalForcerMixin {

    /**
     * Cached predicate for nether portal POI lookups.
     * Allocated once — the lambda captures nothing and is safe to share.
     */
    @Unique
    private static final Predicate<RegistryEntry<PointOfInterestType>> PORTAL_POI_PREDICATE = type -> type.matchesKey(PointOfInterestTypes.NETHER_PORTAL);

    /**
     * Upper bound for synchronous Chunkis portal-candidate chunk preloads.
     *
     * <p>Vanilla preloads POI data before lookup. Chunkis only needs to load
     * chunks that actually contain portal POI candidates so restored portal
     * blocks are present when vanilla validates block states. Loading the full
     * Overworld search square is the expensive path this cap avoids.</p>
     */
    @Unique
    private static final int MAX_FORCED_PORTAL_CANDIDATE_CHUNKS = Integer.getInteger("chunkis.portal.maxForcedCandidateChunks", 16);

    @Shadow
    @Final
    private ServerWorld world;

    /**
     * Returns the distinct chunks that contain portal POIs in vanilla's search
     * square.
     */
    @Unique
    private static Set<ChunkPos> collectPortalCandidateChunks(
            final BlockPos pos,
            final int radius,
            final PointOfInterestStorage poiStorage) {

        final Set<ChunkPos> chunks = new HashSet<>();
        poiStorage.getInSquare(PORTAL_POI_PREDICATE, pos, radius, PointOfInterestStorage.OccupationStatus.ANY)
                  .forEach(point -> {
                      final BlockPos pointPos = point.getPos();
                      chunks.add(new ChunkPos(pointPos));
                  });
        return chunks;
    }

    /**
     * Returns vanilla's portal search radius for the destination dimension.
     *
     * @param destIsNether whether the destination is Nether-like
     * @return portal search radius in blocks
     */
    @Unique
    private static int portalSearchRadius(final boolean destIsNether) {
        return destIsNether ? 16 : 128;
    }

    /**
     * Counts nether portal POIs across all chunks in the given range.
     *
     * <p>Uses the shared {@link #PORTAL_POI_PREDICATE} to avoid per-call
     * lambda allocation, and a local {@code long} accumulator to avoid the
     * {@code long[1]} heap allocation that a lambda closure would require.</p>
     *
     * @param range      chunk range to scan
     * @param poiStorage POI storage for the target world
     * @return total portal POI count across the range
     */
    @Unique
    private static long countPortalPois(final SearchChunkRange range, final PointOfInterestStorage poiStorage) {
        long count = 0;
        for (int chunkX = range.minChunkX(); chunkX <= range.maxChunkX(); chunkX++) {
            for (int chunkZ = range.minChunkZ(); chunkZ <= range.maxChunkZ(); chunkZ++) {
                count += poiStorage.getInChunk(PORTAL_POI_PREDICATE, new ChunkPos(chunkX, chunkZ), PointOfInterestStorage.OccupationStatus.ANY).count();
            }
        }
        return count;
    }

    /**
     * Loads chunks containing portal POI candidates after vanilla has preloaded
     * POI data for the search area.
     *
     * <p>This makes Chunkis-restored portal blocks visible to vanilla's
     * post-POI block-state validation without forcing the whole Overworld
     * 128-block search square to load synchronously.</p>
     *
     * @param pos          destination-scaled portal search origin
     * @param destIsNether true when vanilla will use the smaller Nether search radius
     * @param worldBorder  destination world border
     * @param cir          callback info
     */
    @Inject(
            method = "getPortalPos",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/poi/PointOfInterestStorage;preloadChunks(Lnet/minecraft/world/WorldView;Lnet/minecraft/util/math/BlockPos;I)V",
                    shift = At.Shift.AFTER))
    private void chunkis$loadPortalCandidateChunks(final BlockPos pos, final boolean destIsNether, final WorldBorder worldBorder, final CallbackInfoReturnable<Optional<?>> cir) {

        final int radius = portalSearchRadius(destIsNether);
        final SearchChunkRange range = SearchChunkRange.of(pos, radius);
        final PointOfInterestStorage poiStorage = world.getPointOfInterestStorage();
        final Set<ChunkPos> candidateChunks = new HashSet<>(
                PortalChunkIndexManager.getPortalChunksInRange(
                        world,
                        range.minChunkX(),
                        range.maxChunkX(),
                        range.minChunkZ(),
                        range.maxChunkZ()
                                                              )
        );

        if (candidateChunks.isEmpty()) {
            candidateChunks.addAll(collectPortalCandidateChunks(pos, radius, poiStorage));
        }

        if (candidateChunks.isEmpty()) {
            return;
        }

        if (candidateChunks.size() > MAX_FORCED_PORTAL_CANDIDATE_CHUNKS) {
            Chunkis.LOGGER.warn(
                    "Chunkis [PORTAL]: Skipping {} portal candidate chunk preload around {} in {} because {} candidate chunk(s) exceeds limit {}",
                    destIsNether ? "Nether" : "Overworld",
                    pos,
                    world.getRegistryKey().getValue(),
                    candidateChunks.size(),
                    MAX_FORCED_PORTAL_CANDIDATE_CHUNKS);
            return;
        }

        for (final ChunkPos candidateChunk : candidateChunks) {
            world.getChunk(candidateChunk.x, candidateChunk.z);
        }

        if (Chunkis.LOGGER.isDebugEnabled()) {
            Chunkis.LOGGER.debug(
                    "Chunkis [PORTAL]: Loaded {} portal candidate chunk(s) for {} search around {} in {} covering {} search chunk(s)",
                    candidateChunks.size(),
                    destIsNether ? "Nether" : "Overworld",
                    pos,
                    world.getRegistryKey().getValue(),
                    range.chunkCount());
        }
    }

    /**
     * Loads the same chunk area vanilla is about to search for portal POIs.
     *
     * <p>Retained as an explicit diagnostics fallback for worlds where portal
     * POI data is absent or stale. Disabled by default because the Overworld
     * search square can cover hundreds of chunks.</p>
     */
    @Inject(method = "getPortalPos", at = @At("HEAD"))
    private void chunkis$loadPortalSearchChunks(final BlockPos pos, final boolean destIsNether, final WorldBorder worldBorder, final CallbackInfoReturnable<Optional<?>> cir) {

        final int radius = portalSearchRadius(destIsNether);
        final SearchChunkRange range = SearchChunkRange.of(pos, radius);
        final int maxForcedSearchChunks = Integer.getInteger("chunkis.portal.maxForcedSearchChunks", 0);

        if (maxForcedSearchChunks <= 0 || range.chunkCount() > maxForcedSearchChunks) {
            if (Chunkis.LOGGER.isDebugEnabled() && maxForcedSearchChunks > 0) {
                Chunkis.LOGGER.debug(
                        "Chunkis [PORTAL]: Skipping forced {} portal search preload around {} in {} because {} chunk(s) exceeds limit {}",
                        destIsNether ? "Nether" : "Overworld",
                        pos,
                        world.getRegistryKey().getValue(),
                        range.chunkCount(),
                        maxForcedSearchChunks);
            }
            return;
        }

        for (int chunkX = range.minChunkX(); chunkX <= range.maxChunkX(); chunkX++) {
            for (int chunkZ = range.minChunkZ(); chunkZ <= range.maxChunkZ(); chunkZ++) {
                world.getChunk(chunkX, chunkZ);
            }
        }
    }

    /**
     * Logs whether vanilla found an existing portal or will fall through to portal
     * creation.
     *
     * @param pos          destination-scaled portal search origin
     * @param destIsNether true when vanilla used the smaller Nether search radius
     * @param worldBorder  destination world border
     * @param cir          callback info containing vanilla's lookup result
     */
    @Inject(method = "getPortalPos", at = @At("RETURN"))
    private void chunkis$logPortalLookupResult(final BlockPos pos, final boolean destIsNether, final WorldBorder worldBorder, final CallbackInfoReturnable<Optional<?>> cir) {

        final SearchChunkRange range = SearchChunkRange.of(pos, portalSearchRadius(destIsNether));

        if (cir.getReturnValue().isPresent()) {
            if (Chunkis.LOGGER.isDebugEnabled()) {
                final long candidates = countPortalPois(range, world.getPointOfInterestStorage());
                Chunkis.LOGGER.debug("Chunkis [PORTAL]: Lookup in {} from {} found existing portal with {} candidate POI(s)", world.getRegistryKey().getValue(), pos, candidates);
            }
        } else {
            final long candidates = countPortalPois(range, world.getPointOfInterestStorage());
            if (candidates > 0) {
                Chunkis.LOGGER.warn("Chunkis [PORTAL]: Lookup in {} from {} found no portal despite {} candidate POI(s)", world.getRegistryKey().getValue(), pos, candidates);
            } else if (Chunkis.LOGGER.isDebugEnabled()) {
                Chunkis.LOGGER.debug("Chunkis [PORTAL]: Lookup in {} from {} found no portal candidate", world.getRegistryKey().getValue(), pos);
            }
        }
    }

    /**
     * Inclusive chunk bounds for a block-radius portal search square.
     */
    @Unique
    private record SearchChunkRange(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {

        /**
         * Converts a block-space portal search into chunk-space bounds.
         *
         * @param center block-space search center
         * @param radius block-space search radius
         * @return inclusive chunk bounds intersecting the search square
         */
        static SearchChunkRange of(final BlockPos center, final int radius) {
            return new SearchChunkRange(
                    (center.getX() - radius) >> 4,
                    (center.getX() + radius) >> 4, (center.getZ() - radius) >> 4, (center.getZ() + radius) >> 4);
        }

        /**
         * Returns how many chunks are covered by this inclusive range.
         *
         * @return chunk count
         */
        int chunkCount() {
            return (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        }
    }
}

