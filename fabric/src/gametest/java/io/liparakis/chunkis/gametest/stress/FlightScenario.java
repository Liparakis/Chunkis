package io.liparakis.chunkis.gametest.stress;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.config.ChunkisDebugLevel;
import io.liparakis.chunkis.integration.migration.state.MigrationStateServiceHolder;
import io.liparakis.chunkis.world.tracking.save.AsyncCisSaveManager;
import io.liparakis.chunkis.world.tracking.save.ChunkisStoragePaths;
import io.liparakis.chunkis.world.tracking.save.RecentChunkBlocks;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import io.liparakis.chunkis.world.tracking.state.LoadedChunks;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

/**
 * A simulated player flying in a straight line across natural terrain and back, as players explore: chunks stream
 * in ahead and unload behind, across many region files. A window of forced chunks follows the flight position, as
 * the chunks around a player would; a mock player would not load any, since it has no connection.
 *
 * <p>Three legs. The first flies out over fresh terrain and generates it; it is not measured. The second flies back
 * and the third out again, both over terrain the first leg stored, so they measure loading stored chunks while
 * moving, saving chunks left behind, and a few edits near the flight position along the way.</p>
 *
 * <p>Besides tick times, it measures pop-in: how long a chunk that comes within view of the flight position takes to become
 * usable. A chunk already usable when it comes into view costs nothing; one still loading is counted as late, and
 * its wait recorded. Settings come from {@code chunkis.stress.*} system properties.</p>
 */
final class FlightScenario implements StressRun {

    /**
     * Blocks flown per tick; 3 is about elytra flight boosted with rockets.
     */
    private static final double SPEED = Double.parseDouble(System.getProperty("chunkis.stress.flightSpeed", "3"));

    /**
     * Length of each leg in blocks.
     */
    private static final int DISTANCE = Integer.getInteger("chunkis.stress.flightBlocks", 4096);

    /**
     * Server tick rate while flying; 0 runs ticks back to back.
     */
    private static final int TICKS_PER_SECOND = Integer.getInteger("chunkis.stress.tps", 20);

    /**
     * Whether the generating leg runs unpaced, moving on only once the window is usable. Several times faster, but
     * it changes when leg 0's unload saves land, and so the cache contents and pending work leg 1 starts with: only
     * for runs that count things, never for timing comparisons.
     */
    private static final boolean FAST_GENERATION = Boolean.getBoolean("chunkis.stress.fastGeneration");

    /**
     * Radius in chunks of the window kept loaded around the flight position; vanilla's default view distance.
     */
    private static final int LOAD_RADIUS = 10;

    /**
     * Chunks closer than this to the flight position count as in view; kept inside the loaded window so that a
     * chunk gets loaded a little before it comes into view, as for a player.
     */
    private static final int VIEW_RADIUS = 8;

    /**
     * Ticks to keep running after the last leg, so the chunks left behind finish saving.
     */
    private static final int SETTLE_TICKS = 200;

    /**
     * Number of legs; the first is not measured.
     */
    private static final int LEGS = 3;

    /**
     * Game test context.
     */
    private final TestContext context;

    /**
     * World flown over.
     */
    private final ServerWorld world;

    /**
     * Shared metrics.
     */
    private final StressMetrics metrics = new StressMetrics(TICKS_PER_SECOND);

    /**
     * Seeded, so runs place the same edits.
     */
    private final Random random = new Random(0xF1_1647L);

    /**
     * First block X of the flight path.
     */
    private final double startX;

    /**
     * Block Z of the flight path.
     */
    private final double pathZ;

    /**
     * World-level Chunkis directory, for the disk size.
     */
    private final Path chunkisDir;

    /**
     * Block X of the flight position.
     */
    private double x;

    /**
     * Chunks currently forced around the flight position, by packed position.
     */
    private LongOpenHashSet forced = new LongOpenHashSet();

    /**
     * Current leg, 0 based.
     */
    private int leg;

