package io.liparakis.chunkis.mixin.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import java.lang.reflect.Method;
import java.util.Set;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

/**
 * Test class for {@code ChunkSerializerMixin}.
 */
class ChunkSerializerMixinTest {

    /**
     * Helper method to invoke the private static method {@code chunkis$newIdentityMarkerSet} in
     * {@code ChunkSerializerMixin} via reflection.
     *
     * @return the set of identity markers
     * @throws ReflectiveOperationException if reflection fails
     */
    @SuppressWarnings("unchecked")
    private static Set<Object> newIdentityMarkerSet() throws ReflectiveOperationException {
        final Class<?> mixinClass = Class.forName("io.liparakis.chunkis.mixin.storage.ChunkSerializerMixin");
        final Method method = mixinClass.getDeclaredMethod("chunkis$newIdentityMarkerSet");
        method.setAccessible(true);
        return (Set<Object>) method.invoke(null);
    }

    /**
     * Helper method to invoke the private static method {@code chunkis$shouldResetProtoChunkToEmpty} in
     * {@code ChunkSerializerMixin} via reflection.
     *
     * @param metadata the NbtCompound representing metadata
     * @return true if the proto chunk should be reset to empty, false otherwise
     * @throws ReflectiveOperationException if reflection fails
     */
    private static boolean shouldResetProtoChunkToEmpty(final NbtCompound metadata)
            throws ReflectiveOperationException {
        final Class<?> mixinClass = Class.forName("io.liparakis.chunkis.mixin.storage.ChunkSerializerMixin");
        final Method method = mixinClass.getDeclaredMethod(
                "chunkis$shouldResetProtoChunkToEmpty",
                Object.class
        );
        method.setAccessible(true);
        return (boolean) method.invoke(null, metadata);
    }

    /**
     * Tests that the identity marker set returned by {@code chunkis$newIdentityMarkerSet()}
     * uses reference equality rather than object equality (e.g., adding two different String instances containing the
     * same
     * value succeeds for both, and removing one does not remove the other).
     *
     * @throws ReflectiveOperationException if reflection fails
     */
    @Test
    void identityMarkerSetUsesReferenceEquality() throws ReflectiveOperationException {
        final Set<Object> markers = newIdentityMarkerSet();
        final Object first = new String("same");
        final Object second = new String("same");

        assertTrue(markers.add(first));
        assertTrue(markers.add(second));
        assertTrue(markers.remove(first));
        assertFalse(markers.remove(first));
        assertTrue(markers.remove(second));
    }

    /**
     * Tests that an authoritative full baseline chunk does not trigger resetting the proto chunk to empty.
     *
     * @throws ReflectiveOperationException if reflection fails
     */
    @Test
    void authoritativeFullBaselineDoesNotResetProtoChunkToEmpty() throws ReflectiveOperationException {
        final NbtCompound metadata = CisNbtUtil.createChunkMetadataTakingOwnership(
                null,
                true,
                true,
                null,
                false
        );

        assertFalse(shouldResetProtoChunkToEmpty(metadata));
    }

    /**
     * Tests that a sparse replay without a persisted base chunk triggers resetting the proto chunk to empty.
     *
     * @throws ReflectiveOperationException if reflection fails
     */
    @Test
    void sparseReplayWithoutPersistedBaseResetsProtoChunkToEmpty() throws ReflectiveOperationException {
        final NbtCompound metadata = CisNbtUtil.createChunkMetadataTakingOwnership(
                null,
                true,
                false,
                null,
                false
        );

        assertTrue(shouldResetProtoChunkToEmpty(metadata));
    }
}
