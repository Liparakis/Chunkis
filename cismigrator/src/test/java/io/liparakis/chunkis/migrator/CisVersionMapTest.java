package io.liparakis.chunkis.migrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CisVersionMap}, verifying that the upgrade paths
 * are correctly planned for both sequential and multi-step migrations,
 * and that invalid paths are properly rejected.
 */
class CisVersionMapTest {

    /**
     * Verifies that a sequential upgrade path (e.g., v7 &rarr; v9) is correctly
     * mapped into the expected migration steps.
     */
    @Test
    void plansSequentialUpgradePath() {
        final CisVersionPath path = CisVersionMap.plan(7, 9);

        assertEquals(2, path.steps().size());
        assertEquals(7, path.steps().getFirst().fromVersion());
        assertEquals(9, path.steps().getLast().toVersion());
    }

    /**
     * Verifies that planning with identical versions returns a no-op path.
     */
    @Test
    void returnsNoOpPathWhenVersionsAlreadyMatch() {
        final CisVersionPath path = CisVersionMap.plan(9, 9);

        assertTrue(path.isNoOp());
        assertEquals(0, path.steps().size());
    }

    /**
     * Verifies that planning an upgrade path from an unknown version
     * throws an {@link IllegalArgumentException}.
     */
    @Test
    void rejectsUnknownUpgradePath() {
        assertThrows(IllegalArgumentException.class, () -> CisVersionMap.plan(6, 9));
    }

    /**
     * Verifies that planning a downgrade path (e.g., v9 &rarr; v8)
     * is unsupported and throws an {@link IllegalArgumentException}.
     */
    @Test
    void rejectsDowngrades() {
        assertThrows(IllegalArgumentException.class, () -> CisVersionMap.plan(9, 8));
    }
}
