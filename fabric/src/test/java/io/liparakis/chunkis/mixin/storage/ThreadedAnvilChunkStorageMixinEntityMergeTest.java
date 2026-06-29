package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.entity.capture.LiveEntitySnapshotCapture;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Uuids;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ThreadedAnvilChunkStorageMixinEntityMergeTest {

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

    private static NbtCompound entityNbt(final UUID uuid) {
        final NbtCompound nbt = new NbtCompound();
        nbt.putIntArray("UUID", Uuids.toIntArray(uuid));
        return nbt;
    }

    private static UUID uuidOf(final NbtCompound nbt) {
        return nbt.getIntArray("UUID").map(Uuids::toUuid).orElseThrow();
    }
}
