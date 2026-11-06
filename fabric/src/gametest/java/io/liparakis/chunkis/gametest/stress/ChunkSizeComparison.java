package io.liparakis.chunkis.gametest.stress;

import com.google.gson.JsonObject;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.compression.CompressionContext;
import io.liparakis.chunkis.world.entity.capture.ChunkEntityNbtCapture;
import io.liparakis.chunkis.world.restoration.nbt.CisMetadataCodec;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import io.liparakis.chunkis.world.tracking.ownership.ChunkDeltaOwnership;
import io.liparakis.chunkis.world.tracking.save.FabricCisStorageHelper;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.phys.AABB;

/**
 * Compares, for the same loaded chunks, the bytes Chunkis writes per chunk with what vanilla would write:
 * the chunk record plus the separate entity record, each zlib-compressed as vanilla region files store
 * them, also rounded up to vanilla's 4 KiB region sectors.
 */
final class ChunkSizeComparison {

    /**
     * Vanilla region files allocate whole sectors of this size.
     */
    private static final int SECTOR_BYTES = 4096;

    /**
     * Compressor CisStorage applies to every payload it writes.
     */
    private static final CompressionContext COMPRESSION = new CompressionContext();

    /**
     * Vanilla's per-record header: 4-byte length plus 1-byte compression type.
     */
    private static final int RECORD_HEADER_BYTES = 5;

    /**
     * Static holder; not instantiable.
     */
    private ChunkSizeComparison() {
    }

    /**
     * Measures every chunk that is loaded and owned by Chunkis.
     *
     * @param world  world
     * @param chunks chunk positions
     * @return mean bytes per chunk for Chunkis, vanilla and vanilla on disk, plus ratios
     */
    @SuppressWarnings("unchecked")
    static JsonObject measure(final ServerLevel world, final List<ChunkPos> chunks) {
        long chunkisBytes = 0;
        long chunkisRawBytes = 0;
        long vanillaBytes = 0;
        long vanillaSectorBytes = 0;
        int measured = 0;
        int owned = 0;
        int withBaseChunk = 0;
        int withFullBaseline = 0;
        long metadataBytes = 0;
        for (final ChunkPos pos : chunks) {
            final LevelChunk chunk = world.getChunk(pos.x(), pos.z());
            final int chunkRecord = deflatedSize(SerializableChunkData.copyOf(world, chunk).write());
            final CompoundTag entities = entityRecord(world, pos);
            final int entityRecord = entities == null ? 0 : deflatedSize(entities);
            vanillaBytes += chunkRecord + entityRecord;
            vanillaSectorBytes += sectorRounded(chunkRecord) + (entityRecord == 0 ? 0 : sectorRounded(entityRecord));
            measured++;
            if (!(chunk instanceof ChunkisDeltaDuck duck)
                    || !ChunkDeltaOwnership.hasChunkisOwnedState(duck.chunkis$getDelta())) {
                continue;
            }
            // The stored entry, not the live delta: a loaded chunk's delta may have released its block instructions.
            final ChunkDelta<BlockState, CompoundTag> delta = FabricCisStorageHelper.getStorage(world)
                    .load(FabricCisStorageHelper.toStoragePos(pos));
            final byte[] chunkisRaw = chunkisPayload(world, pos, delta);
            chunkisRawBytes += chunkisRaw.length;
            // Measured as written to disk: CisStorage compresses every payload before writing it.
            chunkisBytes += chunkisRaw.length == 0 ? 0 : COMPRESSION.compress(chunkisRaw).length;
            withBaseChunk += CisNbtUtil.hasPersistedBaseChunkNbt(delta.getChunkMetadata()) ? 1 : 0;
            withFullBaseline += CisNbtUtil.hasFullBlockBaseline(delta.getChunkMetadata()) ? 1 : 0;
            metadataBytes += metadataSize(delta.getChunkMetadata());
            owned++;
        }
        final JsonObject result = new JsonObject();
        result.addProperty("chunks", measured);
        result.addProperty("chunkisOwnedChunks", owned);
        if (measured == 0) {
            return result;
        }
        result.addProperty("vanillaBytes", vanillaBytes / (double) measured);
        result.addProperty("vanillaOnDiskBytes", vanillaSectorBytes / (double) measured);
        if (owned == 0) {
            return result;
        }
        result.addProperty("chunkisBytes", chunkisBytes / (double) owned);
        result.addProperty("chunkisRawBytes", chunkisRawBytes / (double) owned);
        result.addProperty("chunkisToVanilla", (chunkisBytes / (double) owned) / (vanillaBytes / (double) measured));
        result.addProperty("chunkisToVanillaOnDisk",
                (chunkisBytes / (double) owned) / (vanillaSectorBytes / (double) measured));
        result.addProperty("chunksWithBaseChunk", withBaseChunk);
        result.addProperty("chunksWithFullBlockBaseline", withFullBaseline);
        result.addProperty("metadataRawBytes", metadataBytes / (double) owned);
        return result;
    }

