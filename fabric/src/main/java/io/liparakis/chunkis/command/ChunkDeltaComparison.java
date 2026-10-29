package io.liparakis.chunkis.command;

import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

/**
 * Compares two chunk deltas block by block, block entity by block entity, and by
 * entities and metadata, producing a summary with sample mismatches.
 *
 * <p>Used by the MCA inspect and remigrate debug commands.</p>
 */
final class ChunkDeltaComparison {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private ChunkDeltaComparison() {
        throw new AssertionError("Utility class");
    }

    /**
     * Compares two chunk deltas and extracts high-signal mismatch summaries.
     *
     * @param expected translated MCA snapshot
     * @param actual   persisted CIS snapshot
     * @return comparison summary
     */
    static ComparisonSummary compareDeltas(final ChunkDelta<BlockState, NbtCompound> expected,
            final ChunkDelta<BlockState, NbtCompound> actual) {
        final List<String> realBlockSamples = new ArrayList<>();
        final List<String> explicitAirOnlySamples = new ArrayList<>();
        final List<String> blockEntitySamples = new ArrayList<>();
        final List<String> entitySamples = new ArrayList<>();

        final var expectedBlocks = materializeBlocks(expected);
        final var actualBlocks = materializeBlocks(actual);
        final BlockMismatchSummary blockMismatchSummary = countBlockMismatches(expectedBlocks,
                actualBlocks,
                realBlockSamples,
                explicitAirOnlySamples);

        final int blockEntityMismatchCount = countBlockEntityMismatches(materializeBlockEntities(expected),
                materializeBlockEntities(actual),
                blockEntitySamples);

        final boolean entitiesMatch = compareEntityPayloads(expected, actual, entitySamples);
        final boolean metadataMatch = compareMetadata(expected.getChunkMetadata(), actual.getChunkMetadata());
        return new ComparisonSummary(blockMismatchSummary.realMismatchCount(),
                blockMismatchSummary.explicitAirOnlyMismatchCount(),
                blockMismatchSummary.totalMismatchCount(),
                blockEntityMismatchCount,
                entitiesMatch,
                metadataMatch,
                realBlockSamples,
                explicitAirOnlySamples,
                blockEntitySamples,
                entitySamples);
    }

    /**
     * Materializes one delta's sparse blocks into a local-position map.
     *
     * @param delta source delta
     * @return local position to state map
     */
    private static Map<Long, BlockState> materializeBlocks(final ChunkDelta<BlockState, NbtCompound> delta) {
        final Map<Long, BlockState> blocks = new java.util.HashMap<>();
        delta.forEachBlockInstruction((x, y, z, paletteId, state) -> blocks.put(BlockPos.asLong(x, y, z), state));
        return blocks;
    }

    /**
     * Counts block-state mismatches between two materialized block maps and
     * records a few examples.
     *
     * @param expected               expected block map
     * @param actual                 actual block map
     * @param realSamples            sample collector for real block mismatches
     * @param explicitAirOnlySamples sample collector for explicit-air-only mismatches
     * @return mismatch breakdown
     */
    private static BlockMismatchSummary countBlockMismatches(final Map<Long, BlockState> expected,
            final Map<Long, BlockState> actual,
            final List<String> realSamples,
            final List<String> explicitAirOnlySamples) {
        final Set<Long> keys = new HashSet<>(expected.keySet());
        keys.addAll(actual.keySet());

        int totalMismatches = 0;
        int realMismatches = 0;
        int explicitAirOnlyMismatches = 0;
        for (final long key : keys) {
            final BlockState expectedState = expected.get(key);
            final BlockState actualState = actual.get(key);
            if (Objects.equals(expectedState, actualState)) {
                continue;
            }

            totalMismatches++;
            if (isExplicitAirOnlyMismatch(expectedState, actualState)) {
                explicitAirOnlyMismatches++;
                if (explicitAirOnlySamples.size() < 6) {
                    explicitAirOnlySamples.add(
                            formatLocalBlockPosition(key) + "=" + describeState(expectedState) + " -> " + describeState(
                                    actualState));
                }
                continue;
            }

            realMismatches++;
            if (realSamples.size() < 6) {
                realSamples.add(
                        formatLocalBlockPosition(key) + "=" + describeState(expectedState) + " -> " + describeState(
                                actualState));
            }
        }
        return new BlockMismatchSummary(realMismatches, explicitAirOnlyMismatches, totalMismatches);
    }

    /**
     * Returns whether a block mismatch is only an explicit persisted air entry
     * on one side versus no sparse entry on the other.
     *
     * @param expectedState expected block state, may be null
     * @param actualState   actual block state, may be null
     * @return {@code true} when the mismatch is explicit-air-only
     */
    private static boolean isExplicitAirOnlyMismatch(final BlockState expectedState, final BlockState actualState) {
        return expectedState == null && isAirState(actualState) || actualState == null && isAirState(expectedState);
    }

