package io.liparakis.chunkis.world.restoration.nbt;

import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.SavedTick;
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
    private static final Codec<List<SavedTick<Block>>> BLOCK_TICKS_CODEC =
            SavedTick.codec(BuiltInRegistries.BLOCK.byNameCodec()).listOf();

    /**
     * Vanilla's codec for pending fluid ticks.
     */
    private static final Codec<List<SavedTick<Fluid>>> FLUID_TICKS_CODEC =
            SavedTick.codec(BuiltInRegistries.FLUID.byNameCodec()).listOf();

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
    public static CompoundTag capture(final LevelChunk chunk) {
        final ChunkAccess.PackedTicks ticks = chunk.getTicksForSerialization(chunk.getLevel().getGameTime());
        if (ticks.blocks().isEmpty() && ticks.fluids().isEmpty()) {
            return null;
        }
        final CompoundTag nbt = new CompoundTag();
        if (!ticks.blocks().isEmpty()) {
            nbt.store(BLOCK_TICKS, BLOCK_TICKS_CODEC, ticks.blocks());
        }
        if (!ticks.fluids().isEmpty()) {
            nbt.store(FLUID_TICKS, FLUID_TICKS_CODEC, ticks.fluids());
        }
        return nbt;
    }

    /**
     * Hands stored pending ticks to vanilla's chunk reader.
     *
     * @param metadata chunk metadata, possibly {@code null}
     * @param root     chunk NBT vanilla will read
     */
    public static void putInto(final CompoundTag metadata, final CompoundTag root) {
        final CompoundTag pending = metadata == null ? null : metadata.getCompound(CisNbtUtil.PENDING_TICKS_KEY)
                .orElse(null);
        if (pending == null) {
            return;
        }
        pending.getList(BLOCK_TICKS).ifPresent(list -> root.put(BLOCK_TICKS, list.copy()));
        pending.getList(FLUID_TICKS).ifPresent(list -> root.put(FLUID_TICKS, list.copy()));
    }
}
