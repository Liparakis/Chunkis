package io.liparakis.chunkis.storage.io.region;

/**
 * One reusable byte range inside a region payload area.
 *
 * @param offset start offset in the region file
 * @param length byte length of the reusable range
 */
record RegionFreeBlock(int offset, int length) {

}
