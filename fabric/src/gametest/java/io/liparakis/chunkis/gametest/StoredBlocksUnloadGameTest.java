package io.liparakis.chunkis.gametest;

import static io.liparakis.chunkis.gametest.SectionTestSupport.copySections;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.world.entity.capture.EntityPayloadNbt;
import io.liparakis.chunkis.world.entity.capture.VanillaEntitySaveCapture;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.restoration.nbt.PreloadedBlockSections;
import io.liparakis.chunkis.world.tracking.save.AsyncCisSaveManager;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Covers chunks that unload while their block payload is released ({@link ChunkDelta#isBlockPayloadReleased()}):
 * the unload save copies the blocks instead of capturing them into the delta, which keeps only its recorded edits,
 * and every later save or reload reads the blocks back from the stored entry with those edits on top. Each test
 * edits a block and changes a chest's contents, state only Chunkis stores for an owned chunk, and checks both
 * survive together with every other block.
 */
@SuppressWarnings("unused")
public final class StoredBlocksUnloadGameTest {

    /**
     * Unloads a reloaded chunk with an edited block and a changed chest, and expects the unload save to leave
     * its block payload released (no capture) and the reload to bring back the changes and every block.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 600)
    public void unloadOfReleasedChunkCopiesBlocksAndKeepsChange(final GameTestHelper context) {
        final Fixture fixture = Fixture.create(context, 2700);
        fixture.storeAndReloadReleased()
                .thenWaitUntil(() -> {
                    fixture.fillChest();
                    fixture.unload();
                })
                .thenWaitUntil(fixture::awaitUnloaded)
                .thenWaitUntil(() -> {
                    final ChunkDelta<?, ?> unloadedDelta = fixture.liveDelta();
                    context.assertTrue(unloadedDelta != null && unloadedDelta.isBlockPayloadReleased(),
                            Component.literal("The unload save captured the blocks of chunk " + fixture.target
                                    + " instead of copying them."));
                    AsyncCisSaveManager.flushAndClose(fixture.world);
                    fixture.load();
                })
                .thenWaitUntil(fixture::assertReloadedWithChange)
                .thenWaitUntil(fixture::unforce)
                .thenSucceed();
    }

    /**
     * Saves, after its chunk unloaded, a released tracked delta with a recorded block edit that vanilla's entity
     * save then changed, and expects the stored entry to keep every block, apply the edit and gain the entity.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 600)
    public void entitySaveAfterUnloadKeepsStoredBlocks(final GameTestHelper context) {
        final Fixture fixture = Fixture.create(context, 2900);
        final UUID[] pig = new UUID[1];
        fixture.storeAndReloadReleased()
                .thenWaitUntil(fixture::unload)
                .thenWaitUntil(fixture::awaitUnloaded)
                .thenWaitUntil(() -> {
                    AsyncCisSaveManager.flushAndClose(fixture.world);
                    // The state an unload leaves while its save is still on its way: a released tracked delta that
                    // holds only the edits recorded on top of the stored blocks.
                    final ChunkDelta<BlockState, CompoundTag> tracked = fixture.storage()
                            .load(fixture.cisPos());
                    tracked.claimOwnership(ChunkTraceReason.EXPLICIT_CHUNKIS_MUTATION.name(), "gametest");
                    tracked.releaseBlockPayload();
                    fixture.recordEdit(tracked);
                    GlobalChunkTracker.addDelta(fixture.world, fixture.target, tracked, "gametest");

                    final Pig entity = java.util.Objects.requireNonNull(EntityTypes.PIG.create(fixture.world, EntitySpawnReason.LOAD), "Pig fixture could not be created");
                    final BlockPos at = fixture.target.getBlockAt(8, fixture.baseY + 20, 8);
                    entity.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                    pig[0] = entity.getUUID();
                    VanillaEntitySaveCapture.capture(fixture.world, fixture.target, List.of(entity));
                    context.assertTrue(tracked.isDirty() && tracked.isBlockPayloadReleased(),
                            Component.literal("Vanilla's entity save did not land in the tracked delta."));
                    context.assertTrue(FabricCisStorageHelper.saveTrackedDelta(fixture.world, fixture.storage(),
                                    fixture.target, tracked),
                            Component.literal("The tracked delta holding stored blocks could not be saved."));
                    fixture.assertStoredEntry(pig[0]);
                })
                .thenSucceed();
    }

    /**
     * Reloads a chunk right after it unloaded with a changed chest, without waiting for its save, and expects
     * the change and every block.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 600)
    public void fastReloadAfterUnloadKeepsChange(final GameTestHelper context) {
        final Fixture fixture = Fixture.create(context, 3100);
        fixture.storeAndReloadReleased()
                .thenWaitUntil(() -> {
                    fixture.fillChest();
                    fixture.unload();
                })
                .thenWaitUntil(fixture::awaitUnloaded)
                .thenWaitUntil(fixture::load)
                .thenWaitUntil(fixture::assertReloadedWithChange)
                .thenWaitUntil(fixture::unforce)
                .thenSucceed();
    }

    /**
     * Unloads a chunk with a changed chest whose save is refused, and expects the reload to rebuild it from
     * the stored blocks and the tracked change.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 600)
    public void failedUnloadSaveKeepsChangeForReload(final GameTestHelper context) {
        final Fixture fixture = Fixture.create(context, 4700);
        fixture.storeAndReloadReleased()
                .thenWaitUntil(() -> {
                    fixture.fillChest();
                    // Every later write of this test-only chunk fails, like a disk error would.
                    fixture.storage()
                            .protectFromOverwrite(fixture.cisPos());
                    fixture.unload();
                })
                .thenWaitUntil(fixture::awaitUnloaded)
                .thenWaitUntil(() -> {
                    AsyncCisSaveManager.flushAndClose(fixture.world);
                    final ChunkDelta<?, ?> tracked = GlobalChunkTracker.getDelta(fixture.world, fixture.target);
                    context.assertTrue(tracked != null && tracked.isDirty() && tracked.isBlockPayloadReleased(),
                            Component.literal("The refused save did not leave the change tracked."));
                    fixture.load();
                })
                .thenWaitUntil(fixture::assertReloadedWithChange)
                .thenWaitUntil(fixture::unforce)
                .thenSucceed();
    }

    /**
     * Reloads a stored chunk and unloads it again without touching it, and expects its delta to stay clean (loading
     * turns the light flag off and on, which must not count as a change) and the dirty tracker to never hold it.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 600)
    public void untouchedReloadedChunkStaysCleanAndUntracked(final GameTestHelper context) {
        final Fixture fixture = Fixture.create(context, 4300);
        fixture.storeAndReloadReleased()
                .thenWaitUntil(() -> {
                    final ChunkDelta<?, ?> delta = fixture.liveDelta();
                    context.assertTrue(delta != null && !delta.isDirty(),
                            Component.literal("Loading chunk " + fixture.target + " marked its delta dirty."));
                    context.assertTrue(GlobalChunkTracker.getActiveDelta(fixture.world, fixture.target) == null,
                            Component.literal("The dirty tracker holds the clean delta of reloaded chunk "
                                    + fixture.target + "."));
                    fixture.unload();
                })
                .thenWaitUntil(fixture::awaitUnloaded)
                .thenWaitUntil(() -> {
                    AsyncCisSaveManager.flushAndClose(fixture.world);
                    context.assertTrue(GlobalChunkTracker.getActiveDelta(fixture.world, fixture.target) == null,
                            Component.literal("Unloading untouched chunk " + fixture.target
                                    + " left its delta in the dirty tracker."));
                })
                .thenSucceed();
    }

    /**
     * Writes into the block containers of a chunk that unloaded clean, which the recent-chunk cache took over, and
     * expects the reload to come back with the stored block: the cache checks the containers before a load uses them.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 600)
    public void staleWriteToAnUnloadedChunkNeverReachesItsReload(final GameTestHelper context) {
        final Fixture fixture = Fixture.create(context, 4900);
        final LevelChunk[] unloaded = new LevelChunk[1];
        final BlockState[] stored = new BlockState[1];
        fixture.storeAndReloadReleased()
                .thenWaitUntil(() -> {
                    final ChunkDelta<?, ?> delta = fixture.liveDelta();
                    context.assertTrue(delta != null && !delta.isDirty() && delta.getWrittenBlocksPrint() != null,
                            Component.literal("Chunk " + fixture.target + " would not reach the recent-chunk cache."));
                    unloaded[0] = fixture.chunk;
                    stored[0] = fixture.chunk.getBlockState(fixture.editedPos());
                    fixture.unload();
                })
                .thenWaitUntil(fixture::awaitUnloaded)
                .thenWaitUntil(() -> {
                    final BlockPos at = fixture.editedPos();
                    unloaded[0].getSection(unloaded[0].getSectionIndex(at.getY())).getStates()
                            .getAndSet(at.getX() & 15, at.getY() & 15, at.getZ() & 15,
                                    Blocks.DIAMOND_BLOCK.defaultBlockState());
                    fixture.load();
                })
                .thenWaitUntil(() -> {
                    final LevelChunk reloaded = fixture.world.getChunk(fixture.target.x(), fixture.target.z());
                    context.assertTrue(reloaded != unloaded[0],
                            Component.literal("Chunk " + fixture.target + " was not reloaded."));
                    context.assertTrue(reloaded.getBlockState(fixture.editedPos()) == stored[0],
                            Component.literal("A write to the unloaded chunk reached reloaded chunk " + fixture.target
                                    + ": " + reloaded.getBlockState(fixture.editedPos()) + "."));
                    fixture.unforce();
                })
                .thenSucceed();
    }

    /**
     * Takes a chunk that unloaded clean back out of the recent-chunk cache as a chunk load does, with its blocks only
     * attached, and expects the fallback that needs its instructions to build exactly the stored blocks from the
     * attached containers, then the chunk to reload whole.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 600)
    public void chunkLoadWithoutInstructionsBuildsThemFromItsContainers(final GameTestHelper context) {
        final Fixture fixture = Fixture.create(context, 5300);
        fixture.storeAndReloadReleased()
                .thenWaitUntil(() -> {
                    final ChunkDelta<?, ?> delta = fixture.liveDelta();
                    context.assertTrue(delta != null && !delta.isDirty() && delta.getWrittenBlocksPrint() != null,
                            Component.literal("Chunk " + fixture.target + " would not reach the recent-chunk cache."));
                    fixture.unload();
                })
                .thenWaitUntil(fixture::awaitUnloaded)
                .thenWaitUntil(() -> {
                    final var storage = FabricCisStorageHelper.getStorage(fixture.world);
                    final CisChunkPos pos = FabricCisStorageHelper.toStoragePos(fixture.target);
                    final ChunkDelta<BlockState, CompoundTag> attached = storage.loadForChunkLoad(pos);
                    context.assertTrue(attached.hasBlocksOnlyAttached() && attached.getBlockChangesCount() == 0,
                            Component.literal("Chunk " + fixture.target + " did not load its blocks without instructions:"
                                    + " attached=" + attached.hasBlocksOnlyAttached() + " blocks="
                                    + attached.getBlockChangesCount() + "."));
                    PreloadedBlockSections.buildAttachedInstructions(fixture.world, attached);
                    final java.util.Map<Long, BlockState> rebuilt = blockMap(attached);
                    final java.util.Map<Long, BlockState> stored = blockMap(storage.load(pos));
                    context.assertTrue(!attached.hasBlocksOnlyAttached() && !stored.isEmpty()
                                    && rebuilt.equals(stored),
                            Component.literal("Rebuilt blocks of " + fixture.target + " differ from the stored entry: "
                                    + rebuilt.size() + " against " + stored.size() + "."));
                    fixture.load();
                })
                .thenWaitUntil(() -> {
                    fixture.chunk = fixture.world.getChunk(fixture.target.x(), fixture.target.z());
                    context.assertTrue(fixture.chunk.getBlockState(fixture.chest).is(Blocks.CHEST),
                            Component.literal("Reloaded chunk " + fixture.target + " lost its chest."));
                    fixture.unforce();
                })
                .thenSucceed();
    }

    /**
     * Returns a delta's blocks by packed position.
     *
     * @param delta delta to read
     * @return blocks by position
     */
    private static java.util.Map<Long, BlockState> blockMap(final ChunkDelta<BlockState, CompoundTag> delta) {
        final java.util.Map<Long, BlockState> blocks = new java.util.HashMap<>();
        delta.forEachBlock((x, y, z, state) -> blocks.put(io.liparakis.chunkis.core.BlockInstruction.packPos(x, y, z),
                state));
        return blocks;
    }

    /**
     * Unloads a stored chunk whose only change since its last save is a new entity, and expects the stored entry
     * to keep every block, the chest, the light and the heightmaps, and to gain the entity: the save keeps the
     * delta's earlier capture of everything but blocks and entities instead of capturing it again.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 600)
    public void entityOnlyUnloadSaveKeepsTheRestOfTheChunk(final GameTestHelper context) {
        final Fixture fixture = Fixture.create(context, 3700);
        final UUID[] pig = new UUID[1];
        fixture.storeAndReloadReleased()
                .thenWaitUntil(() -> {
                    // Clears vanilla's own flag, which the reload set: from here vanilla sees no change but the pig.
                    fixture.world.getChunkSource().save(false);
                    AsyncCisSaveManager.flushAndClose(fixture.world);
                    pig[0] = fixture.spawnPig();
                    fixture.unload();
                })
                .thenWaitUntil(fixture::awaitUnloaded)
                .thenWaitUntil(() -> {
                    AsyncCisSaveManager.flushAndClose(fixture.world);
                    fixture.assertStoredEntry(pig[0]);
                    fixture.assertStoredNonBlockState();
                })
                .thenSucceed();
    }

    /**
     * Saves a changed chunk while it cannot be snapshotted, as during a restore, and expects vanilla's save flag to
     * stay set so the next save captures the change; that save then clears it.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 600)
    public void saveSkippedAsUnsafeKeepsVanillasSaveFlag(final GameTestHelper context) {
        final Fixture fixture = Fixture.create(context, 5900);
        context.startSequence()
                .thenWaitUntil(() -> {
                    final var coordinator = ((io.liparakis.chunkis.world.tracking.save.ChunkSaveCoordinatorHolder)
                            fixture.world.getChunkSource().chunkMap).chunkis$saveCoordinator();
                    final ChunkisDeltaDuck duck = (ChunkisDeltaDuck) fixture.chunk;
                    fixture.chunk.markUnsaved();
                    duck.chunkis$setRestoreOperationId("gametest-unsafe");
                    try {
                        coordinator.save(fixture.chunk);
                    } finally {
                        duck.chunkis$setRestoreOperationId(null);
                    }
                    // Vanilla's own flag: Chunkis's needsSaving() also reports a dirty delta, such as one being written.
                    final var flag = (io.liparakis.chunkis.mixin.accessor.ChunkSaveFlagAccessor) fixture.chunk;
                    context.assertTrue(flag.chunkis$vanillaNeedsSaving(), Component.literal("A save skipped as unsafe cleared "
                            + "the save flag of " + fixture.target + ", so its change would never be captured."));
                    final var outcome = coordinator.save(fixture.chunk);
                    context.assertFalse(flag.chunkis$vanillaNeedsSaving(), Component.literal("The next save of "
                            + fixture.target + " did not clear the save flag: outcome " + outcome + "."));
                    fixture.unforce();
                })
                .thenSucceed();
    }

    /**
     * Stores a pig, moves it so the stored entity no longer matches, lets the chunk and then its entities unload
     * the way vanilla schedules them, and expects exactly one pig back after the reload, where it was moved to.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 800)
    public void movedEntitySurvivesUnloadAndReloadOnce(final GameTestHelper context) {
        final Fixture fixture = Fixture.create(context, 5700);
        final UUID[] pig = new UUID[1];
        final net.minecraft.world.phys.Vec3[] moved = new net.minecraft.world.phys.Vec3[1];
        fixture.storeAndReloadReleased()
                .thenWaitUntil(() -> {
                    pig[0] = fixture.spawnPig();
                    fixture.chunk.markUnsaved();
                    fixture.world.getChunkSource().save(false);
                    AsyncCisSaveManager.flushAndClose(fixture.world);
                })
                .thenWaitUntil(() -> {
                    final net.minecraft.world.entity.Entity entity = fixture.world.getEntity(pig[0]);
                    context.assertTrue(entity != null, Component.literal("Pig " + pig[0] + " is not in the world."));
                    assert entity != null;
                    final BlockPos to = fixture.target.getBlockAt(3, fixture.baseY + 20, 12);
                    entity.snapTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 90, 0);
                    if (entity instanceof net.minecraft.world.entity.Mob mob) {
                        mob.setNoAi(true);
                    }
                    moved[0] = entity.position();
                    fixture.unload();
                })
                .thenWaitUntil(fixture::awaitUnloaded)
                .thenWaitUntil(() -> context.assertTrue(fixture.world.getEntity(pig[0]) == null,
                        Component.literal("Waiting for vanilla to unload the entities of " + fixture.target + ".")))
                .thenWaitUntil(() -> {
                    AsyncCisSaveManager.flushAndClose(fixture.world);
                    fixture.load();
                })
                .thenWaitUntil(() -> {
                    final net.minecraft.world.entity.Entity entity = fixture.world.getEntity(pig[0]);
                    context.assertTrue(entity != null,
                            Component.literal("Waiting for pig " + pig[0] + " to come back with " + fixture.target + "."));
                    assert entity != null;
                    context.assertTrue(entity.position().distanceToSqr(moved[0]) < 0.01,
                            Component.literal("Pig " + pig[0] + " came back at " + entity.position() + ", moved to "
                                    + moved[0] + "."));
                    final int pigs = fixture.world.getEntities(EntityTypes.PIG,
                            new net.minecraft.world.phys.AABB(fixture.target.getMinBlockX(), fixture.world.getMinY(),
                                    fixture.target.getMinBlockZ(), fixture.target.getMaxBlockX() + 1,
                                    fixture.world.getMaxY() + 1, fixture.target.getMaxBlockZ() + 1),
                            candidate -> candidate.getUUID().equals(pig[0])).size();
                    context.assertTrue(pigs == 1, Component.literal("Pig " + pig[0] + " exists " + pigs
                            + " times after the reload."));
                    fixture.unforce();
                })
                .thenSucceed();
    }

    /**
     * After the same save, changes a block and the chest and adds an entity, and expects all of them stored: a
     * change vanilla tracks still makes the save capture everything.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 600)
    public void trackedChangeWithEntityChangeIsSavedWhole(final GameTestHelper context) {
        final Fixture fixture = Fixture.create(context, 4500);
        final UUID[] pig = new UUID[1];
        fixture.storeAndReloadReleased()
                .thenWaitUntil(() -> {
                    fixture.world.getChunkSource().save(false);
                    AsyncCisSaveManager.flushAndClose(fixture.world);
                    fixture.fillChest();
                    pig[0] = fixture.spawnPig();
                    fixture.unload();
                })
                .thenWaitUntil(fixture::awaitUnloaded)
                .thenWaitUntil(() -> {
                    AsyncCisSaveManager.flushAndClose(fixture.world);
                    fixture.assertStoredEntry(pig[0]);
                    fixture.assertStoredNonBlockState();
                    fixture.load();
                })
                .thenWaitUntil(fixture::assertReloadedWithChange)
                .thenWaitUntil(fixture::unforce)
                .thenSucceed();
    }

    /**
     * One test chunk: a section of varied blocks and a chest, stored and reloaded so its delta releases its
     * block instructions.
     */
    private static final class Fixture {

        /**
         * Test context.
         */
        private final GameTestHelper context;

        /**
         * Test world.
         */
        private final ServerLevel world;

        /**
         * Chunk under test, far from every other test's.
         */
        private final ChunkPos target;

        /**
         * Bottom Y of the filled section.
         */
        private int baseY;

        /**
         * Position of the chest.
         */
        private BlockPos chest;

        /**
         * Per-section block copies taken after the blocks were placed.
         */
        private List<PalettedContainer<BlockState>> blocks;

        /**
         * Currently loaded chunk object.
         */
        private LevelChunk chunk;

        /**
         * Unload count seen for the target chunk.
         */
        private final AtomicInteger unloads = new AtomicInteger();

        /**
         * Unloads awaited so far.
         */
        private int awaitedUnloads;

        /**
         * Creates the fixture.
         *
         * @param context test context
         * @param offset  chunk offset from the test structure, distinct per test
         */
        private Fixture(final GameTestHelper context, final int offset) {
            this.context = context;
            this.world = context.getLevel();
            final ChunkPos anchor = ChunkPos.containing(context.absolutePos(BlockPos.ZERO));
            this.target = new ChunkPos(anchor.x() - offset, anchor.z() + offset);
            ServerChunkEvents.CHUNK_UNLOAD.register((unloadWorld, unloadChunk) -> {
                if (unloadWorld == world && unloadChunk.getPos().equals(target)) {
                    unloads.incrementAndGet();
                }
            });
        }

        /**
         * Creates the fixture and places its blocks.
         *
         * @param context test context
         * @param offset  chunk offset from the test structure, distinct per test
         * @return the fixture
         */
        static Fixture create(final GameTestHelper context, final int offset) {
            final Fixture fixture = new Fixture(context, offset);
            fixture.place();
            return fixture;
        }

        /**
         * Places a section of varied blocks and an empty chest in the target chunk.
         */
        private void place() {
            world.setChunkForced(target.x(), target.z(), true);
            chunk = world.getChunk(target.x(), target.z());
            // Deep underground, under the chunk's highest block: no snow settles on it and nothing near it ticks,
            // so the chunk sees no block edit of its own.
            baseY = (world.getMinY() + 32) & ~15;
            SectionTestSupport.placePattern(world, target, baseY, simpleStates());
            chest = target.getBlockAt(4, baseY + 16, 4);
            world.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS);
            blocks = copySections(chunk);
        }

        /**
         * Starts the steps that store the chunk, unload it and load it again with a released block payload.
         *
         * @return the task runner, for the test's own steps
         */
        net.minecraft.gametest.framework.GameTestSequence storeAndReloadReleased() {
            return context.startSequence()
                    .thenWaitUntil(() -> {
                        chunk.markUnsaved();
                        world.getChunkSource().save(false);
                        AsyncCisSaveManager.flushAndClose(world);
                    })
                    // The save's completion marks the delta clean on a later tick; unloading before it would
                    // capture the chunk again and hand the dirty delta to the reload.
                    .thenWaitUntil(() -> {
                        final ChunkDelta<?, ?> delta = liveDelta();
                        context.assertTrue(delta != null && !delta.isDirty(),
                                Component.literal("Waiting for the first save of chunk " + target + " to complete."));
                    })
                    .thenWaitUntil(this::unload)
                    .thenWaitUntil(this::awaitUnloaded)
                    .thenWaitUntil(this::load)
                    .thenWaitUntil(() -> {
                        chunk = world.getChunk(target.x(), target.z());
                        final ChunkDelta<?, ?> delta = liveDelta();
                        context.assertTrue(delta != null && delta.isBlockPayloadReleased()
                                        && delta.getBlockChangesCount() == 0,
                                Component.literal("Reloaded chunk " + target + " did not release its blocks: "
                                        + (delta == null ? "no delta" : "released=" + delta.isBlockPayloadReleased()
                                        + " blocks=" + delta.getBlockChangesCount() + " dirty=" + delta.isDirty())
                                        + "."));
                    });
        }

        /**
         * Records the gold-block edit in a delta without touching the world, and expects it in the stored blocks.
         *
         * @param delta delta receiving the edit
         */
        void recordEdit(final ChunkDelta<BlockState, CompoundTag> delta) {
            final BlockPos at = editedPos();
            final BlockState gold = Blocks.GOLD_BLOCK.defaultBlockState();
            delta.addBlockChange(at.getX() & 15, at.getY(), at.getZ() & 15, gold);
            blocks.get((at.getY() - world.getMinY()) >> 4)
                    .getAndSet(at.getX() & 15, at.getY() & 15, at.getZ() & 15, gold);
        }

        /**
         * Returns the position of the edited block.
         *
         * @return edited position
         */
        BlockPos editedPos() {
            return target.getBlockAt(3, baseY + 5, 7);
        }

        /**
         * Edits a block to gold and puts a diamond in the chest.
         */
        void fillChest() {
            world.setBlock(editedPos(), Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
            blocks = copySections(chunk);
            if (!(world.getBlockEntity(chest) instanceof ChestBlockEntity chestEntity)) {
                throw context.assertionException(Component.literal("No chest at " + chest + "."));
            }
            chestEntity.setItem(0, new ItemStack(Items.DIAMOND));
            chestEntity.setChanged();
        }

        /**
         * Releases the forced ticket so the chunk unloads.
         */
        void unload() {
            world.setChunkForced(target.x(), target.z(), false);
        }

        /**
         * Waits for the next unload of the chunk.
         */
        void awaitUnloaded() {
            context.assertTrue(unloads.get() > awaitedUnloads,
                    Component.literal("Waiting for chunk " + target + " to unload."));
            awaitedUnloads = unloads.get();
        }

        /**
         * Forces and loads the chunk.
         */
        void load() {
            world.setChunkForced(target.x(), target.z(), true);
            world.getChunk(target.x(), target.z());
        }

        /**
         * Releases the forced ticket at the end of a test.
         */
        void unforce() {
            world.setChunkForced(target.x(), target.z(), false);
        }

        /**
         * Returns the delta attached to the last loaded chunk object.
         *
         * @return the delta, or {@code null}
         */
        ChunkDelta<?, ?> liveDelta() {
            return chunk instanceof ChunkisDeltaDuck duck ? duck.chunkis$getDelta() : null;
        }

        /**
         * Asserts the reloaded chunk has every placed block and the diamond.
         */
        void assertReloadedWithChange() {
            final LevelChunk reloaded = world.getChunk(target.x(), target.z());
            context.assertTrue(reloaded != chunk, Component.literal("Chunk " + target + " was not reloaded."));
            final List<PalettedContainer<BlockState>> restored = copySections(reloaded);
            for (int section = 0; section < blocks.size(); section++) {
                for (int i = 0; i < 16 * 16 * 16; i++) {
                    final BlockState want = blocks.get(section).get(i & 15, i >> 8, (i >> 4) & 15);
                    final BlockState got = restored.get(section).get(i & 15, i >> 8, (i >> 4) & 15);
                    if (want != got) {
                        throw context.assertionException(Component.literal("Section " + section + " position " + i
                                + " reloaded as " + got + ", placed as " + want + "."));
                    }
                }
            }
            context.assertTrue(world.getBlockEntity(chest) instanceof ChestBlockEntity chestEntity
                            && chestEntity.getItem(0).is(Items.DIAMOND),
                    Component.literal("The chest at " + chest + " lost its diamond."));
            chunk = reloaded;
        }

        /**
         * Asserts the stored entry has every placed block and holds the entity.
         *
         * @param entity UUID of the entity the entry must hold
         */
        void assertStoredEntry(final UUID entity) {
            final ChunkDelta<BlockState, CompoundTag> stored = storage().load(cisPos());
            final int bottomY = world.getMinY();
            final int[] nonAir = {0};
            stored.forEachBlock((x, y, z, state) -> {
                final int section = (y - bottomY) >> 4;
                final BlockState want = blocks.get(section).get(x, y & 15, z);
                if (want != state) {
                    throw context.assertionException(Component.literal("Stored block at " + x + "," + y + "," + z + " is "
                            + state + ", placed as " + want + "."));
                }
                nonAir[0]++;
            });
            int expected = 0;
            for (final PalettedContainer<BlockState> section : blocks) {
                for (int i = 0; i < 16 * 16 * 16; i++) {
                    expected += section.get(i & 15, i >> 8, (i >> 4) & 15).isAir() ? 0 : 1;
                }
            }
            context.assertTrue(nonAir[0] == expected, Component.literal("Stored entry holds " + nonAir[0]
                    + " blocks, expected " + expected + "."));
            final List<UUID> uuids = new ArrayList<>();
            stored.forEachEntity(nbt -> EntityPayloadNbt.findUuid(nbt).ifPresent(uuids::add));
            stored.forEachPendingEntity(nbt -> EntityPayloadNbt.findUuid(nbt).ifPresent(uuids::add));
            context.assertTrue(uuids.contains(entity), Component.literal("Stored entry lacks entity " + entity + "."));
        }

        /**
         * Returns the world's CIS storage.
         *
         * @return storage
         */
        io.liparakis.chunkis.storage.io.CisStorage<Block, BlockState, net.minecraft.world.level.block.state.properties.Property<?>,
                CompoundTag> storage() {
            return FabricCisStorageHelper.getStorage(world);
        }

        /**
         * Returns the storage position of the chunk.
         *
         * @return storage position
         */
        CisChunkPos cisPos() {
            return FabricCisStorageHelper.toStoragePos(target);
        }

        /**
         * Spawns a pig in the test chunk.
         *
         * @return its UUID
         */
        UUID spawnPig() {
            final Pig entity = Objects.requireNonNull(EntityTypes.PIG.create(world, EntitySpawnReason.COMMAND), "Pig fixture could not be created");
            final BlockPos at = target.getBlockAt(8, baseY + 20, 8);
            entity.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
            world.addFreshEntity(entity);
            return entity.getUUID();
        }

        /**
         * Expects the stored entry to hold the chest and the light and heightmaps a full capture writes.
         */
        void assertStoredNonBlockState() {
            final ChunkDelta<BlockState, CompoundTag> stored = storage().load(cisPos());
            final CompoundTag metadata = stored.getChunkMetadata();
            context.assertTrue(metadata != null && metadata.contains(CisNbtUtil.STORED_SECTIONS_KEY)
                            && metadata.contains(CisNbtUtil.STORED_HEIGHTMAPS_KEY),
                    Component.literal("Stored entry of " + target + " lost its light or heightmaps."));
            context.assertTrue(!stored.getBlockEntities().isEmpty(),
                    Component.literal("Stored entry of " + target + " lost the chest."));
        }
    }

    /**
     * Picks block states that never change by themselves: dyed wool, concrete, terracotta and stained glass.
     *
     * @return the states
     */
    private static List<BlockState> simpleStates() {
        final List<BlockState> states = new ArrayList<>();
        for (final Block block : BuiltInRegistries.BLOCK) {
            final String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
            if (path.endsWith("_wool") || path.endsWith("_concrete") || path.endsWith("_terracotta")
                    && !path.endsWith("glazed_terracotta") || path.endsWith("_stained_glass")) {
                states.add(block.defaultBlockState());
            }
        }
        return states;
    }


}
