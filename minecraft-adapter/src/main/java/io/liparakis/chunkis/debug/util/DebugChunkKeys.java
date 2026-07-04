package io.liparakis.chunkis.debug.util;

import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import net.minecraft.util.math.ChunkPos;

/**
 * Small adapter helpers for the Fabric-side debug key call sites.
 */
public final class DebugChunkKeys {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private DebugChunkKeys() {
        throw new AssertionError("Utility class");
    }

    /**
     * Creates a DebugChunkKey from a Minecraft ChunkPos.
     *
     * @param pos the Minecraft chunk position to convert
     * @return a new DebugChunkKey container mapping the coordinates
     */
    public static DebugChunkKey of(final ChunkPos pos) {
        return new DebugChunkKey(pos.x, pos.z);
    }

    /**
     * Creates a DebugChunkKey from raw chunk coordinate offsets.
     *
     * @param chunkX chunk X position coordinate offset
     * @param chunkZ chunk Z position coordinate offset
     * @return a new DebugChunkKey container mapping the coordinates
     */
    public static DebugChunkKey of(final int chunkX, final int chunkZ) {
        return new DebugChunkKey(chunkX, chunkZ);
    }
}