    /**
     * Blocks flown in the current leg.
     */
    private double flown;

    /**
     * Ticks the generating leg took, waits for loading included.
     */
    private long leg0Ticks;

    /**
     * Ticks since the last leg ended, or -1 while flying.
     */
    private int settledTicks = -1;

    /**
     * When each chunk in view that is not usable yet first came into view, by packed position.
     */
    private final Long2LongOpenHashMap pendingSince = new Long2LongOpenHashMap();

    /**
     * Chunks in view during the last tick, by packed position.
     */
    private LongOpenHashSet inView = new LongOpenHashSet();

    /**
     * Chunks that came into view during measured legs.
     */
    private long chunksEntered;

    /**
     * Of those, chunks that were not usable yet when they came into view.
     */
    private long chunksLate;

    /**
     * Of the late ones, chunks that left view again before becoming usable.
     */
    private long chunksMissed;

    /**
     * Loads requested and not yet converted, sampled every measured tick: the loader's backlog.
     */
    private final StressMetrics.LongSamples backlog = new StressMetrics.LongSamples();

    /**
     * Forced chunks not usable yet, sampled every measured tick.
     */
    private final StressMetrics.LongSamples unusable = new StressMetrics.LongSamples();

    /**
     * Largest loader backlog of the current leg.
     */
    private long legMaxBacklog;

    /**
     * Tickets dropped before their chunk became usable, during measured legs.
     */
    private long ticketsAbandoned;

    /**
     * Per-leg figures: cache traffic, backlog and, for the generating leg, the stage profile.
     */
    private final JsonArray legs = new JsonArray();

    /**
     * Cache counters at the start of the current leg.
     */
    private JsonObject legCacheStart = StageProfiler.cacheSnapshot();

    /**
     * Prepares the flight path, far from the test area.
     *
     * @param context game test context
     */
    FlightScenario(final TestContext context) {
        this.context = context;
        this.world = context.getWorld();
        final ChunkPos anchor = new ChunkPos(Integer.getInteger("chunkis.stress.anchorX", 40_000),
                Integer.getInteger("chunkis.stress.anchorZ", 40_000));
        this.startX = anchor.getStartX() + 8.0;
        this.pathZ = anchor.getStartZ() + 8.0;
        this.chunkisDir = ChunkisStoragePaths.computeRegionsDirectory(
                world.getServer().getSavePath(WorldSavePath.ROOT), world.getRegistryKey()).getParent();
    }

    @Override
    public StressMetrics metrics() {
        return metrics;
    }

    @Override
    public void onChunkLoad(final WorldChunk loaded) {
        StageProfiler.event(StageProfiler.Event.LOADED_EVENT, loaded.getPos().x, loaded.getPos().z);
    }

    @Override
    public void onChunkUnload(final WorldChunk unloaded) {
    }

