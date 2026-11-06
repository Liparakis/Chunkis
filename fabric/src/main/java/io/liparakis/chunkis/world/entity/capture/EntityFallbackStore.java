package io.liparakis.chunkis.world.entity.capture;

import io.liparakis.chunkis.adapter.FabricNbtAdapter;
import io.liparakis.chunkis.world.tracking.save.ChunkisStoragePaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueOutput;
import io.liparakis.chunkis.Chunkis;

/**
 * Durable entity-only snapshots for positions without a safely writable terrain delta.
 * A snapshot owns the complete entity list, including an empty list. Ownership stays
 * here on later saves so stale CIS copies cannot resurrect removed entities.
 * Writes finish before vanilla's write is cancelled; failures abort the save explicitly.
 * The independent version-1 NBT envelope does not change the CIS terrain format.
 */
public final class EntityFallbackStore {
    /** Version of this independent snapshot envelope. */
    private static final String VERSION_KEY = "ChunkisEntityFallbackVersion";

    /** Utility class. */
    private EntityFallbackStore() {
    }

    /** Resolves an entity snapshot without creating any directory. */
    public static Path path(final ServerLevel world, final ChunkPos pos) {
        return ChunkisStoragePaths.computeRegionsDirectory(world.getServer().getWorldPath(LevelResource.ROOT),
                world.dimension()).getParent().resolve("entity-fallback")
                .resolve("c." + pos.x() + "." + pos.z() + ".nbt");
    }

    /** Tests ownership, including empty snapshots; a malformed existing file must fail on read. */
    public static boolean owns(final ServerLevel world, final ChunkPos pos) {
        final Path target = path(world, pos);
        try {
            Files.readAttributes(target, BasicFileAttributes.class);
            return true;
        } catch (final NoSuchFileException absent) {
            return false;
        } catch (final IOException failure) {
            throw new UncheckedIOException("Cannot inspect entity fallback for " + pos + " at " + target, failure);
        }
    }

    /** Saves vanilla's complete list, preserving passenger trees through Entity.save. Server thread only. */
    public static void save(final ServerLevel world, final ChunkPos pos, final List<? extends Entity> entities) {
        final CompoundTag root = NbtUtils.addCurrentDataVersion(new CompoundTag());
        root.store("Position", ChunkPos.CODEC, pos);
        final ListTag payloads = new ListTag();
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(
                ChunkAccess.problemPath(pos), Chunkis.LOGGER)) {
            for (final Entity entity : entities) {
                final TagValueOutput output = TagValueOutput.createWithContext(
                        reporter.forChild(entity.problemPath()), entity.registryAccess());
                if (entity.save(output)) {
                    payloads.add(output.buildResult());
                }
            }
        }
        root.put("Entities", payloads);
        try {
            write(path(world, pos), pos, root);
        } catch (final IOException failure) {
            throw new UncheckedIOException("Cannot save entity fallback for " + pos + " in "
                    + world.dimension().identifier() + " at " + path(world, pos), failure);
        }
    }

    /** Atomically replaces a snapshot. An unsupported atomic move fails without replacing the old file. */
    public static void write(final Path target, final ChunkPos pos, final CompoundTag payload) throws IOException {
        if (Files.exists(target)) {
            read(target, pos); // Never overwrite an unreadable or future-version snapshot.
        }
        final CompoundTag root = payload.copy();
        root.putInt(VERSION_KEY, 1);
        root.store("Position", ChunkPos.CODEC, pos);
        Files.createDirectories(target.getParent());
        final Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            NbtIo.writeCompressed(root, temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Reads and validates the envelope before native entity data fixing and deserialization. */
    public static CompoundTag read(final Path target, final ChunkPos pos) throws IOException {
        final CompoundTag root = NbtIo.readCompressed(target, NbtAccounter.create(FabricNbtAdapter.MAX_NBT_SIZE));
        final int version = root.getIntOr(VERSION_KEY, -1);
        if (version != 1) {
            throw new IOException("Unsupported " + VERSION_KEY + "=" + version + "; expected 1 for " + pos
                    + " at " + target);
        }
        if (!root.read("Position", ChunkPos.CODEC).filter(pos::equals).isPresent()) {
            throw new IOException("Invalid Position in entity fallback v1 for " + pos + " at " + target);
        }
        if (root.getList("Entities").isEmpty()) {
            throw new IOException("Missing or invalid Entities list in entity fallback v1 for " + pos + " at " + target);
        }
        return root;
    }
}
