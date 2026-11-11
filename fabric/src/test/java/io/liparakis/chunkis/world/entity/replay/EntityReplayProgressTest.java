package io.liparakis.chunkis.world.entity.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Deterministic replay-budget regressions without a running Minecraft world or wall-clock timing. */
class EntityReplayProgressTest {
    /** Other dimensions must not make a blocked prefix starve later, ready entities on every tick. */
    @Test
    void blockedPrefixDoesNotStarveReadyEntitiesAcrossDimensionTicks() {
        final EntityReplayProgress<Integer> progress = new EntityReplayProgress<>();
        final List<Map.Entry<Integer, Integer>> entries = IntStream.range(0, 100)
                .mapToObj(i -> Map.entry(i, i)).toList();
        final Set<Integer> restored = new HashSet<>();
        for (int tick = 0; tick < 25; tick++) {
            final int start = progress.start("overworld", entries);
            for (int step = 0; step < 4; step++) {
                final int key = entries.get((start + step) % entries.size()).getKey();
                if (key >= 90) {
                    restored.add(key);
                }
            }
            progress.pause("overworld", entries.get((start + 4) % entries.size()).getKey());
            progress.complete("nether");
            progress.complete("end");
        }
        assertEquals(Set.of(90, 91, 92, 93, 94, 95, 96, 97, 98, 99), restored);
    }

    /** A dense chunk must resume at the next entity rather than repeatedly at the chunk's first UUID. */
    @Test
    void resumesWithinOneChunk() {
        final EntityReplayProgress<String> progress = new EntityReplayProgress<>();
        final List<Map.Entry<String, Integer>> entries = List.of(
                Map.entry("chunk/a", 0), Map.entry("chunk/b", 0), Map.entry("chunk/c", 0));
        progress.pause("overworld", "chunk/b");
        assertEquals(1, progress.start("overworld", entries));
    }

    /** Dimensions with their own unfinished work retain independent positions. */
    @Test
    void interleavedDimensionsKeepIndependentProgress() {
        final EntityReplayProgress<Integer> progress = new EntityReplayProgress<>();
        final List<Map.Entry<Integer, Integer>> entries = List.of(
                Map.entry(1, 0), Map.entry(2, 0), Map.entry(3, 0));
        progress.pause("overworld", 3);
        progress.pause("nether", 2);
        assertEquals(2, progress.start("overworld", entries));
        assertEquals(1, progress.start("nether", entries));
        progress.complete("nether");
        assertEquals(2, progress.start("overworld", entries));
        assertEquals(0, progress.start("nether", entries));
    }

    /** Removal of a cursor entry advances to its successor or wraps to the beginning. */
    @Test
    void removedEntryResumesAtSuccessorOrWraps() {
        final EntityReplayProgress<Integer> progress = new EntityReplayProgress<>();
        progress.pause("overworld", 2);
        assertEquals(1, progress.start("overworld", List.of(Map.entry(1, 0), Map.entry(3, 0))));
        assertEquals(0, progress.start("overworld", List.of(Map.entry(1, 0))));
        assertEquals(0, progress.start("overworld", List.of()));
    }

    /** A subsequent server lifecycle must not inherit an earlier world's progress. */
    @Test
    void clearResetsAllDimensions() {
        final EntityReplayProgress<Integer> progress = new EntityReplayProgress<>();
        final List<Map.Entry<Integer, Integer>> entries = List.of(Map.entry(1, 0), Map.entry(2, 0));
        progress.pause("overworld", 2);
        progress.pause("nether", 2);
        progress.clear();
        assertEquals(0, progress.start("overworld", entries));
        assertEquals(0, progress.start("nether", entries));
    }
}
