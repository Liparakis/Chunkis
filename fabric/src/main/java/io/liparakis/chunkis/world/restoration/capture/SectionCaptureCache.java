package io.liparakis.chunkis.world.restoration.capture;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.restoration.core.PalettedContainerFields;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.util.collection.PaletteStorage;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.Palette;
import net.minecraft.world.chunk.PalettedContainer;

/**
 * Lets a full-baseline snapshot re-read only the sections whose block data changed since the
 * previous snapshot of the same delta.
 *
 * <p>Each section is fingerprinted from its raw palette storage and palette entries, so every write
 * path is seen, including ones that bypass {@code WorldChunk.setBlockState}. A record is valid only
 * while the delta's {@link ChunkDelta#getBlockPayloadEpoch() bulk-change counter} is unchanged:
 * live edits upsert instructions without touching it, while decoding, clearing or any other bulk
 * rewrite invalidates the record and forces a full read.</p>
 *
 * <p>Records are weakly keyed by delta identity and only touched on the server thread.</p>
 */
final class SectionCaptureCache {

    /**
     * Fingerprint of a section with no non-air blocks.
     */
    private static final long EMPTY_SECTION = 0x5EC7_10E1_E3E7_0001L;

    /**
     * Last capture per delta.
     */
    private static final Map<ChunkDelta<?, ?>, Record> RECORDS = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Static holder; not instantiable.
     */
    private SectionCaptureCache() {
    }

    /**
     * Returns the delta's last capture if its instructions are still exactly what that capture built,
     * plus later live edits.
     *
     * @param delta delta about to be captured again
     * @return valid record, or {@code null}
     */
    static Record validRecord(final ChunkDelta<?, ?> delta) {
        final Record record = RECORDS.get(delta);
        return record != null && record.epoch() == delta.getBlockPayloadEpoch() ? record : null;
    }

    /**
     * Returns the non-air block count of the last capture into {@code delta}, even when its instructions have
     * since been released; used to keep the shrink check of the next capture meaningful.
     *
     * @param delta delta
     * @return non-air blocks of the last capture, or 0 when none is recorded
     */
    static int lastNonAirBlocks(final ChunkDelta<?, ?> delta) {
        final Record record = RECORDS.get(delta);
        return record != null ? record.nonAirBlocks() : 0;
    }

    /**
     * Records the non-air block count of a capture that built no instructions, so the next capture's shrink
     * check has a baseline. The record compares unequal to any fingerprints, so it never lets a capture keep
     * instructions.
     *
     * @param delta        captured delta
     * @param nonAirBlocks live non-air block count at capture time
     */
    static void recordNonAirBlocks(final ChunkDelta<?, ?> delta, final int nonAirBlocks) {
        RECORDS.put(delta, new Record(delta.getBlockPayloadEpoch(), new long[0], nonAirBlocks));
    }

    /**
     * Records a completed capture. Call after the last instruction was appended.
     *
     * @param delta        captured delta
     * @param fingerprints per-section fingerprints the capture read, or {@code null} when unavailable
     * @param nonAirBlocks live non-air block count at capture time
     */
    static void record(final ChunkDelta<?, ?> delta, final long[] fingerprints, final int nonAirBlocks) {
        if (fingerprints == null) {
            RECORDS.remove(delta);
            return;
        }
        RECORDS.put(delta, new Record(delta.getBlockPayloadEpoch(), fingerprints, nonAirBlocks));
    }

    /**
     * Fingerprints every section, or returns {@code null} when raw container access is unavailable.
     *
     * @param sections chunk sections
     * @return per-section fingerprints, or {@code null}
     */
    static long[] fingerprints(final ChunkSection[] sections) {
        if (!PalettedContainerFields.AVAILABLE) {
            return null;
        }
        final long[] fingerprints = new long[sections.length];
        for (int i = 0; i < sections.length; i++) {
            fingerprints[i] = fingerprint(sections[i]);
        }
        return fingerprints;
    }

    /**
     * Marks the sections that must be read again: all of them without a matching previous record.
     *
     * @param previous     previous capture, or {@code null}
     * @param fingerprints current fingerprints, or {@code null}
     * @param sectionCount number of sections
     * @return per-section "read again" flags
     */
    static boolean[] changedSections(final Record previous, final long[] fingerprints, final int sectionCount) {
        final boolean[] changed = new boolean[sectionCount];
        final boolean comparable = previous != null && fingerprints != null
                && previous.fingerprints().length == sectionCount;
        for (int i = 0; i < sectionCount; i++) {
            changed[i] = !comparable || previous.fingerprints()[i] != fingerprints[i];
        }
        return changed;
    }

    /**
     * Fingerprints one section's block data.
     *
     * @param section section, possibly {@code null}
     * @return 64-bit fingerprint
     */
    private static long fingerprint(final ChunkSection section) {
        if (section == null || section.isEmpty()) {
            return EMPTY_SECTION;
        }
        final PalettedContainer<BlockState> container = section.getBlockStateContainer();
        final Object data = PalettedContainerFields.data(container);
        final PaletteStorage storage = PalettedContainerFields.storage(data);
        final Palette<BlockState> palette = PalettedContainerFields.palette(data);

        long hash = mix(0x9E37_79B9_7F4A_7C15L, palette.getSize());
        for (int i = 0; i < palette.getSize(); i++) {
            hash = mix(hash, System.identityHashCode(palette.get(i)));
        }
        hash = mix(hash, storage.getElementBits());
        for (final long word : storage.getData()) {
            hash = mix(hash, word);
        }
        return hash;
    }

    /**
     * Folds one value into a running 64-bit hash.
     *
     * @param hash  running hash
     * @param value value to fold in
     * @return new hash
     */
    private static long mix(final long hash, final long value) {
        final long mixed = (hash ^ value) * 0xBF58_476D_1CE4_E5B9L;
        return mixed ^ (mixed >>> 31);
    }

    /**
     * One capture of a delta.
     *
     * @param epoch        the delta's bulk-change counter right after the capture
     * @param fingerprints per-section fingerprints read by the capture
     * @param nonAirBlocks live non-air block count at capture time
     */
    record Record(long epoch, long[] fingerprints, int nonAirBlocks) {
    }
}
