package io.liparakis.chunkis.debug.util;

import io.liparakis.chunkis.mixin.accessor.ChunkSectionAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

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
    public static int countNonEmptySections(final ChunkAccess chunk) {
        return chunk == null ? 0 : countNonEmptySections(chunk.getSections());
    }

    /**
     * Counts the number of non-empty sections in the given sections array.
     *
     * @param sections target sections array, may be null
     * @return count of non-empty sections
     */
    public static int countNonEmptySections(final LevelChunkSection[] sections) {
        if (sections == null) {
            return 0;
        }
        int count = 0;
        for (final LevelChunkSection section : sections) {
            if (section != null && !section.hasOnlyAir()) {
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
    public static int countNonAirBlocks(final ChunkAccess chunk) {
        return chunk == null ? 0 : countNonAirBlocks(chunk.getSections());
    }

    /**
     * Counts the total number of non-air block states in the given sections array.
     *
     * @param sections target sections array, may be null
     * @return count of non-air blocks
     */
    public static int countNonAirBlocks(final LevelChunkSection[] sections) {
        if (sections == null) {
            return 0;
        }

        int count = 0;
        for (final LevelChunkSection section : sections) {
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            count += ((ChunkSectionAccessor) section).chunkis$getNonEmptyBlockCount();
        }
        return count;
    }

    /**
     * Generates a descriptive debug text summary of the given chunk's contents.
     *
     * @param chunk target chunk to summarize, may be null
     * @return debug summary string
     */
    public static String summarize(final ChunkAccess chunk) {
        if (chunk == null) {
            return "sections=0, nonAirBlocks=0, status=<null>, empty=true";
        }
        return "sections=" + countNonEmptySections(chunk)
                + ", nonAirBlocks=" + countNonAirBlocks(chunk)
                + ", status=" + chunk.getPersistedStatus()
                + ", empty=" + (countNonEmptySections(chunk) == 0);
    }
}