    /**
     * Returns whether a block state is an air state.
     *
     * @param state block state, may be null
     * @return {@code true} when the state is non-null and air
     */
    private static boolean isAirState(final BlockState state) {
        return state != null && state.isAir();
    }

    /**
     * Counts block-entity payload mismatches between two maps and records a few
     * examples.
     *
     * @param expected expected block entities
     * @param actual   actual block entities
     * @param samples  sample output collector
     * @return total mismatch count
     */
    private static int countBlockEntityMismatches(final java.util.Map<Long, NbtCompound> expected,
            final java.util.Map<Long, NbtCompound> actual,
            final List<String> samples) {
        final java.util.Set<Long> keys = new java.util.HashSet<>();
        keys.addAll(expected.keySet());
        keys.addAll(actual.keySet());

        int mismatches = 0;
        for (final long key : keys) {
            final NbtCompound expectedNbt = expected.get(key);
            final NbtCompound actualNbt = actual.get(key);
            if (NbtHelper.matches(expectedNbt, actualNbt, true)) {
                continue;
            }

            mismatches++;
            if (samples.size() < 4) {
                samples.add(formatLocalBlockPosition(key) + "=" + describeBlockEntity(expectedNbt) + " -> "
                        + describeBlockEntity(actualNbt));
            }
        }
        return mismatches;
    }

    /**
     * Materializes the effective persisted block-entity payloads for one chunk delta.
     *
     * <p>Chunks saved through the persisted-base-chunk path keep block entities in the
     * vanilla base NBT rather than in the sparse delta map. For inspection we need the
     * effective persisted view, not just the sparse overlay.</p>
     */
    private static java.util.Map<Long, NbtCompound> materializeBlockEntities(final io.liparakis.chunkis.core.ChunkDelta<BlockState, NbtCompound> delta) {
        final java.util.Map<Long, NbtCompound> blockEntities = new java.util.HashMap<>();
        if (delta == null) {
            return blockEntities;
        }

        final NbtCompound baseChunkNbt = CisNbtUtil.extractPersistedBaseChunkNbt(delta.getChunkMetadata());
        for (final NbtCompound blockEntityNbt : extractBlockEntityPayloads(baseChunkNbt)) {
            final Integer x = readIntOrNull(blockEntityNbt, "x");
            final Integer y = readIntOrNull(blockEntityNbt, "y");
            final Integer z = readIntOrNull(blockEntityNbt, "z");
            if (x == null || y == null || z == null) {
                continue;
            }
            blockEntities.put(BlockInstruction.packPos(x & 15, y, z & 15), blockEntityNbt);
        }
        blockEntities.putAll(delta.getBlockEntities());
        return blockEntities;
    }

    /**
     * Extracts block-entity payload compounds from modern or legacy chunk roots.
     */
    private static List<NbtCompound> extractBlockEntityPayloads(final NbtCompound root) {
        final List<NbtCompound> payloads = CisNbtUtil.extractCompoundList(chunkPayloadRoot(root), "block_entities");
        return payloads.isEmpty() ? CisNbtUtil.extractCompoundList(chunkPayloadRoot(root), "TileEntities") : payloads;
    }

    /**
     * Returns the nested legacy {@code Level} payload root when present.
     */
    private static NbtCompound chunkPayloadRoot(final NbtCompound root) {
        if (root == null) {
            return null;
        }
        return root.getCompound("Level")
                .orElse(root);
    }

    /**
     * Compares entity payload collections as normalized sorted NBT strings.
     *
     * @param expected expected delta
     * @param actual   actual delta
     * @param samples  mismatch sample collector
     * @return {@code true} when entity payloads match
     */
    private static boolean compareEntityPayloads(final io.liparakis.chunkis.core.ChunkDelta<BlockState, NbtCompound> expected,
            final io.liparakis.chunkis.core.ChunkDelta<BlockState, NbtCompound> actual,
            final List<String> samples) {
        final List<String> expectedEntities = materializeEntities(expected);
        final List<String> actualEntities = materializeEntities(actual);
        if (expectedEntities.equals(actualEntities)) {
            return true;
        }

        samples.add("expectedCount=" + expectedEntities.size() + ", actualCount=" + actualEntities.size());
        addFirstSample(samples, "expectedFirst=", expectedEntities);
        addFirstSample(samples, "actualFirst=", actualEntities);
        return false;
    }

    /**
     * Performs read int or null.
     */
    private static Integer readIntOrNull(final NbtCompound root, final String key) {
        return root == null ? null : root.getInt(key)
                .orElse(null);
    }

