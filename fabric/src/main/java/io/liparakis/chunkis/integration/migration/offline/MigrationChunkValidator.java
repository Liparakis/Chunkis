package io.liparakis.chunkis.integration.migration.offline;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.storage.io.CisStorage;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.slf4j.Logger;

/**
 * Validates that a chunk written to CIS during migration matches the snapshot
 * built from its MCA source (blocks, block entities, entities and metadata shape).
 */
public final class MigrationChunkValidator {

    /**
     * Logger for this class.
     */
    private static final Logger LOGGER = Chunkis.LOGGER;

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private MigrationChunkValidator() {
        throw new AssertionError("Utility class");
    }

    /**
     * Validates that the stored migrated chunk still matches the translated
     * authoritative snapshot shape, not just its marker flags.
     */
    static MigrationValidationResult validateMigratedChunk(final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage,
            final ChunkPos chunkPos,
            final ChunkDelta<BlockState, NbtCompound> expected) {
        final ChunkDelta<BlockState, NbtCompound> stored = storage.load(new CisChunkPos(chunkPos.x, chunkPos.z));
        return matchesMigratedChunkShape(expected, stored);
    }

    /**
     * Performs matches migrated chunk shape.
     */
    static MigrationValidationResult matchesMigratedChunkShape(final ChunkDelta<BlockState, NbtCompound> expected,
            final ChunkDelta<BlockState, NbtCompound> actual) {
        if (expected == null || actual == null || actual.isEmpty()) {
            return MigrationValidationResult.failure("NULL_OR_EMPTY", "expected or actual is null/empty");
        }

        if (!CisNbtUtil.hasFullBlockBaseline(actual.getChunkMetadata())) {
            return MigrationValidationResult.failure("MISSING_BASELINE", "missing full block baseline flag");
        }
        if (!CisNbtUtil.isMigratedAuthoritativeChunk(actual.getChunkMetadata())) {
            return MigrationValidationResult.failure("NOT_AUTHORITATIVE", "missing migrated authoritative marker");
        }
        final Map<Long, String> expectedNonAir = canonicalNonAirBlockMap(expected);
        final Map<Long, String> actualNonAir = canonicalNonAirBlockMap(actual);
        if (expectedNonAir.size() != actualNonAir.size()) {
            return MigrationValidationResult.failure("BLOCK_COUNT_MISMATCH",
                    "expectedNonAir=" + expectedNonAir.size() + ", actualNonAir=" + actualNonAir.size());
        }
        if (expected.getBlockEntities()
                .size() != actual.getBlockEntities()
                .size()) {
            return MigrationValidationResult.failure("BE_COUNT_MISMATCH",
                    "expected=" + expected.getBlockEntities()
                            .size() + ", actual=" + actual.getBlockEntities()
                            .size());
        }
        if (expected.countPendingEntities() != actual.countPendingEntities()) {
            return MigrationValidationResult.failure("ENTITY_COUNT_MISMATCH",
                    "expected=" + expected.countPendingEntities() + ", actual=" + actual.countPendingEntities());
        }

        if (!NbtHelper.matches(CisNbtUtil.extractPersistedStructureMetadata(expected.getChunkMetadata()),
                CisNbtUtil.extractPersistedStructureMetadata(actual.getChunkMetadata()),
                true)) {
            return MigrationValidationResult.failure("STRUCTURE_META_MISMATCH", "structure metadata differs");
        }
        if (!NbtHelper.matches(CisNbtUtil.extractPreservedAuxiliaryChunkNbt(expected.getChunkMetadata()),
                CisNbtUtil.extractPreservedAuxiliaryChunkNbt(actual.getChunkMetadata()),
                true)) {
            return MigrationValidationResult.failure("AUX_META_MISMATCH", "auxiliary metadata differs");
        }

        final MigrationValidationResult blocks = validateBlocks(expected, actual);
        if (!blocks.valid()) {
            return blocks;
        }
        final MigrationValidationResult blockEntities = validateBlockEntities(expected, actual);
        if (!blockEntities.valid()) {
            return blockEntities;
        }
        final MigrationValidationResult entities = validateEntities(expected, actual);
        if (!entities.valid()) {
            return entities;
        }
        return MigrationValidationResult.success();
    }

