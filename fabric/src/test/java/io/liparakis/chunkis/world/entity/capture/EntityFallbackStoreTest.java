package io.liparakis.chunkis.world.entity.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

/** Entity snapshots must survive reopening without depending on terrain or a live server. */
class EntityFallbackStoreTest {
    /** ChunkPos's codec depends on Minecraft's registered chunk statuses. */
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }
    /** Preserves opaque passenger and mod metadata and persists an empty replacement. */
    @Test
    void roundTripAndEmptyReplacement(@TempDir final Path directory) throws IOException {
        final Path target = directory.resolve("c.-12.34.nbt");
        final ChunkPos pos = new ChunkPos(-12, 34);
        final CompoundTag root = new CompoundTag();
        final CompoundTag entity = new CompoundTag();
        entity.putString("id", "example:custom_entity");
        final ListTag passengers = new ListTag();
        final CompoundTag passenger = new CompoundTag();
        passenger.putString("opaque:metadata", "preserved");
        passengers.add(passenger);
        entity.put("Passengers", passengers);
        final ListTag entities = new ListTag();
        entities.add(entity);
        root.put("Entities", entities);
        EntityFallbackStore.write(target, pos, root);
        assertEquals(entities, EntityFallbackStore.read(target, pos).getListOrEmpty("Entities"));
        root.put("Entities", new ListTag());
        EntityFallbackStore.write(target, pos, root);
        assertEquals(new ListTag(), EntityFallbackStore.read(target, pos).getListOrEmpty("Entities"));
    }

    /** Unknown versions, misplaced snapshots and truncated input must never be overwritten. */
    @Test
    void rejectsUnreadableSnapshot(@TempDir final Path directory) throws IOException {
        final Path target = directory.resolve("snapshot.nbt");
        final ChunkPos pos = new ChunkPos(12, -34);
        final CompoundTag root = new CompoundTag();
        root.put("Entities", new ListTag());
        EntityFallbackStore.write(target, pos, root);
        assertThrows(IOException.class, () -> EntityFallbackStore.read(target, new ChunkPos(13, -34)));
        final CompoundTag future = EntityFallbackStore.read(target, pos);
        future.putInt("ChunkisEntityFallbackVersion", 2);
        NbtIo.writeCompressed(future, target);
        final byte[] previous = Files.readAllBytes(target);
        assertThrows(IOException.class, () -> EntityFallbackStore.write(target, pos, root));
        org.junit.jupiter.api.Assertions.assertArrayEquals(previous, Files.readAllBytes(target));
        Files.write(target, new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> EntityFallbackStore.write(target, pos, root));
    }
}