    /**
     * Performs add first sample.
     */
    private static void addFirstSample(final List<String> samples, final String prefix, final List<String> values) {
        if (values != null && !values.isEmpty()) {
            samples.add(prefix + values.getFirst());
        }
    }

    /**
     * Materializes and normalizes entity payloads for equality comparison.
     *
     * @param delta source delta
     * @return sorted entity payload list
     */
    private static List<String> materializeEntities(final io.liparakis.chunkis.core.ChunkDelta<BlockState, NbtCompound> delta) {
        final List<String> entities = new ArrayList<>();
        delta.forEachEntity(entity -> entities.add(entity == null ? "<null>" : entity.toString()));
        Collections.sort(entities);
        return entities;
    }

    /**
     * Compares the metadata fields Chunkis owns or preserves for migrated
     * authoritative chunks.
     *
     * @param expected expected metadata
     * @param actual   actual metadata
     * @return {@code true} when the relevant metadata matches
     */
    private static boolean compareMetadata(final NbtCompound expected, final NbtCompound actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }

        return CisNbtUtil.hasFullBlockBaseline(expected) == CisNbtUtil.hasFullBlockBaseline(actual)
                && CisNbtUtil.isMigratedAuthoritativeChunk(expected) == CisNbtUtil.isMigratedAuthoritativeChunk(actual)
                && CisNbtUtil.hasPersistedBaseChunkNbt(expected) == CisNbtUtil.hasPersistedBaseChunkNbt(actual)
                && CisNbtUtil.shouldUsePersistedBaseChunkForBlockBaseline(expected)
                == CisNbtUtil.shouldUsePersistedBaseChunkForBlockBaseline(actual)
                && NbtHelper.matches(CisNbtUtil.extractStructureData(expected),
                CisNbtUtil.extractStructureData(actual),
                true) && NbtHelper.matches(CisNbtUtil.extractPreservedAuxiliaryChunkNbt(expected),
                CisNbtUtil.extractPreservedAuxiliaryChunkNbt(actual),
                true);
    }

    /**
     * Formats a packed local block position as {@code x,y,z}.
     *
     * @param key packed local position key
     * @return formatted local coordinate string
     */
    private static String formatLocalBlockPosition(final long key) {
        return BlockInstruction.unpackX(key) + "," + BlockInstruction.unpackY(key) + ","
                + BlockInstruction.unpackZ(key);
    }

    /**
     * Formats a block state for readable diagnostics.
     *
     * @param state block state, may be null
     * @return compact state string
     */
    private static String describeState(final BlockState state) {
        if (state == null) {
            return "<air>";
        }
        return Registries.BLOCK.getId(state.getBlock()) + state.getEntries()
                .toString();
    }

    /**
     * Formats a block-entity payload for readable diagnostics.
     *
     * @param nbt block-entity payload, may be null
     * @return compact payload summary
     */
    private static String describeBlockEntity(final NbtCompound nbt) {
        if (nbt == null) {
            return "<none>";
        }
        return nbt.getString("id", "<missing>") + nbt.getKeys();
    }


    /**
     * Comparison summary for one MCA-vs-CIS chunk diff.
     *
     * @param realBlockMismatchCount       mismatched non-air/non-null block count
     * @param explicitAirOnlyMismatchCount mismatched explicit-air-only count
     * @param totalBlockMismatchCount      total block mismatch count
     * @param blockEntityMismatchCount     mismatched block-entity count
     * @param entitiesMatch                true when entity payloads match
     * @param metadataMatch                true when owned/preserved metadata matches
     * @param realBlockSamples             sample real block mismatches
     * @param explicitAirOnlySamples       sample explicit-air-only mismatches
     * @param blockEntitySamples           sample block-entity mismatches
     * @param entitySamples                sample entity mismatch details
     */
    record ComparisonSummary(int realBlockMismatchCount, int explicitAirOnlyMismatchCount, int totalBlockMismatchCount,
                             int blockEntityMismatchCount, boolean entitiesMatch, boolean metadataMatch,
                             List<String> realBlockSamples, List<String> explicitAirOnlySamples,
                             List<String> blockEntitySamples, List<String> entitySamples) {

    }

    /**
     * Block mismatch breakdown separating real drift from explicit-air-only
     * sparse entry pollution.
     *
     * @param realMismatchCount            count of actual state mismatches
     * @param explicitAirOnlyMismatchCount count of null-vs-air mismatches
     * @param totalMismatchCount           total mismatch count
     */
    private record BlockMismatchSummary(int realMismatchCount, int explicitAirOnlyMismatchCount,
                                        int totalMismatchCount) {

    }

}
