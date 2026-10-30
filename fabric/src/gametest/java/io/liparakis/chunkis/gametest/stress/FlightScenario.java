package io.liparakis.chunkis.gametest.stress;

import com.google.gson.JsonObject;
import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.config.ChunkisDebugLevel;
import io.liparakis.chunkis.integration.migration.state.MigrationStateServiceHolder;
import io.liparakis.chunkis.world.tracking.save.AsyncCisSaveManager;
import io.liparakis.chunkis.world.tracking.save.ChunkisStoragePaths;
import io.liparakis.chunkis.world.tracking.state.LoadedChunks;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.file.Path;
import java.util.List;
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
            ((io.liparakis.chunkis.gametest.mixin.MinecraftServerAccessor) world.getServer())
                    .chunkis$setTickStartTimeNanos(net.minecraft.util.Util.getMeasuringTimeNano());
            StressBenchGameTest.setPacingTicks(true);
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
        flown = Math.min(DISTANCE, flown + SPEED);
        moveTo(leg % 2 == 0 ? flown : DISTANCE - flown);
        if (leg > 0) {
            trackPopIn();
        }
        editNearPlayer();
        if (flown >= DISTANCE) {
            leg++;
            flown = 0.0;
            if (leg == 1) {
                metrics.start(chunkisDir, vanillaDirs());
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
                world.getChunkManager().setChunkForced(new ChunkPos(key), false);
            }
        });
        window.forEach(key -> {
            if (!forced.contains(key)) {
                world.getChunkManager().setChunkForced(new ChunkPos(key), true);
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
        config.addProperty("storage", Boolean.getBoolean("chunkis.stress.vanilla") ? "vanilla" : "chunkis");
        final JsonObject popIn = new JsonObject();
        popIn.addProperty("chunksEntered", chunksEntered);
        popIn.addProperty("chunksLate", chunksLate);
        popIn.addProperty("chunksMissed", chunksMissed);
        config.add("popIn", popIn);
        final Path report = metrics.writeReport(Path.of("chunkis-stress"), config, chunkisDir, retainedHeap);
        Chunkis.LOGGER.info("Chunkis stress: report written to {}", report.toAbsolutePath());
        StressBenchGameTest.setPacingTicks(false);
        context.complete();
    }
}
