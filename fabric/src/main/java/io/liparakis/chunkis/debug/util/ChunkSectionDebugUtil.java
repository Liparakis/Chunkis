package io.liparakis.chunkis.debug.util;

import net.minecraft.block.BlockState;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;

/**
 * Debugging utilities for inspecting empty sections and non-air block counts in Minecraft chunk data.
 */
public final class ChunkSectionDebugUtil {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private ChunkSectionDebugUtil() {
        throw new AssertionError("Utility class");
    }

    /**
     * Counts the number of non-empty sections in the given chunk.
     *
     * @param chunk target chunk to scan, may be null
     * @return count of non-empty sections
     */
    public static int countNonEmptySections(final Chunk chunk) {
        return chunk == null ? 0 : countNonEmptySections(chunk.getSectionArray());
    }

    /**
     * Counts the number of non-empty sections in the given sections array.
     *
     * @param sections target sections array, may be null
     * @return count of non-empty sections
     */
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

    /**
     * Counts the total number of non-air block states in the given chunk.
     *
     * @param chunk target chunk to scan, may be null
     * @return count of non-air blocks
     */
    public static int countNonAirBlocks(final Chunk chunk) {
        return chunk == null ? 0 : countNonAirBlocks(chunk.getSectionArray());
    }

    /**
     * Counts the total number of non-air block states in the given sections array.
     *
     * @param sections target sections array, may be null
     * @return count of non-air blocks
     */
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

    /**
     * Generates a descriptive debug text summary of the given chunk's contents.
     *
     * @param chunk target chunk to summarize, may be null
     * @return debug summary string
     */
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