    /**
     * Compares canonicalized non-air block state payloads between expected and stored chunks.
     *
     * @param expected translated authoritative snapshot
     * @param actual   stored CIS payload loaded back from disk
     * @return validation result describing the first block-level mismatch class
     */
    private static MigrationValidationResult validateBlocks(final ChunkDelta<BlockState, NbtCompound> expected,
            final ChunkDelta<BlockState, NbtCompound> actual) {
        final Map<Long, String> expectedNonAir = canonicalNonAirBlockMap(expected);
        final Map<Long, String> actualNonAir = canonicalNonAirBlockMap(actual);

        if (!expectedNonAir.keySet()
                .equals(actualNonAir.keySet())) {
            return MigrationValidationResult.failure("BLOCK_POS_MISMATCH",
                    "non-air positions differ: expected=" + expectedNonAir.size() + ", actual=" + actualNonAir.size());
        }

        int mismatchCount = 0;
        for (final Map.Entry<Long, String> entry : expectedNonAir.entrySet()) {
            final long pos = entry.getKey();
            final String expKey = entry.getValue();
            final String actKey = actualNonAir.get(pos);
            if (!expKey.equals(actKey)) {
                mismatchCount++;
                if (mismatchCount <= 10) {
                    LOGGER.warn("Block mismatch at {}: expected={} actual={}", BlockPos.fromLong(pos), expKey, actKey);
                }
            }
        }
        if (mismatchCount > 10) {
            LOGGER.warn("... {} additional block mismatches suppressed", mismatchCount - 10);
        }
        if (mismatchCount > 0) {
            return MigrationValidationResult.failure("BLOCK_STATE_MISMATCH", mismatchCount + " block state mismatches");
        }
        return MigrationValidationResult.success();
    }

    /**
     * Builds a stable string key for a block state so validation compares semantic state
     * rather than palette order or object identity.
     *
     * @param state block state to canonicalize
     * @return registry id plus sorted property assignments
     */
    private static String canonicalBlockStateKey(final BlockState state) {
        if (state == null) {
            return "null";
        }
        final StringBuilder sb = new StringBuilder(Registries.BLOCK.getId(state.getBlock())
                .toString());
        final Map<Property<?>, Comparable<?>> props = new TreeMap<>(Comparator.comparing(Property::getName));
        props.putAll(state.getEntries());
        if (!props.isEmpty()) {
            sb.append('[');
            sb.append(props.entrySet()
                    .stream()
                    .map(e -> e.getKey()
                            .getName() + "=" + e.getValue())
                    .collect(Collectors.joining(",")));
            sb.append(']');
        }
        return sb.toString();
    }

    /**
     * Builds a canonical map of non-air block states keyed by local position.
     * AIR and null states are excluded because they are not authoritative
     * semantic changes for migration validation.
     */
    private static Map<Long, String> canonicalNonAirBlockMap(final ChunkDelta<BlockState, NbtCompound> delta) {
        final Map<Long, String> result = new HashMap<>();
        delta.forEachBlockInstruction((x, y, z, paletteId, state) -> {
            if (state != null && !state.isAir()) {
                result.put(BlockPos.asLong(x, y, z), canonicalBlockStateKey(state));
            }
        });
        return result;
    }

    /**
     * Compares block-entity payloads between expected and stored chunks using recursive NBT matching.
     *
     * @param expected translated authoritative snapshot
     * @param actual   stored CIS payload loaded back from disk
     * @return validation result describing any block-entity mismatch
     */
    private static MigrationValidationResult validateBlockEntities(final ChunkDelta<BlockState, NbtCompound> expected,
            final ChunkDelta<BlockState, NbtCompound> actual) {
        final Set<Long> keys = new HashSet<>();
        keys.addAll(expected.getBlockEntities()
                .keySet());
        keys.addAll(actual.getBlockEntities()
                .keySet());
        int mismatchCount = 0;
        for (final long key : keys) {
            final NbtCompound exp = expected.getBlockEntities()
                    .get(key);
            final NbtCompound act = actual.getBlockEntities()
                    .get(key);
            if (!NbtHelper.matches(exp, act, true)) {
                mismatchCount++;
                final BlockPos pos = BlockPos.fromLong(key);
                if (mismatchCount <= 10) {
                    LOGGER.warn("Block entity mismatch at {}: expected keys={} actual keys={}",
                            pos,
                            exp.getKeys(),
                            act != null ? act.getKeys() : "null");
                }
            }
        }
        if (mismatchCount > 10) {
            LOGGER.warn("... {} additional block-entity mismatches suppressed", mismatchCount - 10);
        }
        if (mismatchCount > 0) {
            return MigrationValidationResult.failure("BLOCK_ENTITY_MISMATCH",
                    mismatchCount + " block entity mismatches");
        }
        return MigrationValidationResult.success();
    }

    /**
     * Compares pending-entity lists after normalizing them to sorted string representations.
     */
    private static MigrationValidationResult validateEntities(final ChunkDelta<BlockState, NbtCompound> expected,
            final ChunkDelta<BlockState, NbtCompound> actual) {
        final List<String> expectedEntities = new ArrayList<>();
        final List<String> actualEntities = new ArrayList<>();
        expected.forEachEntity(entity -> expectedEntities.add(entity == null ? "<null>" : entity.toString()));
        actual.forEachEntity(entity -> actualEntities.add(entity == null ? "<null>" : entity.toString()));
        expectedEntities.sort(String::compareTo);
        actualEntities.sort(String::compareTo);
        if (!Objects.equals(expectedEntities, actualEntities)) {
            return MigrationValidationResult.failure("ENTITY_MISMATCH",
                    "entity list differs: expected=" + expectedEntities.size() + ", actual=" + actualEntities.size());
        }
        return MigrationValidationResult.success();
    }

}
