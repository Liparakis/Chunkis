package io.liparakis.chunkis.migrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Inflater;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that the V8 CIS fixture files can be copied into isolated temporary
 * storage without mutating the originals, and that every chunk slot in the
 * copied files carries the expected version number ({@code 8}).
 */
class CisFixtureCopyTest {

    /**
     * Root directory name for version 8 fixtures in the resources folder.
     */
    private static final String FIXTURE_ROOT = "V8";

    /**
     * List of expected region file names within the fixture.
     */
    private static final String[] REGION_FILES = {
            "r.-1.0.cis",
            "r.-1.1.cis",
            "r.0.0.cis",
            "r.0.1.cis"
    };

    /**
     * Inflate/deflate/hash buffer size; large enough to avoid repeated array grows.
     */
    private static final int IO_BUFFER_SIZE = 8192;

    /**
     * Bytes per chunk header entry (offset int + length int).
     */
    private static final int HEADER_ENTRY_BYTES = 8;

    /**
     * Number of chunk slots per region file (32 × 32).
     */
    private static final int REGION_SLOTS = 1024;

    /**
     * Temporary directory for storing isolated fixture copies.
     */
    @TempDir
    Path tempDir;

    /**
     * Converts a byte array to a hex-encoded string.
     */
    private static String toHex(final byte[] bytes) {
        final StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            builder.append(Character.forDigit((b >>> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }

    /**
     * Inflates a zlib-compressed byte array.
     */
    private static byte[] inflate(final byte[] compressed) throws Exception {
        final Inflater inflater = new Inflater();
        inflater.setInput(compressed);

        final byte[] buffer = new byte[IO_BUFFER_SIZE];
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (!inflater.finished()) {
            final int read = inflater.inflate(buffer);
            if (read == 0 && inflater.needsInput()) {
                break;
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    /**
     * Reads a big-endian 4-byte integer from the provided byte array.
     */
    private static int readInt(final byte[] data, final int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    /**
     * Verifies that flipping the "copy" bit for V8 fixtures correctly replicates
     * the static files into a temporary environment without altering the
     * original resource files.
     */
    @Test
    void copiesV8FixturesIntoIsolatedTempStorageWithoutMutatingOriginals() throws Exception {
        final List<String> originalHashes = hashFixtureFiles();
        final Path copiedRoot = copyFixturesToTempStorage();

        final List<Integer> copiedVersions = scanChunkVersions(copiedRoot.resolve("regions"));
        assertFalse(copiedVersions.isEmpty(), "Expected copied V8 fixtures to contain at least one chunk payload");
        copiedVersions.forEach(version -> assertEquals(8, version));

        assertEquals(originalHashes, hashFixtureFiles(),
                "Fixture hashes changed even though the test should only copy them into temp storage");
    }

    /**
     * Copies the static V8 test resources into the temporary test directory.
     *
     * @return path to the new storage root in {@code tempDir}
     */
    private Path copyFixturesToTempStorage() throws IOException {
        final Path storageRoot = tempDir.resolve("fixture-copy");
        final Path regionsDir = storageRoot.resolve("regions");
        Files.createDirectories(regionsDir);

        copyResource(FIXTURE_ROOT + "/global_ids.json", storageRoot.resolve("global_ids.json"));
        for (final String regionFile : REGION_FILES) {
            copyResource(FIXTURE_ROOT + "/regions/" + regionFile, regionsDir.resolve(regionFile));
        }

        return storageRoot;
    }

    /**
     * Copies a single resource file to the specified destination path.
     */
    private void copyResource(final String resourcePath, final Path destination) throws IOException {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IOException("Missing test resource: " + resourcePath);
            }
            Files.copy(input, destination);
        }
    }

    /**
     * Computes SHA-256 hashes for all fixture files in the classpath.
     *
     * @return list of hex-encoded hashes
     */
    private List<String> hashFixtureFiles() throws Exception {
        final List<String> hashes = new ArrayList<>(REGION_FILES.length + 1);
        hashes.add(hashResource(FIXTURE_ROOT + "/global_ids.json"));
        for (final String regionFile : REGION_FILES) {
            hashes.add(hashResource(FIXTURE_ROOT + "/regions/" + regionFile));
        }
        return hashes;
    }

    /**
     * Computes the SHA-256 hash of a single classpath resource.
     */
    private String hashResource(final String resourcePath) throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IOException("Missing test resource: " + resourcePath);
            }

            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] buffer = new byte[IO_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return toHex(digest.digest());
        }
    }

    /**
     * Reads every non-empty chunk slot from each region file and returns the
     * decoded version field (bytes 4–7 of the raw payload).
     *
     * @param regionsDir directory containing the copied {@code .cis} files
     * @return list of version values, one per populated chunk slot
     */
    private List<Integer> scanChunkVersions(final Path regionsDir) throws Exception {
        final List<Integer> versions = new ArrayList<>();

        for (final String regionFile : REGION_FILES) {
            final byte[] bytes = Files.readAllBytes(regionsDir.resolve(regionFile));

            for (int index = 0; index < REGION_SLOTS; index++) {
                final int headerOffset = index * HEADER_ENTRY_BYTES;
                final int chunkOffset = readInt(bytes, headerOffset);
                final int chunkLength = readInt(bytes, headerOffset + 4);

                if (chunkOffset == 0 || chunkLength == 0) {
                    continue;
                }

                final byte[] compressed = new byte[chunkLength];
                System.arraycopy(bytes, chunkOffset, compressed, 0, chunkLength);
                versions.add(readInt(inflate(compressed), 4));
            }
        }

        return versions;
    }
}