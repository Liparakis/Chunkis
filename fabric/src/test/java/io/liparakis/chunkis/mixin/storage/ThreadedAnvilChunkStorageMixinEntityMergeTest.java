package io.liparakis.chunkis.mixin.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.entity.capture.LiveEntitySnapshotCapture;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/**
 * Test class for entity merging behavior in {@code ThreadedAnvilChunkStorageMixin}.
 */
final class ThreadedAnvilChunkStorageMixinEntityMergeTest {

    /**
     * Helper method to invoke the private static method {@code copyUnresolvedPendingEntities} in
     * {@link LiveEntitySnapshotCapture} via reflection.
     *
     * @param existingDelta the existing {@link ChunkDelta} containing previous entity snapshots
     * @param liveEntities  the list of live entity snapshots
     * @return the list of merged entities
     * @throws Exception if reflection or execution fails
     */
    private static List<CompoundTag> invokeMerge(
            final ChunkDelta<Object, CompoundTag> existingDelta,
            final List<CompoundTag> liveEntities
    ) throws Exception {
        final ChunkDelta<Object, CompoundTag> targetDelta = new ChunkDelta<>();
        targetDelta.setEntities(liveEntities, false);
        final Set<String> liveEntityUuids = liveEntities.stream()
                .map(ThreadedAnvilChunkStorageMixinEntityMergeTest::uuidOf)
                .map(UUID::toString)
                .collect(java.util.stream.Collectors.toSet());

        final List<CompoundTag> pending = new java.util.ArrayList<>();
        existingDelta.forEachPendingEntity(pending::add);
        final Method method = LiveEntitySnapshotCapture.class.getDeclaredMethod(
                "copyUnresolvedPendingEntities",
                net.minecraft.server.level.ServerLevel.class,
                List.class,
                Set.class,
                ChunkDelta.class
        );
        method.setAccessible(true);
        method.invoke(null, null, pending, liveEntityUuids, targetDelta);
        return targetDelta.getEntitiesList();
    }

    /**
     * Creates a dummy NbtCompound representing an entity with the specified UUID.
     *
     * @param uuid the {@link UUID} of the entity
     * @return the {@link CompoundTag} containing the entity UUID
     */
    private static CompoundTag entityNbt(final UUID uuid) {
        final CompoundTag nbt = new CompoundTag();
        nbt.putIntArray("UUID", UUIDUtil.uuidToIntArray(uuid));
        return nbt;
    }

    /**
     * Extracts the UUID of an entity from its NbtCompound representation.
     *
     * @param nbt the {@link CompoundTag} of the entity
     * @return the {@link UUID} of the entity
     * @throws java.util.NoSuchElementException if the UUID is missing or invalid
     */
    private static UUID uuidOf(final CompoundTag nbt) {
        return nbt.getIntArray("UUID")
                .map(UUIDUtil::uuidFromIntArray)
                .orElseThrow();
    }

    /**
     * Tests that unresolved pending entities in the existing delta are retained when capturing live entities.
     *
     * @throws Exception if reflection or execution fails
     */
    @Test
    void keepsUnresolvedPendingEntitiesWhenCapturingLiveEntities() throws Exception {
        final UUID restoredLiveUuid = UUID.randomUUID();
        final UUID unresolvedPendingUuid = UUID.randomUUID();

        final ChunkDelta<Object, CompoundTag> existingDelta = new ChunkDelta<>();
        existingDelta.setEntities(
                List.of(
                        entityNbt(restoredLiveUuid),
                        entityNbt(unresolvedPendingUuid)
                ), false
        );

        final List<CompoundTag> merged = invokeMerge(existingDelta, List.of(entityNbt(restoredLiveUuid)));

        assertEquals(2, merged.size());
        assertEquals(restoredLiveUuid, uuidOf(merged.get(0)));
        assertEquals(unresolvedPendingUuid, uuidOf(merged.get(1)));
    }
}
