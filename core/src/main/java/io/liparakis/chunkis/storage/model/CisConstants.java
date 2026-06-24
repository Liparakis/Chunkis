package io.liparakis.chunkis.storage.model;

/**
 * Central constants for CIS (Chunk Information Storage)
 * system.
 * <p>
 * This class provides:
 * - File format constants (magic numbers, versions)
 * - Geometry constants (section sizes, region sizes)
 *
 * @version 1
 * @author Liparakis
 */
public final class CisConstants {

    /**
     * Magic number for CIS files: "CIS4" in ASCII (0x43495334)
     * Used to validate file format and detect corruption.
     */
    public static final int MAGIC = 0x43495334;

    /**
     * Current CIS format version.
     * V9: Adds chunk-level metadata (for example structure starts/references)
     * so deterministic structure worldgen remains idempotent across reloads.
     * V10: Stores NBT payloads raw inside the CIS blob instead of individually
     * compressing them, so the outer chunk compression is the only compression pass.
     * V11: Stores authoritative chunk snapshots in the CIS block payload. Missing
     * block entries now mean air at restore time; old formats must migrate forward
     * before normal runtime load.
     * Increment when making breaking changes to the serialization format.
     */
    public static final int VERSION = 11;

    /**
     * Size of one dimension of a chunk section (16 blocks).
     * Minecraft chunk sections are 16x16x16 cubes.
     */
    public static final int SECTION_SIZE = 16;

    /**
     * Bit mask for extracting chunk-local coordinates (0-15).
     * Equivalent to (SECTION_SIZE - 1).
     */
    public static final int COORD_MASK = SECTION_SIZE - 1;

    /**
     * Minimum Y section index (-4 for world starting at Y=-64).
     */
    public static final int MIN_SECTION_Y = -4;

    /**
     * Maximum Y section index (19 for world ending at Y=319).
     */
    public static final int MAX_SECTION_Y = 19;

    /**
     * Minimum absolute block Y coordinate (-64).
     */
    public static final int MIN_Y = MIN_SECTION_Y * SECTION_SIZE;

    /**
     * Maximum absolute block Y coordinate (319).
     */
    public static final int MAX_Y = (MAX_SECTION_Y + 1) * SECTION_SIZE - 1;

    /**
     * Zstd compression level used for CIS region payloads.
     */
    public static final int COMPRESSION_LEVEL = 3;

    /**
     * Maximum cached region files (64 files).
     * Prevents unbounded memory growth while maintaining good hit rate.
     */
    public static final int MAX_CACHED_REGIONS = 64;

    /**
     * Maximum number of entries in sparse storage before the section is
     * converted to dense encoding. Exceeding this threshold triggers promotion.
     */
    public static final int MAX_SPARSE_CAPACITY = 512;

    /**
     * Number of bits used to encode a palette index.
     * Supports up to 2^12 = 4096 unique block states per section.
     */
    public static final int PALETTE_SIZE_BITS = 12;

    /**
     * Number of bits used to encode a section's Y coordinate.
     * Supports a signed range of [-32, 31] via ZigZag encoding,
     * covering the full legal chunk section range.
     */
    public static final int SECTION_Y_BITS = 6;

    /**
     * Number of bits used to encode a block count within a section.
     * A full section holds exactly 4096 blocks (16³), which requires
     * 13 bits to represent the inclusive range [0, 4096].
     */
    public static final int BLOCK_COUNT_BITS = 13;

    /**
     * Sentinel block-count value used by uniform v11 sections.
     */
    public static final int UNIFORM_SECTION_SENTINEL = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;

    /**
     * Sentinel block-count value used by default-sparse v11 sections.
     */
    public static final int DEFAULT_SPARSE_SECTION_SENTINEL = UNIFORM_SECTION_SENTINEL + 1;

    /**
     * Encoding type flag indicating that a section uses sparse storage,
     * where only non-air blocks are explicitly stored.
     */
    public static final int SECTION_ENCODING_SPARSE = 0;

    /**
     * Encoding type flag indicating that a section uses dense storage,
     * where all 4096 block positions are stored as a flat palette-indexed array.
     */
    public static final int SECTION_ENCODING_DENSE = 1;

    /**
     * Private constructor prevents instantiation.
     * This is a utility class with only static members.
     */
    private CisConstants() {
        throw new AssertionError("CisConstants is a utility class and should not be instantiated");
    }
}
