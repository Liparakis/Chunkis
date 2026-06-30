package io.liparakis.chunkis.mixin.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import java.lang.reflect.Method;
import net.minecraft.nbt.NbtCompound;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChunkSerializerMixinTest {

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

    @SuppressWarnings("unchecked")
    private static Set<Object> newIdentityMarkerSet() throws ReflectiveOperationException {
        final Method method = ChunkSerializerMixin.class.getDeclaredMethod("chunkis$newIdentityMarkerSet");
        method.setAccessible(true);
        return (Set<Object>) method.invoke(null);
    }

    private static boolean shouldResetProtoChunkToEmpty(final NbtCompound metadata)
            throws ReflectiveOperationException {
        final Method method = ChunkSerializerMixin.class.getDeclaredMethod(
                "chunkis$shouldResetProtoChunkToEmpty",
                Object.class
        );
        method.setAccessible(true);
        return (boolean) method.invoke(null, metadata);
    }
}
