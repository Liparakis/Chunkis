package io.liparakis.chunkis.world.restoration.nbt;

import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.fluid.Fluid;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.tick.Tick;
import com.mojang.serialization.Codec;

/**
 * Keeps a stored chunk's pending block and fluid ticks, in vanilla's own form, so a chunk saved while water still
 * flows or leaves still update resumes exactly where it stopped instead of freezing.
 *
 * <p>The ticks live in chunk metadata under {@link CisNbtUtil#PENDING_TICKS_KEY} as vanilla's {@code block_ticks}
 * and {@code fluid_ticks} lists (delays relative to the save), are refreshed by every snapshot of the chunk and
 * are handed to vanilla's chunk reader on load. A chunk with no pending tick stores nothing.</p>
 */
public final class PendingTicksNbt {

    /**
     * Vanilla's key for pending block ticks.
     */
    private static final String BLOCK_TICKS = "block_ticks";

    /**
     * Vanilla's key for pending fluid ticks.
     */
    private static final String FLUID_TICKS = "fluid_ticks";

    /**
     * Vanilla's codec for pending block ticks.
     */
    private static final Codec<List<Tick<Block>>> BLOCK_TICKS_CODEC =
            Tick.createCodec(Registries.BLOCK.getCodec()).listOf();

    /**
     * Vanilla's codec for pending fluid ticks.
     */
    private static final Codec<List<Tick<Fluid>>> FLUID_TICKS_CODEC =
            Tick.createCodec(Registries.FLUID.getCodec()).listOf();

    /**
     * Not instantiable.
     */
    private PendingTicksNbt() {
        throw new AssertionError("Utility class");
    }

    /**
     * Captures a live chunk's pending ticks.
     *
     * @param chunk live chunk
     * @return the ticks, or {@code null} when none is pending
     */
    public static NbtCompound capture(final WorldChunk chunk) {
        final Chunk.TickSchedulers ticks = chunk.getTickSchedulers(chunk.getWorld().getTime());
        if (ticks.blocks().isEmpty() && ticks.fluids().isEmpty()) {
            return null;
        }
        final NbtCompound nbt = new NbtCompound();
        if (!ticks.blocks().isEmpty()) {
            nbt.put(BLOCK_TICKS, BLOCK_TICKS_CODEC, ticks.blocks());
        }
        if (!ticks.fluids().isEmpty()) {
            nbt.put(FLUID_TICKS, FLUID_TICKS_CODEC, ticks.fluids());
        }
        return nbt;
    }

    /**
     * Hands stored pending ticks to vanilla's chunk reader.
     *
     * @param metadata chunk metadata, possibly {@code null}
     * @param root     chunk NBT vanilla will read
     */
    public static void putInto(final NbtCompound metadata, final NbtCompound root) {
        final NbtCompound pending = metadata == null ? null : metadata.getCompound(CisNbtUtil.PENDING_TICKS_KEY)
                .orElse(null);
        if (pending == null) {
            return;
        }
        pending.getList(BLOCK_TICKS).ifPresent(list -> root.put(BLOCK_TICKS, list.copy()));
        pending.getList(FLUID_TICKS).ifPresent(list -> root.put(FLUID_TICKS, list.copy()));
    }
}
