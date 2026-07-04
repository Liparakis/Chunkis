package io.liparakis.chunkis.storage.codec.network;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.ChunkDeltaView;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.codec.AbstractCisEncoder;
import io.liparakis.chunkis.storage.mapping.PropertyPacker;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * High-performance encoder for Chunkis Network (CIS) format chunk delta data.
 * <p>
 * This encoder transforms {@link ChunkDelta} objects into compressed binary
 * format, optimizing for both bandwidth efficiency and encoding speed.
 * </p>
 *
 * @param <B> Block type
 * @param <S> BlockState type
 * @param <P> Property type
 * @param <N> NBT type
 */
public final class CisNetworkEncoder<B, S, P, N> extends AbstractCisEncoder<S, N> {

    /**
     * Thread-local encoder context for allocation-free encoding.
     */
    @SuppressWarnings("rawtypes")
    private static final ThreadLocal<EncoderContext> CONTEXT = ThreadLocal.withInitial(EncoderContext::new);

    /**
     * Registry adapter used to turn block identities into stable network ids.
     */
    private final BlockRegistryAdapter<B> registryAdapter;
    /**
     * Typed state adapter retained locally so palette writes do not need wildcard casts.
     */
    private final BlockStateAdapter<B, S, P> typedStateAdapter;
    /**
     * Property serializer used for the network global palette payload.
     */
    private final PropertyPacker<B, S, P> propertyPacker;

    /**
     * Constructs a new CisNetworkEncoder.
     */
    public CisNetworkEncoder(
            BlockRegistryAdapter<B> registryAdapter,
            PropertyPacker<B, S, P> propertyPacker,
            BlockStateAdapter<B, S, P> stateAdapter,
            NbtAdapter<N> nbtAdapter,
            S airState) {
        super(nbtAdapter, airState);
        this.registryAdapter = registryAdapter;
        this.typedStateAdapter = stateAdapter;
        this.propertyPacker = propertyPacker;
    }

    /**
     * Encodes a chunk delta view into compressed binary format.
     */
    public byte[] encode(final ChunkDeltaView<S, N> delta) throws IOException {
        return encodeInternal(delta);
    }

    @Override
    protected void writeGlobalPaletteEntry(
            DataOutputStream dos,
            EncoderContext<S> ctx,
            S state) throws IOException {
        B block = typedStateAdapter.getBlock(state);
        dos.writeUTF(registryAdapter.getId(block));

        var metas = propertyPacker.getPropertyMetas(block);
        propertyPacker.writeProperties(ctx.bitWriter, state, metas);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected EncoderContext<S> getContext() {
        return CONTEXT.get();
    }
}