    /**
     * Starts loading chunks around the start of the path.
     */
    void begin() {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.OFF);
        if (!Boolean.getBoolean("chunkis.stress.vanilla")) {
            // As in a world created with Chunkis; the game test server skips the pre-launch step that marks it.
            MigrationStateServiceHolder.markAuthoritative(world.getRegistryKey());
        }
        if (TICKS_PER_SECOND > 0) {
            world.getServer().getTickManager().setTickRate(TICKS_PER_SECOND);
        }
        if (!FAST_GENERATION) {
            startPacing();
        }
        moveTo(0.0);
        Chunkis.LOGGER.info("Chunkis stress: flight of {} legs x {} blocks at {} blocks/tick", LEGS, DISTANCE, SPEED);
    }

    @Override
    public boolean tick() {
        if (settledTicks >= 0) {
            if (++settledTicks >= SETTLE_TICKS) {
                finish();
                return true;
            }
            return false;
        }
        if (leg == 0) {
            leg0Ticks++;
            if (FAST_GENERATION && trackBacklog() > 0) {
                return false;
            }
        }
        flown = Math.min(DISTANCE, flown + SPEED);
        moveTo(leg % 2 == 0 ? flown : DISTANCE - flown);
        if (leg > 0) {
            trackPopIn();
        }
        if (leg > 0 || !FAST_GENERATION) {
            trackBacklog();
        }
        editNearPlayer();
        if (flown >= DISTANCE) {
            endLeg();
            leg++;
            flown = 0.0;
            Chunkis.LOGGER.info("Chunkis stress: leg {} starts", leg);
            if (leg == 1) {
                Chunkis.LOGGER.info("Chunkis stress: generating leg took {} ticks", leg0Ticks);
                if (FAST_GENERATION) {
                    startPacing();
                }
                metrics.start(chunkisDir, vanillaDirs());
                StageProfiler.reset();
                backlog.clear();
                unusable.clear();
                ticketsAbandoned = 0;
            }
            if (leg >= LEGS) {
                settledTicks = 0;
            } else {
                metrics.setPhase(leg % 2 == 0 ? StressMetrics.Phase.OUTBOUND : StressMetrics.Phase.RETURN);
            }
        }
        return false;
    }

    /**
     * Moves the flight position along the path, and the loaded window with it once it enters another chunk.
     *
     * @param along blocks from the start
     */
    private void moveTo(final double along) {
        x = startX + along;
        final ChunkPos center = center();
        if (forced.contains(ChunkPos.toLong(center.x - LOAD_RADIUS, center.z - LOAD_RADIUS))
                && forced.contains(ChunkPos.toLong(center.x + LOAD_RADIUS, center.z + LOAD_RADIUS))) {
            return;
        }
        final LongOpenHashSet window = new LongOpenHashSet((2 * LOAD_RADIUS + 1) * (2 * LOAD_RADIUS + 1));
        for (int dx = -LOAD_RADIUS; dx <= LOAD_RADIUS; dx++) {
            for (int dz = -LOAD_RADIUS; dz <= LOAD_RADIUS; dz++) {
                window.add(ChunkPos.toLong(center.x + dx, center.z + dz));
            }
        }
        // Ticket changes only, so chunks load and unload asynchronously as they do for a moving player.
        forced.forEach(key -> {
            if (!window.contains(key)) {
                final ChunkPos pos = new ChunkPos(key);
                if (StageProfiler.pending(pos.x, pos.z)) {
                    ticketsAbandoned++;
                }
                world.getChunkManager().setChunkForced(pos, false);
            }
        });
        window.forEach(key -> {
            if (!forced.contains(key)) {
                final ChunkPos pos = new ChunkPos(key);
                StageProfiler.event(StageProfiler.Event.TICKET, pos.x, pos.z);
                world.getChunkManager().setChunkForced(pos, true);
            }
        });
        forced = window;
    }

    /**
     * Returns the chunk of the flight position.
     *
     * @return chunk position
     */
    private ChunkPos center() {
        return new ChunkPos((int) Math.floor(x) >> 4, (int) Math.floor(pathZ) >> 4);
    }

    /**
     * Records which chunks in view were not usable yet, and how long those took.
     */
    private void trackPopIn() {
        final long now = System.nanoTime();
        final ChunkPos center = center();
        final LongOpenHashSet nowInView = new LongOpenHashSet((2 * VIEW_RADIUS + 1) * (2 * VIEW_RADIUS + 1));
        for (int dx = -VIEW_RADIUS; dx <= VIEW_RADIUS; dx++) {
            for (int dz = -VIEW_RADIUS; dz <= VIEW_RADIUS; dz++) {
                final int x = center.x + dx;
                final int z = center.z + dz;
                final long key = ChunkPos.toLong(x, z);
                nowInView.add(key);
                final boolean usable = LoadedChunks.getIfAccessible(world, x, z) != null;
                if (!inView.contains(key)) {
                    chunksEntered++;
                    if (!usable) {
                        chunksLate++;
                        pendingSince.put(key, now);
                    }
                } else if (usable && pendingSince.containsKey(key)) {
                    metrics.recordChunkLatency(currentPhase(), now - pendingSince.remove(key));
                }
            }
        }
        pendingSince.keySet().removeIf(key -> {
            if (nowInView.contains(key)) {
                return false;
            }
            chunksMissed++;
            return true;
        });
        inView = nowInView;
    }

    /**
     * Switches to real tick pacing, from the current tick on.
     */
    private void startPacing() {
        if (TICKS_PER_SECOND > 0) {
            ((io.liparakis.chunkis.gametest.mixin.MinecraftServerAccessor) world.getServer())
                    .chunkis$setTickStartTimeNanos(net.minecraft.util.Util.getMeasuringTimeNano());
            StressBenchGameTest.setPacingTicks(true);
        }
    }

    /**
     * Samples the loader's backlog and the forced chunks not usable yet, and marks the ones that just became
     * usable.
     *
     * @return forced chunks not usable yet
     */
    private long trackBacklog() {
        long waiting = 0;
        for (final long key : forced) {
            final int chunkX = ChunkPos.getPackedX(key);
            final int chunkZ = ChunkPos.getPackedZ(key);
            if (LoadedChunks.getIfAccessible(world, chunkX, chunkZ) == null) {
                waiting++;
            } else {
                StageProfiler.event(StageProfiler.Event.ACCESSIBLE, chunkX, chunkZ);
            }
        }
        final long inFlight = StageProfiler.inFlight();
        legMaxBacklog = Math.max(legMaxBacklog, inFlight);
        if (leg > 0) {
            backlog.add(inFlight);
            unusable.add(waiting);
        }
        return waiting;
    }

    /**
     * Records the figures of the leg that just ended.
     */
    private void endLeg() {
        final JsonObject figures = new JsonObject();
        figures.addProperty("leg", leg);
        figures.addProperty("direction", leg % 2 == 0 ? "outbound" : "return");
        figures.addProperty("maxBacklog", legMaxBacklog);
        final JsonObject cacheEnd = StageProfiler.cacheSnapshot();
        final JsonObject cache = new JsonObject();
        for (final Map.Entry<String, com.google.gson.JsonElement> counter : cacheEnd.entrySet()) {
            final long end = counter.getValue().getAsLong();
            cache.addProperty(counter.getKey(), counter.getKey().startsWith("peak_")
                    ? end : end - legCacheStart.get(counter.getKey()).getAsLong());
        }
        figures.add("cache", cache);
        if (leg == 0) {
            figures.add("profile", StageProfiler.toJson());
        }
        legs.add(figures);
        legCacheStart = cacheEnd;
        legMaxBacklog = 0;
    }

    /**
     * Measures what Chunkis's two in-memory chunk caches hold: entry counts, their own estimates, and the heap
     * actually freed by dropping each, with full collections before and after. Runs last, when the world is done
     * with them; dropping either only costs later loads the cached copy.
     *
     * @return the measurements
     */
    private JsonObject heapOwnership() {
        final JsonObject owners = new JsonObject();
        try {
            final RecentChunkBlocks recent = RecentChunkBlocks.of(world.getRegistryKey());
            owners.addProperty("recentCacheEntries", ((Map<?, ?>) field(recent, "entries")).size());
            owners.addProperty("recentCacheEstimateMb", (long) field(recent, "bytes") / 1048576.0);
            final Object unloadCache = staticField(GlobalChunkTracker.class, "unloadCache");
            owners.addProperty("unloadCacheEntries", ((Map<?, ?>) field(unloadCache, "entries")).size());
            owners.addProperty("dirtyDeltas",
                    ((Map<?, ?>) staticField(GlobalChunkTracker.class, "dirtyDeltas")).size());
            owners.addProperty("loadedChunks", world.getChunkManager().getLoadedChunkCount());
            owners.add("dirtyDeltaProbe", probeDirtyDeltas());
            final long before = usedHeapAfterGc();
            recent.clear();
            final long afterRecent = usedHeapAfterGc();
            final java.lang.reflect.Method clear = unloadCache.getClass().getDeclaredMethod("clear");
            clear.setAccessible(true);
            clear.invoke(unloadCache);
            final long afterUnload = usedHeapAfterGc();
            owners.addProperty("heapBeforeReleaseMb", before / 1048576.0);
            owners.addProperty("recentCacheFreedMb", (before - afterRecent) / 1048576.0);
            owners.addProperty("unloadCacheFreedMb", (afterRecent - afterUnload) / 1048576.0);
            owners.addProperty("heapAfterReleaseMb", afterUnload / 1048576.0);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("Chunkis cache layout changed; update the flight benchmark", e);
        }
        return owners;
    }

    /**
     * Examines the deltas the tracker still holds as dirty after every save finished: whether each is really
     * dirty, how far its generation is past the saved one, whether storage holds its chunk, and where it last went
     * from clean to dirty.
     *
     * @return the findings
     * @throws ReflectiveOperationException when the tracker or delta layout changed
     */
    private JsonObject probeDirtyDeltas() throws ReflectiveOperationException {
        final Map<?, ?> dirty = (Map<?, ?>) staticField(GlobalChunkTracker.class, "dirtyDeltas");
        final var storage = io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper.getStorage(world);
        long reallyDirty = 0;
        long neverSaved = 0;
        long stored = 0;
        long released = 0;
        final Map<Long, Long> generationGaps = new java.util.TreeMap<>();
        final Map<String, Long> sites = new java.util.HashMap<>();
        final Map<String, Long> cleanSites = new java.util.HashMap<>();
        for (final Map.Entry<?, ?> entry : dirty.entrySet()) {
            final io.liparakis.chunkis.core.ChunkDelta<?, ?> delta =
                    (io.liparakis.chunkis.core.ChunkDelta<?, ?>) entry.getValue();
            final Object state = field(delta, "ownershipState");
            final long mutation = (long) field(state, "mutationGeneration");
            final long saved = (long) field(state, "savedGeneration");
            if (delta.isDirty()) {
                reallyDirty++;
            }
            if (saved == 0L) {
                neverSaved++;
            }
            if (delta.isBlockPayloadReleased()) {
                released++;
            }
            generationGaps.merge(Math.min(mutation - saved, 10L), 1L, Long::sum);
            final long chunkKey = (long) field(entry.getKey(), "chunkKey");
            if (storage.contains(new io.liparakis.chunkis.core.CisChunkPos(ChunkPos.getPackedX(chunkKey),
                    ChunkPos.getPackedZ(chunkKey)))) {
                stored++;
            }
            sites.merge(StageProfiler.dirtySite(delta), 1L, Long::sum);
            if (!delta.isDirty()) {
                cleanSites.merge(StageProfiler.cleanPut(delta), 1L, Long::sum);
            }
        }
        final JsonObject probe = new JsonObject();
        probe.addProperty("entries", dirty.size());
        probe.addProperty("reallyDirty", reallyDirty);
        probe.addProperty("neverSaved", neverSaved);
        probe.addProperty("storageHasEntry", stored);
        probe.addProperty("blocksReleased", released);
        final JsonObject gaps = new JsonObject();
        generationGaps.forEach((gap, count) -> gaps.addProperty(gap >= 10 ? "10+" : String.valueOf(gap), count));
        probe.add("generationsPastSaved", gaps);
        final JsonObject bySite = new JsonObject();
        sites.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(8)
                .forEach(site -> bySite.addProperty(site.getKey(), site.getValue()));
        probe.add("lastDirtiedAt", bySite);
        final JsonObject byCleanSite = new JsonObject();
        cleanSites.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(8)
                .forEach(site -> byCleanSite.addProperty(site.getKey(), site.getValue()));
        probe.add("cleanOnesPutCleanAt", byCleanSite);
        return probe;
    }

    /**
     * Returns heap in use after full collections.
     *
     * @return bytes
     */
    private static long usedHeapAfterGc() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    /**
     * Reads a private field.
     *
     * @param owner object
     * @param name  field name
     * @return value
     * @throws ReflectiveOperationException when the field is missing
     */
    private static Object field(final Object owner, final String name) throws ReflectiveOperationException {
        final Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    /**
     * Reads a private static field.
     *
     * @param owner class
     * @param name  field name
     * @return value
     * @throws ReflectiveOperationException when the field is missing
     */
    private static Object staticField(final Class<?> owner, final String name) throws ReflectiveOperationException {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    /**
     * Phase of the current leg.
     *
     * @return phase
     */
    private StressMetrics.Phase currentPhase() {
        return leg % 2 == 0 ? StressMetrics.Phase.OUTBOUND : StressMetrics.Phase.RETURN;
    }

    /**
     * Places a block on the surface near the flight position, as a player building along the way would.
     */
    private void editNearPlayer() {
        final ChunkPos center = center();
        final int blockX = center.getStartX() + random.nextInt(80) - 32;
        final int blockZ = center.getStartZ() + random.nextInt(80) - 32;
        if (LoadedChunks.getIfAccessible(world, blockX >> 4, blockZ >> 4) == null) {
            return;
        }
        final int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, blockX, blockZ);
        world.setBlockState(new BlockPos(blockX, y, blockZ), Blocks.COBBLESTONE.getDefaultState());
    }

    /**
     * Returns vanilla's chunk and entity region directories of the world.
     *
     * @return directories
     */
    private List<Path> vanillaDirs() {
        final Path dimensionRoot = net.minecraft.world.dimension.DimensionType.getSaveDirectory(
                world.getRegistryKey(), world.getServer().getSavePath(WorldSavePath.ROOT));
        return List.of(dimensionRoot.resolve("region"), dimensionRoot.resolve("entities"));
    }

    /**
     * Stops measuring and writes the report.
     */
    private void finish() {
        metrics.stop();
        forced.forEach(key -> world.getChunkManager().setChunkForced(new ChunkPos(key), false));
        forced.clear();
        AsyncCisSaveManager.flushAndClose(world);
        System.gc();
        final long retainedHeap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        if (Boolean.getBoolean("chunkis.stress.histogram")) {
            StressScenario.writeHeapHistogram();
        }
        final JsonObject config = new JsonObject();
        config.addProperty("scenario", "flight-out-and-back");
        config.addProperty("legs", LEGS);
        config.addProperty("measuredLegs", LEGS - 1);
        config.addProperty("blocksPerLeg", DISTANCE);
        config.addProperty("blocksPerTick", SPEED);
        config.addProperty("loadRadius", LOAD_RADIUS);
        config.addProperty("viewRadius", VIEW_RADIUS);
        config.addProperty("ticksPerSecond", TICKS_PER_SECOND);
        config.addProperty("fastGeneration", FAST_GENERATION);
        config.addProperty("storage", Boolean.getBoolean("chunkis.stress.vanilla") ? "vanilla" : "chunkis");
        final JsonObject popIn = new JsonObject();
        popIn.addProperty("chunksEntered", chunksEntered);
        popIn.addProperty("chunksLate", chunksLate);
        popIn.addProperty("chunksMissed", chunksMissed);
        config.add("popIn", popIn);
        final JsonObject loader = new JsonObject();
        loader.add("loadsInFlight", backlog.summaryRaw());
        loader.add("forcedChunksUnusable", unusable.summaryRaw());
        loader.addProperty("ticketsAbandoned", ticketsAbandoned);
        config.add("loader", loader);
        config.add("legs", legs);
        config.add("storedLegsProfile", StageProfiler.toJson());
        config.add("heapOwnership", heapOwnership());
        final Path report = metrics.writeReport(Path.of("chunkis-stress"), config, chunkisDir, retainedHeap);
        Chunkis.LOGGER.info("Chunkis stress: report written to {}", report.toAbsolutePath());
        StressBenchGameTest.setPacingTicks(false);
        context.complete();
    }
}
