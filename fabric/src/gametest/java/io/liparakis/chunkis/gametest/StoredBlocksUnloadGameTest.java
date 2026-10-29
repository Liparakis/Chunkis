package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.world.entity.capture.EntityPayloadNbt;
import io.liparakis.chunkis.world.entity.capture.VanillaEntitySaveCapture;
import io.liparakis.chunkis.world.tracking.save.AsyncCisSaveManager;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import io.liparakis.chunkis.world.tracking.state.GlobalChunkTracker;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;

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
    public void unloadOfReleasedChunkCopiesBlocksAndKeepsChange(final TestContext context) {
        final Fixture fixture = Fixture.create(context, 2700);
        fixture.storeAndReloadReleased()
                .createAndAdd(() -> {
                    fixture.fillChest();
                    fixture.unload();
                })
                .createAndAdd(fixture::awaitUnloaded)
                .createAndAdd(() -> {
                    final ChunkDelta<?, ?> unloadedDelta = fixture.liveDelta();
                    context.assertTrue(unloadedDelta != null && unloadedDelta.isBlockPayloadReleased(),
                            Text.literal("The unload save captured the blocks of chunk " + fixture.target
                                    + " instead of copying them."));
                    AsyncCisSaveManager.flushAndClose(fixture.world);
                    fixture.load();
                })
                .createAndAdd(fixture::assertReloadedWithChange)
                .createAndAdd(fixture::unforce)
                .completeIfSuccessful();
    }

    /**
     * Saves, after its chunk unloaded, a released tracked delta with a recorded block edit that vanilla's entity
     * save then changed, and expects the stored entry to keep every block, apply the edit and gain the entity.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 600)
    public void entitySaveAfterUnloadKeepsStoredBlocks(final TestContext context) {
        final Fixture fixture = Fixture.create(context, 2900);
        final UUID[] pig = new UUID[1];
        fixture.storeAndReloadReleased()
                .createAndAdd(fixture::unload)
                .createAndAdd(fixture::awaitUnloaded)
                .createAndAdd(() -> {
                    AsyncCisSaveManager.flushAndClose(fixture.world);
                    // The state an unload leaves while its save is still on its way: a released tracked delta that
                    // holds only the edits recorded on top of the stored blocks.
                    final ChunkDelta<BlockState, NbtCompound> tracked = fixture.storage()
                            .load(fixture.cisPos());
                    tracked.claimOwnership(ChunkTraceReason.EXPLICIT_CHUNKIS_MUTATION.name(), "gametest");
                    tracked.releaseBlockPayload();
                    fixture.recordEdit(tracked);
                    GlobalChunkTracker.addDelta(fixture.world, fixture.target, tracked, "gametest");

                    final PigEntity entity = EntityType.PIG.create(fixture.world, SpawnReason.LOAD);
                    final BlockPos at = fixture.target.getBlockPos(8, fixture.baseY + 20, 8);
                    entity.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                    pig[0] = entity.getUuid();
                    VanillaEntitySaveCapture.capture(fixture.world, fixture.target, List.of(entity));
                    context.assertTrue(tracked.isDirty() && tracked.isBlockPayloadReleased(),
                            Text.literal("Vanilla's entity save did not land in the tracked delta."));
                    context.assertTrue(FabricCisStorageHelper.saveTrackedDelta(fixture.world, fixture.storage(),
                                    fixture.target, tracked),
                            Text.literal("The tracked delta holding stored blocks could not be saved."));
                    fixture.assertStoredEntry(pig[0]);
                })
                .completeIfSuccessful();
    }

    /**
     * Reloads a chunk right after it unloaded with a changed chest, without waiting for its save, and expects
     * the change and every block.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 600)
    public void fastReloadAfterUnloadKeepsChange(final TestContext context) {
        final Fixture fixture = Fixture.create(context, 3100);
        fixture.storeAndReloadReleased()
                .createAndAdd(() -> {
                    fixture.fillChest();
                    fixture.unload();
                })
                .createAndAdd(fixture::awaitUnloaded)
                .createAndAdd(fixture::load)
                .createAndAdd(fixture::assertReloadedWithChange)
                .createAndAdd(fixture::unforce)
                .completeIfSuccessful();
    }

    /**
     * Unloads a chunk with a changed chest whose save is refused, and expects the reload to rebuild it from
     * the stored blocks and the tracked change.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 600)
    public void failedUnloadSaveKeepsChangeForReload(final TestContext context) {
        final Fixture fixture = Fixture.create(context, 3300);
        fixture.storeAndReloadReleased()
                .createAndAdd(() -> {
                    fixture.fillChest();
                    // Every later write of this test-only chunk fails, like a disk error would.
                    fixture.storage()
                            .protectFromOverwrite(fixture.cisPos());
                    fixture.unload();
                })
                .createAndAdd(fixture::awaitUnloaded)
                .createAndAdd(() -> {
                    AsyncCisSaveManager.flushAndClose(fixture.world);
                    final ChunkDelta<?, ?> tracked = GlobalChunkTracker.getDelta(fixture.world, fixture.target);
                    context.assertTrue(tracked != null && tracked.isDirty() && tracked.isBlockPayloadReleased(),
                            Text.literal("The refused save did not leave the change tracked."));
                    fixture.load();
                })
                .createAndAdd(fixture::assertReloadedWithChange)
                .createAndAdd(fixture::unforce)
                .completeIfSuccessful();
    }

    /**
     * One test chunk: a section of varied blocks and a chest, stored and reloaded so its delta releases its
     * block instructions.
     */
    private static final class Fixture {

        /**
         * Test context.
         */
        private final TestContext context;

        /**
         * Test world.
         */
        private final ServerWorld world;

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
        private WorldChunk chunk;

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
        private Fixture(final TestContext context, final int offset) {
            this.context = context;
            this.world = context.getWorld();
            final ChunkPos anchor = new ChunkPos(context.getAbsolutePos(BlockPos.ORIGIN));
            this.target = new ChunkPos(anchor.x - offset, anchor.z + offset);
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
        static Fixture create(final TestContext context, final int offset) {
            final Fixture fixture = new Fixture(context, offset);
            fixture.place();
            return fixture;
        }

        /**
         * Places a section of varied blocks and an empty chest in the target chunk.
         */
        private void place() {
            world.setChunkForced(target.x, target.z, true);
            chunk = world.getChunk(target.x, target.z);
            // Deep underground, under the chunk's highest block: no snow settles on it and nothing near it ticks,
            // so the chunk sees no block edit of its own.
            baseY = (world.getBottomY() + 32) & ~15;
            final List<BlockState> states = simpleStates();
            for (int i = 0; i < 16 * 16 * 16; i++) {
                final BlockPos pos = target.getBlockPos(i & 15, baseY + (i >> 8), (i >> 4) & 15);
                world.setBlockState(pos, states.get(i % states.size()), Block.NOTIFY_LISTENERS);
            }
            chest = target.getBlockPos(4, baseY + 16, 4);
            world.setBlockState(chest, Blocks.CHEST.getDefaultState(), Block.NOTIFY_LISTENERS);
            blocks = copySections(chunk);
        }

        /**
         * Starts the steps that store the chunk, unload it and load it again with a released block payload.
         *
         * @return the task runner, for the test's own steps
         */
        net.minecraft.test.TimedTaskRunner storeAndReloadReleased() {
            return context.createTimedTaskRunner()
                    .createAndAdd(() -> {
                        chunk.markNeedsSaving();
                        world.getChunkManager().save(false);
                        AsyncCisSaveManager.flushAndClose(world);
                    })
                    // The save's completion marks the delta clean on a later tick; unloading before it would
                    // capture the chunk again and hand the dirty delta to the reload.
                    .createAndAdd(() -> context.assertTrue(liveDelta() != null && !liveDelta().isDirty(),
                            Text.literal("Waiting for the first save of chunk " + target + " to complete.")))
                    .createAndAdd(this::unload)
                    .createAndAdd(this::awaitUnloaded)
                    .createAndAdd(this::load)
                    .createAndAdd(() -> {
                        chunk = world.getChunk(target.x, target.z);
                        final ChunkDelta<?, ?> delta = liveDelta();
                        context.assertTrue(delta != null && delta.isBlockPayloadReleased()
                                        && delta.getBlockChangesCount() == 0,
                                Text.literal("Reloaded chunk " + target + " did not release its blocks: "
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
        void recordEdit(final ChunkDelta<BlockState, NbtCompound> delta) {
            final BlockPos at = editedPos();
            final BlockState gold = Blocks.GOLD_BLOCK.getDefaultState();
            delta.addBlockChange(at.getX() & 15, at.getY(), at.getZ() & 15, gold);
            blocks.get((at.getY() - world.getBottomY()) >> 4)
                    .swap(at.getX() & 15, at.getY() & 15, at.getZ() & 15, gold);
        }

        /**
         * Returns the position of the edited block.
         *
         * @return edited position
         */
        BlockPos editedPos() {
            return target.getBlockPos(3, baseY + 5, 7);
        }

        /**
         * Edits a block to gold and puts a diamond in the chest.
         */
        void fillChest() {
            world.setBlockState(editedPos(), Blocks.GOLD_BLOCK.getDefaultState(), Block.NOTIFY_LISTENERS);
            blocks = copySections(chunk);
            if (!(world.getBlockEntity(chest) instanceof ChestBlockEntity chestEntity)) {
                throw context.createError(Text.literal("No chest at " + chest + "."));
            }
            chestEntity.setStack(0, new ItemStack(Items.DIAMOND));
            chestEntity.markDirty();
        }

        /**
         * Releases the forced ticket so the chunk unloads.
         */
        void unload() {
            world.setChunkForced(target.x, target.z, false);
        }

        /**
         * Waits for the next unload of the chunk.
         */
        void awaitUnloaded() {
            context.assertTrue(unloads.get() > awaitedUnloads,
                    Text.literal("Waiting for chunk " + target + " to unload."));
            awaitedUnloads = unloads.get();
        }

        /**
         * Forces and loads the chunk.
         */
        void load() {
            world.setChunkForced(target.x, target.z, true);
            world.getChunk(target.x, target.z);
        }

        /**
         * Releases the forced ticket at the end of a test.
         */
        void unforce() {
            world.setChunkForced(target.x, target.z, false);
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
            final WorldChunk reloaded = world.getChunk(target.x, target.z);
            context.assertTrue(reloaded != chunk, Text.literal("Chunk " + target + " was not reloaded."));
            final List<PalettedContainer<BlockState>> restored = copySections(reloaded);
            for (int section = 0; section < blocks.size(); section++) {
                for (int i = 0; i < 16 * 16 * 16; i++) {
                    final BlockState want = blocks.get(section).get(i & 15, i >> 8, (i >> 4) & 15);
                    final BlockState got = restored.get(section).get(i & 15, i >> 8, (i >> 4) & 15);
                    if (want != got) {
                        throw context.createError(Text.literal("Section " + section + " position " + i
                                + " reloaded as " + got + ", placed as " + want + "."));
                    }
                }
            }
            context.assertTrue(world.getBlockEntity(chest) instanceof ChestBlockEntity chestEntity
                            && chestEntity.getStack(0).isOf(Items.DIAMOND),
                    Text.literal("The chest at " + chest + " lost its diamond."));
            chunk = reloaded;
        }

        /**
         * Asserts the stored entry has every placed block and holds the entity.
         *
         * @param entity UUID of the entity the entry must hold
         */
        void assertStoredEntry(final UUID entity) {
            final ChunkDelta<BlockState, NbtCompound> stored = storage().load(cisPos());
            final int bottomY = world.getBottomY();
            final int[] nonAir = {0};
            stored.forEachBlock((x, y, z, state) -> {
                final int section = (y - bottomY) >> 4;
                final BlockState want = blocks.get(section).get(x, y & 15, z);
                if (want != state) {
                    throw context.createError(Text.literal("Stored block at " + x + "," + y + "," + z + " is "
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
            context.assertTrue(nonAir[0] == expected, Text.literal("Stored entry holds " + nonAir[0]
                    + " blocks, expected " + expected + "."));
            final List<UUID> uuids = new ArrayList<>();
            stored.forEachEntity(nbt -> EntityPayloadNbt.findUuid(nbt).ifPresent(uuids::add));
            stored.forEachPendingEntity(nbt -> EntityPayloadNbt.findUuid(nbt).ifPresent(uuids::add));
            context.assertTrue(uuids.contains(entity), Text.literal("Stored entry lacks entity " + entity + "."));
        }

        /**
         * Returns the world's CIS storage.
         *
         * @return storage
         */
        io.liparakis.chunkis.storage.io.CisStorage<Block, BlockState, net.minecraft.state.property.Property<?>,
                NbtCompound> storage() {
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
    }

    /**
     * Picks block states that never change by themselves: dyed wool, concrete, terracotta and stained glass.
     *
     * @return the states
     */
    private static List<BlockState> simpleStates() {
        final List<BlockState> states = new ArrayList<>();
        for (final Block block : Registries.BLOCK) {
            final String path = Registries.BLOCK.getId(block).getPath();
            if (path.endsWith("_wool") || path.endsWith("_concrete") || path.endsWith("_terracotta")
                    && !path.endsWith("glazed_terracotta") || path.endsWith("_stained_glass")) {
                states.add(block.getDefaultState());
            }
        }
        return states;
    }

    /**
     * Copies every section's blocks.
     *
     * @param chunk chunk
     * @return per-section copies
     */
    private static List<PalettedContainer<BlockState>> copySections(final WorldChunk chunk) {
        final List<PalettedContainer<BlockState>> copies = new ArrayList<>();
        for (final ChunkSection section : chunk.getSectionArray()) {
            copies.add(section.getBlockStateContainer().copy());
        }
        return copies;
    }
}
