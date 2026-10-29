package io.liparakis.chunkis.portal;

import io.liparakis.chunkis.Chunkis;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;

/**
 * Crash-safe persistence for the small portal NBT files.
 *
 * <p>Writes go to a temp file that is forced and atomically moved over the target,
 * so a crash leaves the old or the new file, never a truncated one. A file that
 * fails to load is moved aside instead of being silently replaced by the next
 * write, keeping it recoverable.</p>
 */
final class PortalNbtFiles {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private PortalNbtFiles() {
        throw new AssertionError("Utility class");
    }

    /**
     * Reads {@code path}, or returns {@code null} when it does not exist or is unreadable.
     *
     * <p>An unreadable file is renamed to {@code <name>.corrupt-<millis>} and logged.</p>
     *
     * @param path file to read
     * @param what human-readable description for log messages
     * @return the root compound, or {@code null}
     */
    static NbtCompound read(final Path path, final String what) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
            return NbtIo.readCompound(input);
        } catch (final Exception e) {
            final Path aside = path.resolveSibling(path.getFileName() + ".corrupt-" + System.currentTimeMillis());
            try {
                Files.move(path, aside);
                Chunkis.LOGGER.error("Chunkis: Failed to load {} {}; moved it to {} and starting empty", what, path,
                        aside, e);
            } catch (final IOException moveFailure) {
                Chunkis.LOGGER.error("Chunkis: Failed to load {} {} and could not move it aside", what, path, e);
            }
            return null;
        }
    }

    /**
     * Atomically replaces {@code path} with {@code root}.
     *
     * @param path target file
     * @param root compound to write
     * @throws IOException if writing or replacing fails; the previous file is then intact
     */
    static void write(final Path path, final NbtCompound root) throws IOException {
        Files.createDirectories(path.toAbsolutePath()
                .getParent());
        final Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            final DataOutputStream output = new DataOutputStream(Channels.newOutputStream(channel));
            NbtIo.writeCompound(root, output);
            output.flush();
            channel.force(true);
        }
        try {
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
