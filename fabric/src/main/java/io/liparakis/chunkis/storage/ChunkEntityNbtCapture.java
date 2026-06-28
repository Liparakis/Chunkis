package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.Chunkis;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Uuids;

import java.util.UUID;

public final class ChunkEntityNbtCapture {

    private ChunkEntityNbtCapture() {
        throw new AssertionError("Utility class");
    }

    public static NbtCompound serializeEntityNbt(final Entity entity) {
        try (final ErrorReporter.Logging logging = new ErrorReporter.Logging(
                entity.getErrorReporterContext(), Chunkis.LOGGER)) {
            final NbtWriteView writeView = NbtWriteView.create(logging, entity.getRegistryManager());
            entity.writeData(writeView);
            final NbtCompound nbt = writeView.getNbt();
            CisNbtUtil.ensureEntityIdPresent(nbt, entity);
            return nbt;
        } catch (final Exception e) {
            Chunkis.LOGGER.debug(
                    "Chunkis: Skipped entity capture for {} in chunk {}",
                    entity.getType(), entity.getChunkPos(), e
            );
            return null;
        }
    }

    public static String entityUuid(final NbtCompound entityNbt) {
        final UUID uuid = parseEntityUuid(entityNbt);
        return uuid == null ? null : uuid.toString();
    }

    public static UUID parseEntityUuid(final NbtCompound entityNbt) {
        if (entityNbt == null) {
            return null;
        }
        return entityNbt.getIntArray("UUID")
                .map(Uuids::toUuid)
                .orElse(null);
    }
}
