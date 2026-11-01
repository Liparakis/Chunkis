package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.world.entity.replay.ScheduledEntityReplayQueue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

/**
 * Covers the order {@link ScheduledEntityReplayQueue} replays in. Entities tick in the order they were added, so
 * mixing chunks here makes every entity tick slower for as long as they stay loaded.
 */
@SuppressWarnings("unused")
public final class ScheduledEntityReplayGameTest {

    /**
     * Chunks the test spreads entities over, along each axis.
     */
    private static final int CHUNKS_PER_SIDE = 3;

    /**
     * Entities replayed into each chunk. Their random UUIDs would scatter them through a hash-ordered queue.
     */
    private static final int ENTITIES_PER_CHUNK = 6;

    /**
     * Queues entities for a grid of chunks and expects each chunk's entities to end up together in the world's
     * entity list.
     *
     * @param context the game test context
     */
    @GameTest(maxTicks = 400)
    public void replayKeepsEachChunksEntitiesTogether(final TestContext context) {
        final ServerWorld world = context.getWorld();
        final ChunkPos origin = new ChunkPos(context.getAbsolutePos(BlockPos.ORIGIN));
        final List<ChunkPos> chunks = new ArrayList<>();
        for (int x = 0; x < CHUNKS_PER_SIDE; x++) {
            for (int z = 0; z < CHUNKS_PER_SIDE; z++) {
                final ChunkPos chunk = new ChunkPos(origin.x + 5300 + x, origin.z + z);
                chunks.add(chunk);
                world.setChunkForced(chunk.x, chunk.z, true);
            }
        }
        final Map<UUID, ChunkPos> queued = new HashMap<>();
        final List<ChunkPos> replayOrder = new ArrayList<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    for (final ChunkPos chunk : chunks) {
                        context.assertTrue(world.canSpawnEntitiesAt(chunk),
                                Text.literal("Waiting for chunk " + chunk + " to tick entities."));
                    }
                })
                .createAndAdd(() -> {
                    for (final ChunkPos chunk : chunks) {
                        for (int i = 0; i < ENTITIES_PER_CHUNK; i++) {
                            final UUID uuid = UUID.randomUUID();
                            queued.put(uuid, chunk);
                            ScheduledEntityReplayQueue.schedule(world, chunk, uuid.toString(), pig(world, chunk, uuid));
                        }
                    }
                })
                .createAndAdd(() -> {
                    final List<ChunkPos> order = new ArrayList<>();
                    for (final Entity entity : world.iterateEntities()) {
                        final ChunkPos chunk = queued.get(entity.getUuid());
                        if (chunk != null) {
                            order.add(chunk);
                        }
                    }
                    context.assertTrue(order.size() == queued.size(),
                            Text.literal("Waiting for replay: " + order.size() + " of " + queued.size() + "."));
                    replayOrder.addAll(order);
                })
                .createAndAdd(() -> {
                    int chunkChanges = 0;
                    for (int i = 1; i < replayOrder.size(); i++) {
                        if (!replayOrder.get(i).equals(replayOrder.get(i - 1))) {
                            chunkChanges++;
                        }
                    }
                    context.assertTrue(chunkChanges == chunks.size() - 1,
                            Text.literal("Replay mixed its chunks: " + chunkChanges + " changes of chunk in "
                                    + replayOrder.size() + " entities, expected " + (chunks.size() - 1) + "."));
                })
                .createAndAdd(() -> {
                    // Collected first: discarding changes the list being walked.
                    final List<Entity> replayed = new ArrayList<>();
                    for (final Entity entity : world.iterateEntities()) {
                        if (queued.containsKey(entity.getUuid())) {
                            replayed.add(entity);
                        }
                    }
                    replayed.forEach(Entity::discard);
                    chunks.forEach(chunk -> world.setChunkForced(chunk.x, chunk.z, false));
                })
                .completeIfSuccessful();
    }

    /**
     * Builds a pig's payload at the middle of a chunk.
     *
     * @param world world the pig belongs to
     * @param chunk chunk to place it in
     * @param uuid  the pig's UUID
     * @return its entity NBT
     */
    private static NbtCompound pig(final ServerWorld world, final ChunkPos chunk, final UUID uuid) {
        final NbtList pos = new NbtList();
        pos.add(NbtDouble.of(chunk.getCenterX() + 0.5));
        pos.add(NbtDouble.of(world.getSeaLevel() + 40.0));
        pos.add(NbtDouble.of(chunk.getCenterZ() + 0.5));
        final NbtCompound nbt = new NbtCompound();
        nbt.putString("id", "minecraft:pig");
        nbt.putIntArray("UUID", Uuids.toIntArray(uuid));
        nbt.put("Pos", pos);
        return nbt;
    }
}
