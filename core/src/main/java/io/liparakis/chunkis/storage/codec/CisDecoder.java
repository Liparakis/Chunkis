package io.liparakis.chunkis.storage.codec;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.Palette;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.mapping.CisAdapter;

import java.io.IOException;

/**
 * Decoder for the Chunkis CIS format with support for paletted sections and
 * dynamic property packing.
 *
 * @param <S> The BlockState type
 * @param <N> The NBT type
 * @author Liparakis
 * @version 1
 */
public final class CisDecoder<S, N> extends AbstractCisDecoder<S, N> {

    /**
     * Palette adapter that knows how to decode one block-state entry from the CIS palette stream.
     */
    private final CisAdapter<S> cisAdapter;

    /**
     * Constructs a new CisDecoder.
     */
    public CisDecoder(
            CisAdapter<S> cisAdapter, BlockStateAdapter<?, S, ?> stateAdapter, NbtAdapter<N> nbtAdapter,
            S airState) {
        super(nbtAdapter, airState);
        this.cisAdapter = cisAdapter;
    }

    /**
     * Decodes a byte array into a ChunkDelta.
     *
     * @param data the encoded CIS format data
     * @return the decoded ChunkDelta
     * @throws IOException if the data is invalid or corrupted
     */
    public ChunkDelta<S, N> decode(byte[] data) throws IOException {
        return decodeInternal(data);
    }

    @Override
    protected int decodeGlobalPalette(byte[] data, int offset, Palette<S> palette) throws IOException {
        ensureAvailable(data, offset, 4, "global palette size");

        int globalPaletteSize = readIntBE(data, offset);
        offset += 4;

        validateGlobalPaletteSize(globalPaletteSize);

        int requiredBytes = globalPaletteSize * 2 + 4;
        ensureAvailable(data, offset, requiredBytes, "global palette");

        int[] blockIds = new int[globalPaletteSize];
        for (int i = 0; i < globalPaletteSize; i++) {
            blockIds[i] = readShortBE(data, offset) & 0xFFFF;
            offset += 2;
        }

        int propLength = readIntBE(data, offset);
        offset += 4;

        ensureAvailable(data, offset, propLength, "properties");

        propertyReader.setData(data, offset, propLength);
        offset += propLength;

        beginGlobalPalette(globalPaletteSize);
        for (int i = 0; i < globalPaletteSize; i++) {
            addGlobalPaletteState(palette, cisAdapter.readStateProperties(propertyReader, blockIds[i]));
        }

        return offset;
    }
}
