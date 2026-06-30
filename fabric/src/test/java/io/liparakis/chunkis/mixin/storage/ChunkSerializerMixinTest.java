package io.liparakis.chunkis.mixin.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
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

    @SuppressWarnings("unchecked")
    private static Set<Object> newIdentityMarkerSet() throws ReflectiveOperationException {
        final Method method = ChunkSerializerMixin.class.getDeclaredMethod("chunkis$newIdentityMarkerSet");
        method.setAccessible(true);
        return (Set<Object>) method.invoke(null);
    }
}
