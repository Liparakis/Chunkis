package io.liparakis.chunkis.debug.util;

import net.minecraft.block.BlockState;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;

public final class ChunkSectionDebugUtil {

    private ChunkSectionDebugUtil() {
        throw new AssertionError("Utility class");
    }

    public static int countNonEmptySections(final Chunk chunk) {
        return chunk == null ? 0 : countNonEmptySections(chunk.getSectionArray());
    }

    public static int countNonEmptySections(final ChunkSection[] sections) {
        if (sections == null) {
            return 0;
        }
        int count = 0;
        for (final ChunkSection section : sections) {
            if (section != null && !section.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    public static int countNonAirBlocks(final Chunk chunk) {
        return chunk == null ? 0 : countNonAirBlocks(chunk.getSectionArray());
    }

    public static int countNonAirBlocks(final ChunkSection[] sections) {
        if (sections == null) {
            return 0;
        }

        int count = 0;
        for (final ChunkSection section : sections) {
            if (section == null || section.isEmpty()) {
                continue;
            }
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final BlockState state = section.getBlockState(x, y, z);
                        if (state != null && !state.isAir()) {
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    public static String summarize(final Chunk chunk) {
        if (chunk == null) {
            return "sections=0, nonAirBlocks=0, status=<null>, empty=true";
        }
        return "sections=" + countNonEmptySections(chunk)
                + ", nonAirBlocks=" + countNonAirBlocks(chunk)
                + ", status=" + chunk.getStatus()
                + ", empty=" + (countNonEmptySections(chunk) == 0);
    }
}
