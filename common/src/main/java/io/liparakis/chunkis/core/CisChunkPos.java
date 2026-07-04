package io.liparakis.chunkis.core;

/**
 * Platform-agnostic representation of a chunk position.
 * <p>
 * This record stores the grid coordinates of a chunk in a 2D world plane.
 * It is used for identifying and locating chunks without dependency on
 * any specific game engine or platform classes.
 * </p>
 *
 * @param x The chunk X coordinate
 * @param z The chunk Z coordinate
 *          <p>
 */
public record CisChunkPos(int x, int z) {

}
