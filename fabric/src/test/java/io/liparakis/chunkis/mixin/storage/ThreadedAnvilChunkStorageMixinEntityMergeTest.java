package io.liparakis.chunkis.mixin.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.entity.capture.LiveEntitySnapshotCapture;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Uuids;
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
    private static List<NbtCompound> invokeMerge(
            final ChunkDelta<Object, NbtCompound> existingDelta,
            final List<NbtCompound> liveEntities
    ) throws Exception {
        final ChunkDelta<Object, NbtCompound> targetDelta = new ChunkDelta<>();
        targetDelta.setEntities(liveEntities, false);
        final Set<String> liveEntityUuids = liveEntities.stream()
                .map(ThreadedAnvilChunkStorageMixinEntityMergeTest::uuidOf)
                .map(UUID::toString)
                .collect(java.util.stream.Collectors.toSet());

        final Method method = LiveEntitySnapshotCapture.class.getDeclaredMethod(
                "copyUnresolvedPendingEntities",
                ChunkDelta.class,
                Set.class,
                ChunkDelta.class
        );
        method.setAccessible(true);
        method.invoke(null, existingDelta, liveEntityUuids, targetDelta);
        return targetDelta.getEntitiesList();
    }

    /**
     * Creates a dummy NbtCompound representing an entity with the specified UUID.
     *
     * @param uuid the {@link UUID} of the entity
     * @return the {@link NbtCompound} containing the entity UUID
     */
    private static NbtCompound entityNbt(final UUID uuid) {
        final NbtCompound nbt = new NbtCompound();
        nbt.putIntArray("UUID", Uuids.toIntArray(uuid));
        return nbt;
    }

    /**
     * Extracts the UUID of an entity from its NbtCompound representation.
     *
     * @param nbt the {@link NbtCompound} of the entity
     * @return the {@link UUID} of the entity
     * @throws java.util.NoSuchElementException if the UUID is missing or invalid
     */
    private static UUID uuidOf(final NbtCompound nbt) {
        return nbt.getIntArray("UUID")
                .map(Uuids::toUuid)
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

        final ChunkDelta<Object, NbtCompound> existingDelta = new ChunkDelta<>();
        existingDelta.setEntities(
                List.of(
                        entityNbt(restoredLiveUuid),
                        entityNbt(unresolvedPendingUuid)
                ), false
        );

        final List<NbtCompound> merged = invokeMerge(existingDelta, List.of(entityNbt(restoredLiveUuid)));

        assertEquals(2, merged.size());
        assertEquals(restoredLiveUuid, uuidOf(merged.get(0)));
        assertEquals(unresolvedPendingUuid, uuidOf(merged.get(1)));
    }
}
