package io.liparakis.chunkis.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import java.util.Arrays;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the Chunkis network payload wire contract.
 *
 * <p>The production codec uses {@link RegistryByteBuf}; these tests exercise
 * that path directly so payload compression changes cannot silently desync the
 * Fabric packet format.
 */
class ChunkDeltaPayloadTest {

    /**
     * Performs a round-trip encode and decode of a {@link ChunkDeltaPayload} to verify codec integrity.
     *
     * @param payload the source payload to round-trip
     * @return the decoded {@link ChunkDeltaPayload}
     */
    private static ChunkDeltaPayload roundTrip(ChunkDeltaPayload payload) {
        // The payload itself does not read registries, but the Fabric packet API
        // requires a RegistryByteBuf wrapper for the registered codec type.
        RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        ChunkDeltaPayload.CODEC.encode(buf, payload);
        return ChunkDeltaPayload.CODEC.decode(buf);
    }

    /**
     * Verifies that small payloads remain uncompressed and that the data array is copied to prevent
     * external modifications from affecting the payload.
     */
    @Test
    void smallPayloadStaysUncompressedAndIsCopied() {
        byte[] raw = new byte[]{1, 2, 3, 4};

        ChunkDeltaPayload payload = ChunkDeltaPayload.create(raw, 12, -7);
        raw[0] = 99;

        assertEquals(12, payload.chunkX());
        assertEquals(-7, payload.chunkZ());
        assertFalse(payload.compressed());
        assertEquals(0, payload.uncompressedSize());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, payload.data());
        assertNotSame(raw, payload.data());
    }

    /**
     * Verifies that the codec correctly encodes and decodes an uncompressed payload.
     */
    @Test
    void codecRoundTripsUncompressedPayload() {
        ChunkDeltaPayload payload = ChunkDeltaPayload.create(new byte[]{10, 20, 30}, 3, 4);

        ChunkDeltaPayload decoded = roundTrip(payload);

        assertEquals(payload.chunkX(), decoded.chunkX());
        assertEquals(payload.chunkZ(), decoded.chunkZ());
        assertEquals(payload.compressed(), decoded.compressed());
        assertEquals(payload.uncompressedSize(), decoded.uncompressedSize());
        assertArrayEquals(payload.data(), decoded.data());
    }

    /**
     * Verifies that large, compressible payloads are compressed when serialized.
     */
    @Test
    void largeCompressiblePayloadUsesCompressedWireData() {
        byte[] raw = new byte[8192];
        Arrays.fill(raw, (byte) 5);

        ChunkDeltaPayload payload = ChunkDeltaPayload.create(raw, -4, 9);

        assertEquals(-4, payload.chunkX());
        assertEquals(9, payload.chunkZ());
        assertTrue(payload.compressed());
        assertEquals(raw.length, payload.uncompressedSize());
        assertTrue(payload.data().length < raw.length);
    }

    /**
     * Verifies that the codec correctly round-trips a compressed payload, yielding the original raw data.
     */
    @Test
    void codecRoundTripsCompressedPayloadToRawData() {
        byte[] raw = new byte[8192];
        Arrays.fill(raw, (byte) 5);
        ChunkDeltaPayload payload = ChunkDeltaPayload.create(raw, -4, 9);

        ChunkDeltaPayload decoded = roundTrip(payload);

        assertEquals(payload.chunkX(), decoded.chunkX());
        assertEquals(payload.chunkZ(), decoded.chunkZ());
        assertTrue(decoded.compressed());
        assertEquals(raw.length, decoded.uncompressedSize());
        assertArrayEquals(raw, decoded.data());
    }
}
