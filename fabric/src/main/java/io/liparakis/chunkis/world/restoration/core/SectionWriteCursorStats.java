package io.liparakis.chunkis.world.restoration.core;

/**
 * Immutable counters describing one restore pass's section-write behavior.
 */
record SectionWriteCursorStats(
        long writes,
        long rebinds,
        long paletteHits,
        long paletteMisses,
        long paletteInvalidations
) {

    /**
     * Returns a compact human-readable summary string.
     */
    String describe() {
        return "writes=" + writes
                + ", rebinds=" + rebinds
                + ", paletteHits=" + paletteHits
                + ", paletteMisses=" + paletteMisses
                + ", paletteInvalidations=" + paletteInvalidations;
    }
}
