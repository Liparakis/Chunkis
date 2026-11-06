package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.world.entity.replay.ScheduledEntityReplayQueue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

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
    public void replayKeepsEachChunksEntitiesTogether(final GameTestHelper context) {
        final ServerLevel world = context.getLevel();
        final ChunkPos origin = ChunkPos.containing(context.absolutePos(BlockPos.ZERO));
        final List<ChunkPos> chunks = new ArrayList<>();
        for (int x = 0; x < CHUNKS_PER_SIDE; x++) {
            for (int z = 0; z < CHUNKS_PER_SIDE; z++) {
                final ChunkPos chunk = new ChunkPos(origin.x() + 5300 + x, origin.z() + z);
                chunks.add(chunk);
                world.setChunkForced(chunk.x(), chunk.z(), true);
            }
        }
        final Map<UUID, ChunkPos> queued = new HashMap<>();
        final List<ChunkPos> replayOrder = new ArrayList<>();
        context.startSequence()
                .thenWaitUntil(() -> {
                    for (final ChunkPos chunk : chunks) {
                        context.assertTrue(world.canSpawnEntitiesInChunk(chunk),
                                Component.literal("Waiting for chunk " + chunk + " to tick entities."));
                    }
                })
                .thenWaitUntil(() -> {
                    for (final ChunkPos chunk : chunks) {
                        for (int i = 0; i < ENTITIES_PER_CHUNK; i++) {
                            final UUID uuid = UUID.randomUUID();
                            queued.put(uuid, chunk);
                            ScheduledEntityReplayQueue.schedule(world, chunk, uuid.toString(), pig(world, chunk, uuid));
                        }
                    }
                })
                .thenWaitUntil(() -> {
                    final List<ChunkPos> order = new ArrayList<>();
                    for (final Entity entity : world.getAllEntities()) {
                        final ChunkPos chunk = queued.get(entity.getUUID());
                        if (chunk != null) {
                            order.add(chunk);
                        }
                    }
                    context.assertTrue(order.size() == queued.size(),
                            Component.literal("Waiting for replay: " + order.size() + " of " + queued.size() + "."));
                    replayOrder.addAll(order);
                })
                .thenWaitUntil(() -> {
                    int chunkChanges = 0;
                    for (int i = 1; i < replayOrder.size(); i++) {
                        if (!replayOrder.get(i).equals(replayOrder.get(i - 1))) {
                            chunkChanges++;
                        }
                    }
                    context.assertTrue(chunkChanges == chunks.size() - 1,
                            Component.literal("Replay mixed its chunks: " + chunkChanges + " changes of chunk in "
                                    + replayOrder.size() + " entities, expected " + (chunks.size() - 1) + "."));
                })
                .thenWaitUntil(() -> {
                    // Collected first: discarding changes the list being walked.
                    final List<Entity> replayed = new ArrayList<>();
                    for (final Entity entity : world.getAllEntities()) {
                        if (queued.containsKey(entity.getUUID())) {
                            replayed.add(entity);
                        }
                    }
                    replayed.forEach(Entity::discard);
                    chunks.forEach(chunk -> world.setChunkForced(chunk.x(), chunk.z(), false));
                })
                .thenSucceed();
    }

    /**
     * Builds a pig's payload at the middle of a chunk.
     *
     * @param world world the pig belongs to
     * @param chunk chunk to place it in
     * @param uuid  the pig's UUID
     * @return its entity NBT
     */
    private static CompoundTag pig(final ServerLevel world, final ChunkPos chunk, final UUID uuid) {
        final ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(chunk.getMiddleBlockX() + 0.5));
        pos.add(DoubleTag.valueOf(world.getSeaLevel() + 40.0));
        pos.add(DoubleTag.valueOf(chunk.getMiddleBlockZ() + 0.5));
        final CompoundTag nbt = new CompoundTag();
        nbt.putString("id", "minecraft:pig");
        nbt.putIntArray("UUID", UUIDUtil.uuidToIntArray(uuid));
        nbt.put("Pos", pos);
        return nbt;
    }
}
