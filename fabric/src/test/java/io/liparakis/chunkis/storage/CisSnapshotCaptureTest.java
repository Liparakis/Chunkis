package io.liparakis.chunkis.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CisSnapshotCaptureTest {

    @Test
    void convertsSectionIndexToAbsoluteBlockY() {
        assertEquals(-64, CisSnapshotCapture.toWorldY(-64, 0, 0));
        assertEquals(-49, CisSnapshotCapture.toWorldY(-64, 0, 15));
        assertEquals(-48, CisSnapshotCapture.toWorldY(-64, 1, 0));
        assertEquals(319, CisSnapshotCapture.toWorldY(-64, 23, 15));
    }
}
