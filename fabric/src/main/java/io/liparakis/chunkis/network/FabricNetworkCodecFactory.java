package io.liparakis.chunkis.network;

import io.liparakis.chunkis.adapter.FabricBlockRegistryAdapter;
import io.liparakis.chunkis.adapter.FabricBlockStateAdapter;
import io.liparakis.chunkis.adapter.FabricNbtAdapter;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.core.codec.network.CisNetworkDecoder;
import io.liparakis.chunkis.core.codec.network.CisNetworkEncoder;
import io.liparakis.chunkis.core.mapping.PropertyPacker;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.state.property.Property;

/**
 * Factory for creating Fabric-specific network encoders and decoders.
 *
 * <p>All shared adapter instances ({@link FabricBlockRegistryAdapter},
 * {@link FabricBlockStateAdapter}, {@link FabricNbtAdapter}) are immutable and
 * thread-safe, so they are constructed once and reused across all codec instances.</p>
 *
 * <p><b>Creation strategy:</b> Encoder and decoder singletons are lazily initialized
 * on first use via double-checked locking. This provides zero-allocation performance
 * on the hot path while deferring construction until the codec is actually needed.</p>
 *
 * <p><b>Thread safety:</b> All public methods are thread-safe.</p>
 */
public final class FabricNetworkCodecFactory {

    /**
     * Shared static registry adapter mapping block types.
     */
    private static final BlockRegistryAdapter<Block> REGISTRY_ADAPTER = new FabricBlockRegistryAdapter();

    /**
     * Shared static adapter mapping state properties.
     */
    private static final BlockStateAdapter<Block, BlockState, Property<?>> STATE_ADAPTER = new FabricBlockStateAdapter();

    /**
     * Shared static adapter mapping NBT compounds.
     */
    private static final NbtAdapter<NbtCompound> NBT_ADAPTER = new FabricNbtAdapter();

    /**
     * Shared static property packer mapping properties.
     */
    private static final PropertyPacker<Block, BlockState, Property<?>> PROPERTY_PACKER = new PropertyPacker<>(
            STATE_ADAPTER);

    /**
     * Cached air state used as the "no block" sentinel in codec operations.
     */
    private static final BlockState AIR_STATE = Blocks.AIR.getDefaultState();

    /**
     * Volatile reference to the shared decoder singleton. Null until first use.
     * Guarded by {@code synchronized (FabricNetworkCodecFactory.class)} on the slow path.
     */
    private static volatile CisNetworkDecoder<Block, BlockState, Property<?>, NbtCompound> decoderSingleton;

    /**
     * Volatile reference to the shared encoder singleton. Null until first use.
     * Guarded by {@code synchronized (FabricNetworkCodecFactory.class)} on the slow path.
     */
    private static volatile CisNetworkEncoder<Block, BlockState, Property<?>, NbtCompound> encoderSingleton;

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private FabricNetworkCodecFactory() {
        throw new AssertionError("Utility class");
    }

    /**
     * Returns the shared decoder singleton, creating it on first call.
     *
     * <p>Uses double-checked locking: the fast path (volatile read) is allocation-free.
     * The slow path (synchronized block) is taken only on the very first call.</p>
     *
     * @return the shared {@link CisNetworkDecoder} instance
     */
    public static CisNetworkDecoder<Block, BlockState, Property<?>, NbtCompound> createDecoder() {
        final CisNetworkDecoder<Block, BlockState, Property<?>, NbtCompound> fast = decoderSingleton;
        if (fast != null) {
            return fast;
        }
        return initDecoderSingleton();
    }

    /**
     * Returns the shared encoder singleton, creating it on first call.
     *
     * <p>Uses double-checked locking: the fast path (volatile read) is allocation-free.
     * The slow path (synchronized block) is taken only on the very first call.</p>
     *
     * @return the shared {@link CisNetworkEncoder} instance
     */
    public static CisNetworkEncoder<Block, BlockState, Property<?>, NbtCompound> createEncoder() {
        final CisNetworkEncoder<Block, BlockState, Property<?>, NbtCompound> fast = encoderSingleton;
        if (fast != null) {
            return fast;
        }
        return initEncoderSingleton();
    }

    /**
     * Synchronized slow path for decoder singleton initialization.
     * Re-checks the volatile field inside the lock to handle concurrent first callers.
     *
     * @return the initialized decoder singleton
     */
    private static synchronized CisNetworkDecoder<Block, BlockState, Property<?>, NbtCompound> initDecoderSingleton() {
        if (decoderSingleton == null) {
            decoderSingleton = buildDecoder();
        }
        return decoderSingleton;
    }

    /**
     * Synchronized slow path for encoder singleton initialization.
     * Re-checks the volatile field inside the lock to handle concurrent first callers.
     *
     * @return the initialized encoder singleton
     */
    private static synchronized CisNetworkEncoder<Block, BlockState, Property<?>, NbtCompound> initEncoderSingleton() {
        if (encoderSingleton == null) {
            encoderSingleton = buildEncoder();
        }
        return encoderSingleton;
    }

    /**
     * Constructs a new {@link CisNetworkDecoder} wired to the shared adapter singletons.
     *
     * @return a fully initialized decoder
     */
    private static CisNetworkDecoder<Block, BlockState, Property<?>, NbtCompound> buildDecoder() {
        return new CisNetworkDecoder<>(
                REGISTRY_ADAPTER,
                PROPERTY_PACKER,
                NBT_ADAPTER,
                AIR_STATE);
    }

    /**
     * Constructs a new {@link CisNetworkEncoder} wired to the shared adapter singletons.
     *
     * @return a fully initialized encoder
     */
    private static CisNetworkEncoder<Block, BlockState, Property<?>, NbtCompound> buildEncoder() {
        return new CisNetworkEncoder<>(
                REGISTRY_ADAPTER,
                PROPERTY_PACKER,
                STATE_ADAPTER,
                NBT_ADAPTER,
                AIR_STATE);
    }
}
