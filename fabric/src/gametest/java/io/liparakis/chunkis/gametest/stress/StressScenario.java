package io.liparakis.chunkis.gametest.stress;

import com.google.gson.JsonObject;
import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.config.ChunkisDebugLevel;
import io.liparakis.chunkis.world.tracking.save.AsyncCisSaveManager;
import io.liparakis.chunkis.world.tracking.save.ChunkisStoragePaths;
import io.liparakis.chunkis.world.tracking.state.LoadedChunks;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Tick-driven round-trip scenario: fill an area of chunks with dense, varied terrain, then repeatedly
 * unload it, reload it, edit it and autosave it, measuring every server tick from the second round on.
 *
 * <p>All randomness is seeded so runs are comparable. Settings come from {@code chunkis.stress.*}
 * system properties.</p>
 */
final class StressScenario implements StressRun {

    /**
     * Height of the filled volume above the generated ground.
     */
    private static final int FILL_HEIGHT = 64;

    /**
     * Ticks to keep measuring after each autosave pass, so async completion lands in the window.
     */
    private static final int SAVE_SETTLE_TICKS = 20;

    /**
     * Target tick rate; 0 runs ticks unthrottled like a plain gametest server.
     */
    private static final int TICKS_PER_SECOND = Integer.getInteger("chunkis.stress.tps", 20);

    /**
     * Terrain blocks and their relative weights.
     */
    private static final BlockState[] TERRAIN = {
            Blocks.STONE.defaultBlockState(), Blocks.DEEPSLATE.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
            Blocks.ANDESITE.defaultBlockState(), Blocks.GRANITE.defaultBlockState(), Blocks.DIORITE.defaultBlockState(),
            Blocks.TUFF.defaultBlockState(), Blocks.COAL_ORE.defaultBlockState(), Blocks.IRON_ORE.defaultBlockState(),
            Blocks.COPPER_ORE.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.CLAY.defaultBlockState()
    };

    /**
     * Weights matching {@link #TERRAIN}.
     */
    private static final int[] WEIGHTS = {55, 12, 6, 5, 4, 3, 3, 3, 2, 1, 5, 1};

    /**
     * Blocks used for player-like edits.
     */
    private static final BlockState[] EDIT_BLOCKS = {
            Blocks.AIR.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
            Blocks.GLASS.defaultBlockState(), Blocks.GLOWSTONE.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState()
    };

    /**
     * Scenario steps.
     */
    private enum Step {
        /**
         * Waiting for the forced area to load the first time, then filling and saving it.
         */
        WAIT_INITIAL_LOAD,
        /**
         * Starting a round by releasing the area.
         */
        START_ROUND,
        /**
         * Waiting for every chunk to unload.
         */
        UNLOAD,
        /**
         * Waiting for every chunk to load again.
         */
        RELOAD,
        /**
         * Editing a few chunks per tick.
         */
        EDIT,
        /**
         * Letting an autosave pass settle.
         */
        SAVE,
        /**
         * Writing the report.
         */
        DONE
    }

    /**
     * Gametest context, completed when the scenario ends.
     */
    private final GameTestHelper context;

    /**
     * Test world.
     */
    private final ServerLevel world;

    /**
     * Collected measurements.
     */
    private final StressMetrics metrics = new StressMetrics(TICKS_PER_SECOND);

    /**
     * Seeded source for terrain and edits, so runs are comparable.
     */
    private final Random random = new Random(0xC4_0C15L);

    /**
     * Chunks of the stressed area.
     */
    private final List<ChunkPos> chunks = new ArrayList<>();

    /**
     * Bottom of the filled volume per chunk.
     */
    private final Long2IntOpenHashMap groundYByChunk = new Long2IntOpenHashMap();

    /**
     * Chunks still to unload or load in the current step.
     */
    private final Set<Long> pendingChunks = new HashSet<>();

    /**
     * Measured rounds after the warm-up round.
     */
    private final int rounds = Integer.getInteger("chunkis.stress.rounds", 5);

    /**
     * Scattered block edits per chunk per round.
     */
    private final int editsPerChunk = Integer.getInteger("chunkis.stress.editsPerChunk", 64);

