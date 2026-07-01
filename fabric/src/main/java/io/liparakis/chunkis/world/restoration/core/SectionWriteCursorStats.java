package io.liparakis.chunkis.world.restoration.core;

/**
 * Immutable counters describing one restore pass's section-write behavior.
 */
record SectionWriteCursorStats(
        long writes,
        long rebinds,
        long lastStateHits,
        long paletteHits,
        long paletteMisses,
        long paletteInvalidations,
        long arrayPaletteLookups,
        long biMapPaletteLookups,
        long singularPaletteLookups
) {

    /**
     * Returns a compact human-readable summary string.
     */
    String describe() {
        return "writes=" + writes
                + ", rebinds=" + rebinds
                + ", lastStateHits=" + lastStateHits
                + ", paletteHits=" + paletteHits
                + ", paletteMisses=" + paletteMisses
                + ", paletteInvalidations=" + paletteInvalidations
                + ", arrayPaletteLookups=" + arrayPaletteLookups
                + ", biMapPaletteLookups=" + biMapPaletteLookups
                + ", singularPaletteLookups=" + singularPaletteLookups;
    }
}
