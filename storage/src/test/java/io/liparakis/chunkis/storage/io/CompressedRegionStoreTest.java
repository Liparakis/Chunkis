package io.liparakis.chunkis.storage.io;

import static org.assertj.core.api.Assertions.assertThat;

import io.liparakis.chunkis.core.CisChunkPos;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompressedRegionStoreTest {

    /**
     * Store directory.
     */
    @TempDir
    Path dir;

    /**
     * Payloads read back as last written, deleted ones are gone, and both hold after reopening.
     *
     * @throws IOException on I/O failure
     */
    @Test
    void payloadsSurviveOverwriteDeleteAndReopen() throws IOException {
        final CisChunkPos kept = new CisChunkPos(3, -7);
        final CisChunkPos removed = new CisChunkPos(-40, 70);
        final byte[] first = "first payload".repeat(100).getBytes(StandardCharsets.UTF_8);
        final byte[] second = "second payload".getBytes(StandardCharsets.UTF_8);

        final CompressedRegionStore store = new CompressedRegionStore(dir);
        store.write(kept, first);
        store.write(kept, second);
        store.write(removed, first);
        store.delete(removed);
        assertThat(store.read(kept)).isEqualTo(second);
        assertThat(store.read(removed)).isNull();
        store.close();

        final CompressedRegionStore reopened = new CompressedRegionStore(dir);
        assertThat(reopened.read(kept)).isEqualTo(second);
        assertThat(reopened.read(removed)).isNull();
        reopened.close();
    }

    /**
     * Reading or deleting an absent chunk leaves the directory empty.
     *
     * @throws IOException on I/O failure
     */
    @Test
    void readsAndDeletesNeverCreateRegionFiles() throws IOException {
        final CompressedRegionStore store = new CompressedRegionStore(dir);
        assertThat(store.read(new CisChunkPos(500, 500))).isNull();
        store.delete(new CisChunkPos(500, 500));
        store.close();

        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files).isEmpty();
        }
    }
}