    /**
     * Payload Chunkis encodes for a delta, before compression.
     *
     * @param world world
     * @param pos   chunk position
     * @param delta chunk delta
     * @return encoded payload
     */
    private static byte[] chunkisPayload(final ServerLevel world, final ChunkPos pos,
            final ChunkDelta<BlockState, CompoundTag> delta) {
        try {
            final var prepared = FabricCisStorageHelper.getStorage(world)
                    .prepareSave(FabricCisStorageHelper.toStoragePos(pos), delta.snapshotView(CompoundTag::copy));
            // An empty delta clears the chunk's entry: nothing is stored for it.
            return prepared.clearChunk() ? new byte[0] : prepared.rawData();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed encoding chunk " + pos, e);
        }
    }

    /**
     * Uncompressed size of a delta's chunk metadata (structures, base chunk, flags).
     *
     * @param metadata chunk metadata, possibly {@code null}
     * @return raw bytes
     */
    private static int metadataSize(final CompoundTag metadata) {
        if (metadata == null) {
            return 0;
        }
        try {
            return CisMetadataCodec.serializeRawPayload(metadata).length;
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed encoding chunk metadata", e);
        }
    }

    /**
     * Builds the record vanilla's entity storage would write for the chunk.
     *
     * @param world world
     * @param pos   chunk position
     * @return entity chunk record, or {@code null} when vanilla would write none (no entities)
     */
    private static CompoundTag entityRecord(final ServerLevel world, final ChunkPos pos) {
        final ListTag entities = new ListTag();
        final AABB column = new AABB(pos.getMinBlockX(), world.getMinY(), pos.getMinBlockZ(),
                pos.getMaxBlockX() + 1, world.getMaxY() + 1, pos.getMaxBlockZ() + 1);
        for (final Entity entity : world.getEntities(null, column)) {
            if (entity instanceof Player || !entity.chunkPosition().equals(pos)) {
                continue;
            }
            final CompoundTag nbt = ChunkEntityNbtCapture.serializeEntityNbt(entity);
            if (nbt != null) {
                entities.add(nbt);
            }
        }
        if (entities.isEmpty()) {
            return null;
        }
        final CompoundTag record = new CompoundTag();
        record.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        record.put("Position", new IntArrayTag(new int[] {pos.x(), pos.z()}));
        record.put("Entities", entities);
        return record;
    }

    /**
     * Size of an NBT compound zlib-compressed the way vanilla region files store it.
     *
     * @param nbt compound
     * @return compressed bytes
     */
    private static int deflatedSize(final CompoundTag nbt) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
            NbtIo.write(nbt, out);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed compressing NBT", e);
        }
        return bytes.size();
    }

    /**
     * Space a record of {@code bytes} takes in a vanilla region file.
     *
     * @param bytes compressed record size
     * @return whole sectors in bytes
     */
    private static long sectorRounded(final int bytes) {
        return (long) Math.ceil((bytes + RECORD_HEADER_BYTES) / (double) SECTOR_BYTES) * SECTOR_BYTES;
    }
}
