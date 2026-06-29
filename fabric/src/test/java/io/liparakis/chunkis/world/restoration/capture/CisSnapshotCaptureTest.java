package io.liparakis.chunkis.world.restoration.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CisSnapshotCaptureTest {

    @Test
    void convertsSectionIndexToAbsoluteBlockY() {
        assertEquals(-64, CisSnapshotCapture.toWorldY(-64, 0, 0));
        assertEquals(-49, CisSnapshotCapture.toWorldY(-64, 0, 15));
        assertEquals(-48, CisSnapshotCapture.toWorldY(-64, 1, 0));
        assertEquals(319, CisSnapshotCapture.toWorldY(-64, 23, 15));
    }

    @Test
    void detectsSuspiciousBaselineShrink() {
        assertTrue(CisSnapshotCapture.isSuspiciousBaselineShrink(1000, 599));
        assertFalse(CisSnapshotCapture.isSuspiciousBaselineShrink(1000, 600));
        assertFalse(CisSnapshotCapture.isSuspiciousBaselineShrink(0, 0));
    }
}
