package io.liparakis.chunkis.world.restoration.nbt;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;

/**
 * Length-prefixed raw NBT framing for CIS chunk metadata, including packing the
 * persisted base chunk into its opaque byte-array form for storage.
 */
public final class CisMetadataCodec {

    /**
     * Initial buffer size for small metadata serialization.
     */
    private static final int RAW_METADATA_INITIAL_CAPACITY = 256;

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private CisMetadataCodec() {
        throw new AssertionError("Utility class");
    }

    /**
     * Serializes one raw metadata payload using CIS' length-prefixed framing.
     *
     * <p>The returned byte array contains:</p>
     * <ol>
     *   <li>a four-byte big-endian payload length</li>
     *   <li>the raw NBT compound payload</li>
     * </ol>
     *
     * <p>This avoids the old double-buffer path. It writes a placeholder length,
     * serializes the payload once, patches the first four bytes, then returns one
     * compact byte array.</p>
     *
     * @param metadata metadata payload to serialize
     * @return encoded length-prefixed payload
     * @throws IOException if NBT serialization fails
     */
    public static byte[] serializeRawPayload(final NbtCompound metadata) throws IOException {
        Objects.requireNonNull(metadata, "metadata");

        final LengthPrefixedByteArrayOutputStream buffer =
                new LengthPrefixedByteArrayOutputStream(RAW_METADATA_INITIAL_CAPACITY);

        try (DataOutputStream output = new DataOutputStream(buffer)) {
            output.writeInt(0);
            NbtIo.writeCompound(metadata, output);
            output.flush();
        }

        buffer.patchLengthPrefix();
        return buffer.toSizedByteArray();
    }

    /**
     * Serializes one raw NBT compound without CIS' outer length prefix.
     *
     * <p>Used for nested payloads that Chunkis wants to keep opaque inside larger
     * metadata structures, such as persisted base chunks.</p>
     *
     * @param payload target compound
     * @return raw serialized compound bytes
     * @throws IOException if serialization fails
     */
    static byte[] serializeRawCompound(final NbtCompound payload) throws IOException {
        Objects.requireNonNull(payload, "payload");

        try (java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(4096);
                java.io.DataOutputStream output = new java.io.DataOutputStream(buffer)) {
            NbtIo.writeCompound(payload, output);
            output.flush();
            return buffer.toByteArray();
        }
    }

    /**
     * Parses one raw serialized NBT compound without a length prefix.
     *
     * @param payload raw compound bytes
     * @return parsed compound
     * @throws IOException if parsing fails
     */
    static NbtCompound deserializeRawCompound(final byte[] payload) throws IOException {
        Objects.requireNonNull(payload, "payload");

        try (java.io.ByteArrayInputStream input = new java.io.ByteArrayInputStream(payload);
                java.io.DataInputStream dataInput = new java.io.DataInputStream(input)) {
            return NbtIo.readCompound(dataInput);
        }
    }

    /**
     * Serializes chunk metadata for CIS persistence, packing any embedded base
     * chunk compound into the opaque byte-array form used by newer saves.
     *
     * <p>This keeps the server-thread mutation path on the simple in-memory
     * compound shape while still writing the cheaper-to-load on-disk shape.</p>
     *
     * @param metadata in-memory metadata envelope
     * @return encoded CIS metadata payload including length prefix
     * @throws IOException if serialization fails
     */
    public static byte[] serializeChunkMetadataForStorage(final NbtCompound metadata) throws IOException {
        return serializeRawPayload(packPersistedBaseChunkPayload(metadata));
    }

    /**
     * Returns metadata ready for persistence, rewriting only the persisted base
     * chunk child from compound form to opaque bytes when needed.
     *
     * @param metadata in-memory metadata envelope
     * @return original metadata when no rewrite is needed, otherwise a packed copy
     * @throws IOException if base chunk serialization fails
     */
    static NbtCompound packPersistedBaseChunkPayload(final NbtCompound metadata) throws IOException {
        Objects.requireNonNull(metadata, "metadata");

        if (metadata.getByteArray(CisNbtUtil.BASE_CHUNK_PAYLOAD_KEY)
                .isPresent()) {
            return metadata;
        }

        final NbtCompound baseChunkNbt = CisNbtUtil.getCompoundOrNull(metadata, CisNbtUtil.BASE_CHUNK_NBT_KEY);
        if (baseChunkNbt == null || baseChunkNbt.isEmpty()) {
            return metadata;
        }

        final NbtCompound packed = metadata.copy();
        packed.remove(CisNbtUtil.BASE_CHUNK_NBT_KEY);
        packed.putByteArray(CisNbtUtil.BASE_CHUNK_PAYLOAD_KEY, serializeRawCompound(baseChunkNbt));
        return packed;
    }

    /**
     * Byte-array output stream whose first four bytes can be patched with the
     * payload length after serialization.
     *
     * <p>This avoids serializing metadata into a second temporary payload buffer
     * just to discover its size.</p>
     */
    private static final class LengthPrefixedByteArrayOutputStream
            extends ByteArrayOutputStream {

        /**
         * Constructor.
         *
         * @param size initial capacity
         */
        private LengthPrefixedByteArrayOutputStream(final int size) {
            super(size);
        }

        /**
         * Patches the first four bytes with the payload length.
         *
         * <p>The payload begins immediately after the four-byte length prefix.</p>
         */
        private void patchLengthPrefix() {
            final int payloadLength = count - Integer.BYTES;

            buf[0] = (byte) (payloadLength >>> 24);
            buf[1] = (byte) (payloadLength >>> 16);
            buf[2] = (byte) (payloadLength >>> 8);
            buf[3] = (byte) payloadLength;
        }

        /**
         * Returns a compact byte array containing only written bytes.
         *
         * @return compact serialized byte array
         */
        private byte[] toSizedByteArray() {
            return count == buf.length ? buf : Arrays.copyOf(buf, count);
        }
    }
}
