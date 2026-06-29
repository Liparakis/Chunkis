package io.liparakis.chunkis.debug.util;

import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import net.minecraft.util.math.ChunkPos;

/**
 * Small adapter helpers for the Fabric-side debug key call sites.
 */
public final class DebugChunkKeys {

    private DebugChunkKeys() {
        throw new AssertionError("Utility class");
    }

    public static DebugChunkKey of(final ChunkPos pos) {
        return new DebugChunkKey(pos.x, pos.z);
    }

    public static DebugChunkKey of(final int chunkX, final int chunkZ) {
        return new DebugChunkKey(chunkX, chunkZ);
    }
}
