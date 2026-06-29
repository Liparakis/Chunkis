package io.liparakis.chunkis.spi;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Adapter interface for NBT I/O operations.
 *
 * @param <N> The NBT type
 * @author Liparakis
 * @version 1
 */
public interface NbtAdapter<N> {

    /**
     * Writes the NBT compound to the output.
     */
    void write(N tag, DataOutput output) throws IOException;

    /**
     * Reads an NBT compound from the input.
     */
    N read(DataInput input) throws IOException;

    /**
     * Writes one NBT payload using the legacy compressed-on-inner-payload format.
     *
     * <p>
     * Older CIS versions stored each NBT payload as a length-prefixed compressed
     * blob. Implementations that do not distinguish raw and compressed payloads may
     * delegate to {@link #write(Object, DataOutput)}.
     */
    default void writeCompressed(N tag, DataOutput output) throws IOException {
        write(tag, output);
    }

    /**
     * Reads one NBT payload stored in the legacy compressed-on-inner-payload
     * format.
     *
     * <p>
     * Implementations that do not distinguish raw and compressed payloads may
     * delegate to {@link #read(DataInput)}.
     */
    default N readCompressed(DataInput input) throws IOException {
        return read(input);
    }

    /**
     * Writes one NBT payload using the raw length-prefixed format introduced for
     * newer CIS versions.
     *
     * <p>
     * Implementations that do not distinguish raw and compressed payloads may
     * delegate to {@link #write(Object, DataOutput)}.
     */
    default void writeRaw(N tag, DataOutput output) throws IOException {
        write(tag, output);
    }

    /**
     * Reads one NBT payload stored in the raw length-prefixed format introduced
     * for newer CIS versions.
     *
     * <p>
     * Implementations that do not distinguish raw and compressed payloads may
     * delegate to {@link #read(DataInput)}.
     */
    default N readRaw(DataInput input) throws IOException {
        return read(input);
    }
}
