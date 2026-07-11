package io.liparakis.chunkis.core.codec;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.ChunkDeltaView;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.core.mapping.CisAdapter;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Encoder for the Chunkis CIS format with paletted section storage and dynamic
 * property bit-packing.
 *
 * @param <S> The BlockState type
 * @param <N> The NBT type
 */
public final class CisEncoder<S, N> extends AbstractCisEncoder<S, N> {

    /**
     * Thread-local context to minimize allocations during encoding.
     */
    @SuppressWarnings("rawtypes")
    private static final ThreadLocal<EncoderContext> CONTEXT = ThreadLocal.withInitial(EncoderContext::new);

    /**
     * Palette adapter that knows how to encode one block-state entry into the CIS palette stream.
     */
    private final CisAdapter<S> cisAdapter;

    /**
     * Constructs a new CisEncoder.
     */
    public CisEncoder(CisAdapter<S> cisAdapter, NbtAdapter<N> nbtAdapter,
            S airState) {
        super(nbtAdapter, airState);
        this.cisAdapter = cisAdapter;
    }

    /**
     * Encodes a ChunkDelta into the current Chunkis CIS binary format.
     */
    public byte[] encode(ChunkDelta<S, N> delta) throws IOException {
        return encodeInternal(delta);
    }

    /** Performs encode. */
    public byte[] encode(final ChunkDeltaView<S, N> delta) throws IOException {
        return encodeInternal(delta);
    }

    @Override
    protected void writeGlobalPaletteEntry(
            DataOutputStream dos,
            EncoderContext<S> ctx,
            S state) throws IOException {
        dos.writeShort(cisAdapter.getBlockId(state));
        cisAdapter.writeStateProperties(ctx.bitWriter, state);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected EncoderContext<S> getContext() {
        return CONTEXT.get();
    }
}
