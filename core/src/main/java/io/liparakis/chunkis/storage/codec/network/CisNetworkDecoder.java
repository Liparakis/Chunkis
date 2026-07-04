package io.liparakis.chunkis.storage.codec.network;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.Palette;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.codec.AbstractCisDecoder;
import io.liparakis.chunkis.storage.mapping.PropertyPacker;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * High-performance decoder for Chunkis Network (CIS) format chunk delta data.
 * <p>
 * This decoder transforms compressed binary chunk delta data into a
 * {@link ChunkDelta}
 * object, supporting both sparse and dense section encoding modes for optimal
 * bandwidth efficiency.
 *
 * @param <B> Block type
 * @param <S> BlockState type
 * @param <P> Property type
 * @param <N> NBT type
 */
public final class CisNetworkDecoder<B, S, P, N> extends AbstractCisDecoder<S, N> {

    /**
     * Registry adapter used to resolve network palette block identifiers back into blocks.
     */
    private final BlockRegistryAdapter<B> registryAdapter;
    /**
     * Property serializer used to rebuild block states from network palette entries.
     */
    private final PropertyPacker<B, S, P> propertyPacker;

    /**
     * Constructs a new decoder with pre-allocated buffers.
     */
    public CisNetworkDecoder(
            BlockRegistryAdapter<B> registryAdapter,
            PropertyPacker<B, S, P> propertyPacker,
            NbtAdapter<N> nbtAdapter,
            S airState) {
        super(nbtAdapter, airState);
        this.registryAdapter = registryAdapter;
        this.propertyPacker = propertyPacker;
    }

    /**
     * Validates the encoded property-data span before the shared property reader is rebound.
     */
    private static void validatePropertyLength(byte[] data, int offset, int propLength) throws IOException {
        if (propLength < 0) {
            throw new IOException("Invalid property data length: " + propLength);
        }
        ensureAvailable(data, offset, propLength, "property data");
    }

    /**
     * Decodes compressed chunk delta data into a ChunkDelta object.
     *
     * @param data The compressed binary chunk delta data. Must not be null.
     * @return A fully populated ChunkDelta object
     * @throws IOException if data is corrupted, version mismatch, or format invalid
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
        PaletteReadResult<B> blockResult = readBlocks(data, offset, globalPaletteSize);
        List<B> blocks = blockResult.blocks();
        offset = blockResult.offset();

        ensureAvailable(data, offset, 4, "property data length");
        int propLength = readIntBE(data, offset);
        offset += 4;
        validatePropertyLength(data, offset, propLength);

        propertyReader.setData(data, offset, propLength);
        offset += propLength;

        populateNetworkGlobalPalette(palette, blocks);
        return offset;
    }

    /**
     * Reads the block-identity portion of the network global palette and returns the updated offset.
     */
    private PaletteReadResult<B> readBlocks(byte[] data, int offset, int globalPaletteSize) throws IOException {
        List<B> blocks = new ArrayList<>(globalPaletteSize);
        ByteArrayInputStream bais = new ByteArrayInputStream(data, offset, data.length - offset);
        int initialAvailable = bais.available();

        try (DataInputStream dis = new DataInputStream(bais)) {
            for (int i = 0; i < globalPaletteSize; i++) {
                blocks.add(readBlock(dis));
            }
        }

        int bytesRead = initialAvailable - bais.available();
        return new PaletteReadResult<>(blocks, offset + bytesRead);
    }

    /**
     * Resolves one network palette block identifier, substituting air for unknown ids.
     */
    private B readBlock(DataInputStream dis) throws IOException {
        String idStr = dis.readUTF();
        B block = registryAdapter.getBlock(idStr);
        return block == null ? registryAdapter.getAir() : block;
    }

    /**
     * Reconstructs network global palette states by applying decoded property payloads to each block id.
     */
    private void populateNetworkGlobalPalette(Palette<S> palette, List<B> blocks) {
        beginGlobalPalette(blocks.size());
        for (B block : blocks) {
            var metas = propertyPacker.getPropertyMetas(block);
            addGlobalPaletteState(palette, propertyPacker.readProperties(propertyReader, block, metas));
        }
    }

    /**
     * Result of reading the identifier half of a network global palette.
     */
    private record PaletteReadResult<B>(List<B> blocks, int offset) {

    }
}