    /**
     * Chunks edited per tick.
     */
    private final int chunksPerTick = Integer.getInteger("chunkis.stress.chunksPerTick", 4);

    /**
     * Share of the area, in percent, that is populated and edited; the rest stays untouched generated terrain
     * that only unloads and reloads, as in normal exploration.
     */
    private final int touchedPercent = Integer.getInteger("chunkis.stress.touchedPercent", 100);

    /**
     * Whether touched chunks keep their generated terrain and get player-like edits and a small base on the
     * surface, instead of being filled with synthetic terrain.
     */
    private final boolean natural = Boolean.getBoolean("chunkis.stress.natural");

    /**
     * Chunks that get populated and edited, spread evenly over the area.
     */
    private final List<ChunkPos> touchedChunks = new ArrayList<>();

    /**
     * World-level Chunkis directory, measured for disk use.
     */
    private final Path chunkisDir;

    /**
     * Current step.
     */
    private Step step = Step.WAIT_INITIAL_LOAD;

    /**
     * Current round; round 0 is the unmeasured warm-up.
     */
    private int round;

    /**
     * Ticks since the scenario began.
     */
    private long tick;

    /**
     * Tick at which the current step began.
     */
    private long stepStartTick;

    /**
     * Next chunk to edit in the current round.
     */
    private int editIndex;

    /**
     * Real time at which the current step began.
     */
    private long stepStartNanos;

