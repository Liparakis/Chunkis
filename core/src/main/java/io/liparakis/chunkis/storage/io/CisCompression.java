package io.liparakis.chunkis.storage.io;

import com.github.luben.zstd.Zstd;

import java.io.IOException;

public final class CisCompression {

    private CisCompression() {
        throw new AssertionError("Utility class");
    }

    public static byte[] decompressZstd(final byte[] data) throws IOException {
        final long decompressedSize = Zstd.getFrameContentSize(data);
        if (Zstd.isError(decompressedSize)) {
            throw new IOException("Failed to read CIS Zstd size: " + Zstd.getErrorName(decompressedSize));
        }
        if (decompressedSize <= 0L || decompressedSize > Integer.MAX_VALUE) {
            throw new IOException("Invalid CIS Zstd payload size: " + decompressedSize);
        }
        return Zstd.decompress(data, (int) decompressedSize);
    }
}