    /**
     * Prepares the scenario at a fixed area: the test server places tests at a random position each run, and
     * terrain (biomes, mobs) there would differ between runs that are meant to be compared.
     *
     * @param context gametest context
     */
    StressScenario(final GameTestHelper context) {
        this.context = context;
        this.world = context.getLevel();
        final ChunkPos anchor = new ChunkPos(Integer.getInteger("chunkis.stress.anchorX", 40_000),
                Integer.getInteger("chunkis.stress.anchorZ", 40_000));
        int size = Integer.getInteger("chunkis.stress.size", 8);
        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                chunks.add(new ChunkPos(anchor.x() + dx, anchor.z() + dz));
            }
        }
        final int touched = Math.clamp((int) Math.round(chunks.size() * Math.max(0, touchedPercent) / 100.0),
                1, chunks.size());
        for (int i = 0; i < touched; i++) {
            touchedChunks.add(chunks.get((int) ((long) i * chunks.size() / touched)));
        }
        this.chunkisDir = ChunkisStoragePaths.computeRegionsDirectory(
                        world.getServer()
                                .getWorldPath(LevelResource.ROOT), world.dimension())
                .getParent();
    }

    /**
     * Gives the metrics to the tick hooks.
     *
     * @return metrics
     */
    @Override
    public StressMetrics metrics() {
        return metrics;
    }

    /**
     * Forces the area so it loads, with tracing off so it does not distort the numbers.
     */
    void begin() {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.OFF);
        if (!Boolean.getBoolean("chunkis.stress.vanilla")) {
            // As in a world created with Chunkis: the game test server skips the pre-launch step that marks it, and
            // without it untouched chunks would be saved to vanilla region files instead of regenerating.
            io.liparakis.chunkis.integration.migration.state.MigrationStateServiceHolder
                    .markAuthoritative(world.dimension());
        }
        if (TICKS_PER_SECOND > 0) {
            world.getServer().tickRateManager().setTickRate(TICKS_PER_SECOND);
            // Unpaced, the test server runs ticks faster than real time, and each one moves the next tick's due
            // time forward by a full tick: pacing from there would idle until the clock caught up, for minutes.
            ((io.liparakis.chunkis.gametest.mixin.MinecraftServerAccessor) world.getServer())
                    .chunkis$setTickStartTimeNanos(net.minecraft.util.Util.getNanos());
            StressBenchGameTest.setPacingTicks(true);
        }
        chunks.forEach(pos -> world.setChunkForced(pos.x(), pos.z(), true));
        Chunkis.LOGGER.info("Chunkis stress: {} chunks, {} measured rounds, {} edits per chunk", chunks.size(),
                rounds, editsPerChunk);
    }

    /**
     * Notes a chunk that finished loading.
     *
     * @param loaded loaded chunk
     */
    @Override
    public void onChunkLoad(final LevelChunk loaded) {
        if (loaded.getLevel() != world) {
            return;
        }
        if (step == Step.RELOAD && pendingChunks.remove(loaded.getPos().pack())) {
            metrics.recordChunkLatency(StressMetrics.Phase.RELOAD, System.nanoTime() - stepStartNanos);
        }
    }

    /**
     * Notes a chunk that unloaded.
     *
     * @param unloaded unloaded chunk
     */
    @Override
    public void onChunkUnload(final LevelChunk unloaded) {
        if (unloaded.getLevel() != world) {
            return;
        }
        if (step == Step.UNLOAD && pendingChunks.remove(unloaded.getPos().pack())) {
            metrics.recordChunkLatency(StressMetrics.Phase.UNLOAD, System.nanoTime() - stepStartNanos);
        }
    }

    /**
     * Advances the scenario by one server tick.
     *
     * @return true once the scenario finished
     */
    @Override
    public boolean tick() {
        tick++;
        switch (step) {
            case WAIT_INITIAL_LOAD -> {
                if (chunks.stream()
                        .allMatch(pos -> world.getChunkSource()
                                .hasChunk(pos.x(), pos.z()))) {
                    touchedChunks.forEach(this::populate);
                    world.getChunkSource()
                            .save(false);
                    AsyncCisSaveManager.flushAndClose(world);
                    enter(Step.START_ROUND);
                }
            }
            case START_ROUND -> {
                if (round == 1) {
                    metrics.start(chunkisDir, vanillaDirs());
                    StageProfiler.reset();
                }
                resetPending();
                // Ticket-only (no blocking getChunk), so loading is asynchronous like player movement.
                chunks.forEach(pos -> world.getChunkSource().updateChunkForced(pos, false));
                metrics.setPhase(StressMetrics.Phase.UNLOAD);
                enter(Step.UNLOAD);
            }
            case UNLOAD -> {
                if (pendingChunks.isEmpty()) {
                    metrics.recordPhaseLength(StressMetrics.Phase.UNLOAD, tick - stepStartTick,
                            System.nanoTime() - stepStartNanos);
                    resetPending();
                    chunks.forEach(pos -> world.getChunkSource().updateChunkForced(pos, true));
                    metrics.setPhase(StressMetrics.Phase.RELOAD);
                    enter(Step.RELOAD);
                }
            }
            case RELOAD -> {
                pendingChunks.removeIf(key -> {
                    if (!isAccessible(key)) {
                        return false;
                    }
                    metrics.recordChunkLatency(StressMetrics.Phase.RELOAD, System.nanoTime() - stepStartNanos);
                    return true;
                });
                if (pendingChunks.isEmpty()) {
                    metrics.recordPhaseLength(StressMetrics.Phase.RELOAD, tick - stepStartTick,
                            System.nanoTime() - stepStartNanos);
                    editIndex = 0;
                    metrics.setPhase(StressMetrics.Phase.EDIT);
                    enter(Step.EDIT);
                }
            }
            case EDIT -> {
                for (int i = 0; i < chunksPerTick && editIndex < touchedChunks.size(); i++) {
                    edit(touchedChunks.get(editIndex++));
                }
                if (editIndex >= touchedChunks.size()) {
                    world.getChunkSource()
                            .save(false);
                    metrics.setPhase(StressMetrics.Phase.SAVE);
                    enter(Step.SAVE);
                }
            }
            case SAVE -> {
                if (tick - stepStartTick >= SAVE_SETTLE_TICKS) {
                    round++;
                    enter(round > rounds ? Step.DONE : Step.START_ROUND);
                }
            }
            case DONE -> {
                finish();
                return true;
            }
            default -> throw new IllegalStateException("Unknown step " + step);
        }
        return false;
    }

    /**
     * Vanilla region and entity folders of the overworld save.
     *
     * @return folders measured for vanilla disk use
     */
    private List<Path> vanillaDirs() {
        final Path dimensionRoot = net.minecraft.world.level.dimension.DimensionType.getStorageFolder(
                world.dimension(), world.getServer().getWorldPath(LevelResource.ROOT));
        return List.of(dimensionRoot.resolve("region"), dimensionRoot.resolve("entities"));
    }

    /**
     * Reports, without blocking, whether a chunk is fully loaded and usable.
     *
     * @param key packed chunk position
     * @return true once the chunk's accessible future has completed
     */
    private boolean isAccessible(final long key) {
        return LoadedChunks.getIfAccessible(world, ChunkPos.getX(key), ChunkPos.getZ(key)) != null;
    }

    /**
     * Moves to a new step and remembers when it started.
     *
     * @param next next step
     */
    private void enter(final Step next) {
        // Lines profiles up with phases: JFR samples carry wall-clock times, like the log.
        Chunkis.LOGGER.info("Chunkis stress: step {} at tick {} ({})", next, tick, java.time.LocalTime.now());
        step = next;
        stepStartTick = tick;
        stepStartNanos = System.nanoTime();
    }

    /**
     * Marks every chunk as still pending for the next unload or reload.
     */
    private void resetPending() {
        pendingChunks.clear();
        chunks.forEach(pos -> pendingChunks.add(pos.pack()));
    }

    /**
     * Writes the JVM's class histogram of live objects, taken while the area is still loaded, next to the report.
     */
    static void writeHeapHistogram() {
        try {
            final String histogram = (String) java.lang.management.ManagementFactory.getPlatformMBeanServer()
                    .invoke(new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand"),
                            "gcClassHistogram", new Object[]{new String[0]}, new String[]{String[].class.getName()});
            final Path file = Path.of("chunkis-stress", "histogram-"
                    + (Boolean.getBoolean("chunkis.stress.vanilla") ? "vanilla" : "chunkis") + ".txt");
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, histogram);
            Chunkis.LOGGER.info("Chunkis stress: heap histogram written to {}", file.toAbsolutePath());
        } catch (final Exception e) {
            Chunkis.LOGGER.warn("Chunkis stress: heap histogram failed", e);
        }
    }

    /**
     * Stops measuring, writes the report and releases the area.
     */
    private void finish() {
        metrics.stop();
        // Before the size comparison below reads every stored chunk on this thread.
        final JsonObject stages = StageProfiler.ENABLED ? StageProfiler.toJson() : null;
        AsyncCisSaveManager.flushAndClose(world);
        System.gc();
        final long retainedHeap = Runtime.getRuntime()
                .totalMemory() - Runtime.getRuntime()
                .freeMemory();
        if (Boolean.getBoolean("chunkis.stress.histogram")) {
            writeHeapHistogram();
        }
        final JsonObject config = new JsonObject();
        config.addProperty("scenario", "round-trip-reload-and-edits-async");
        config.addProperty("chunks", chunks.size());
        config.addProperty("measuredRounds", rounds);
        config.addProperty("editsPerChunk", editsPerChunk);
        config.addProperty("chunksPerTick", chunksPerTick);
        config.addProperty("touchedPercent", touchedPercent);
        config.addProperty("touchedChunks", touchedChunks.size());
        config.addProperty("fillHeight", FILL_HEIGHT);
        config.addProperty("ticksPerSecond", TICKS_PER_SECOND);
        config.addProperty("storage", Boolean.getBoolean("chunkis.stress.vanilla") ? "vanilla" : "chunkis");
        config.addProperty("dimension", world.dimension().identifier().toString());
        config.addProperty("natural", natural);
        // Reads every stored chunk, about 10 s; a paired A/B does not use the result.
        if (!Boolean.getBoolean("chunkis.stress.skipSizes")) {
            metrics.setSizePerChunk(ChunkSizeComparison.measure(world, chunks));
        }
        if (stages != null) {
            config.add("stages", stages);
        }
        final Path report = metrics.writeReport(Path.of("chunkis-stress"), config, chunkisDir, retainedHeap);
        Chunkis.LOGGER.info("Chunkis stress: report written to {}", report.toAbsolutePath());
        StressBenchGameTest.setPacingTicks(false);
        chunks.forEach(pos -> world.setChunkForced(pos.x(), pos.z(), false));
        context.succeed();
    }

    /**
     * Fills a chunk with dense varied terrain, a few container block entities and some entities.
     *
     * @param pos chunk position
     */
    private void populate(final ChunkPos pos) {
        final LevelChunk chunk = world.getChunk(pos.x(), pos.z());
        final int surfaceY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, 8, 8) + 1;
        // Natural: the generated terrain stays; edits land in the 64 blocks under the surface, the base on top.
        final int groundY = natural ? surfaceY - FILL_HEIGHT : surfaceY;
        groundYByChunk.put(pos.pack(), groundY);
        for (int y = groundY; !natural && y < groundY + FILL_HEIGHT; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    world.setBlock(pos.getBlockAt(x, y, z), weightedTerrain(), Block.UPDATE_CLIENTS);
                }
            }
        }
        final int topY = groundY + FILL_HEIGHT;
        for (int i = 0; i < 4; i++) {
            final BlockPos chestPos = pos.getBlockAt(2 + i * 3, topY, 2);
            world.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS);
            if (world.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) {
                for (int slot = 0; slot < 27; slot += 2) {
                    chest.setItem(slot, new ItemStack(slot % 4 == 0 ? Items.COBBLESTONE : Items.IRON_INGOT,
                            1 + random.nextInt(64)));
                }
            }
        }
        world.setBlock(pos.getBlockAt(4, topY, 10), Blocks.FURNACE.defaultBlockState(), Block.UPDATE_CLIENTS);
        world.setBlock(pos.getBlockAt(10, topY, 10), Blocks.FURNACE.defaultBlockState(), Block.UPDATE_CLIENTS);
        for (int i = 0; i < 2; i++) {
            final ArmorStand stand = EntityTypes.ARMOR_STAND.create(world, EntitySpawnReason.COMMAND);
            if (stand != null) {
                stand.snapTo(pos.getMinBlockX() + 4.5 + i * 6, topY + 1, pos.getMinBlockZ() + 13.5, 0, 0);
                world.addFreshEntity(stand);
            }
            final Pig pig = EntityTypes.PIG.create(world, EntitySpawnReason.COMMAND);
            if (pig != null) {
                pig.snapTo(pos.getMinBlockX() + 7.5 + i * 2, topY + 1, pos.getMinBlockZ() + 7.5, 0, 0);
                pig.setNoAi(true);
                pig.setPersistenceRequired();
                world.addFreshEntity(pig);
            }
        }
    }

    /**
     * Applies player-like edits: scattered block changes, one small cube and a chest change.
     *
     * @param pos chunk position
     */
    private void edit(final ChunkPos pos) {
        final int groundY = groundYByChunk.get(pos.pack());
        for (int i = 0; i < editsPerChunk; i++) {
            final BlockPos target = pos.getBlockAt(random.nextInt(16), groundY + random.nextInt(FILL_HEIGHT),
                    random.nextInt(16));
            world.setBlock(target, EDIT_BLOCKS[random.nextInt(EDIT_BLOCKS.length)], Block.UPDATE_ALL);
        }
        final BlockState cubeBlock = EDIT_BLOCKS[1 + random.nextInt(EDIT_BLOCKS.length - 1)];
        final int cx = random.nextInt(12);
        final int cy = groundY + random.nextInt(FILL_HEIGHT - 4);
        final int cz = random.nextInt(12);
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    world.setBlock(pos.getBlockAt(cx + x, cy + y, cz + z), cubeBlock, Block.UPDATE_ALL);
                }
            }
        }
        final BlockPos chestPos = pos.getBlockAt(2, groundY + FILL_HEIGHT, 2);
        if (world.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) {
            chest.setItem(random.nextInt(27), new ItemStack(Items.DIAMOND, 1 + random.nextInt(8)));
            chest.setChanged();
        }
    }

    /**
     * Picks a terrain block by weight.
     *
     * @return block state
     */
    private BlockState weightedTerrain() {
        int roll = random.nextInt(100);
        for (int i = 0; i < TERRAIN.length; i++) {
            roll -= WEIGHTS[i];
            if (roll < 0) {
                return TERRAIN[i];
            }
        }
        return TERRAIN[0];
    }
}
