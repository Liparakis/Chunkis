package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.ChunkPos;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Utility methods for Chunkis NBT construction, metadata envelopes, and vanilla
 * compatibility payloads.
 *
 * <p>This class intentionally keeps NBT helpers small and allocation-aware. Hot
 * save/load paths use the ownership-taking methods when they already hold isolated
 * NBT compounds and want to avoid defensive deep copies.</p>
 *
 * <p>Methods are not designed for concurrent mutation of the same
 * {@link NbtCompound}. Callers must ensure compounds are not shared across threads
 * while being modified.</p>
 *
 * @author Liparakis
 * @version 1.2
 */
public final class CisNbtUtil {

    /**
     * Root key under which Chunkis data is nested in synthetic chunk NBT.
     */
    public static final String CHUNKIS_DATA_KEY = "ChunkisData";

    /**
     * Chunk status string indicating no terrain has been generated yet.
     */
    public static final String STATUS_EMPTY = "minecraft:empty";

    /**
     * Vanilla key for the Minecraft data version integer.
     */
    public static final String DATA_VERSION_KEY = "DataVersion";

    /**
     * Vanilla key for chunk generation status.
     */
    public static final String STATUS_KEY = "Status";

    /**
     * Vanilla key for chunk X coordinate.
     */
    public static final String X_POS_KEY = "xPos";

    /**
     * Vanilla key for chunk Z coordinate.
     */
    public static final String Z_POS_KEY = "zPos";

    /**
     * Vanilla key for serialized block entity payloads inside chunk NBT.
     */
    public static final String BLOCK_ENTITIES_KEY = "block_entities";

    /**
     * Vanilla root key for structure metadata.
     */
    public static final String STRUCTURES_KEY = "structures";

    /**
     * Vanilla nested key for serialized structure starts.
     */
    public static final String STRUCTURE_STARTS_KEY = "starts";

    /**
     * Vanilla nested key for serialized structure references.
     */
    public static final String STRUCTURE_REFERENCES_KEY = "References";

    /**
     * Chunkis metadata envelope key inside persisted chunk metadata.
     */
    public static final String CHUNKIS_METADATA_KEY = "chunkis";

    /**
     * Chunkis flag for suppressing one-time vanilla repopulation during restore.
     */
    public static final String SUPPRESS_INITIAL_REPOPULATION_KEY =
            "suppress_initial_repopulation";

    /**
     * Chunkis flag indicating that the CIS block payload stores a complete
     * generated block baseline, including air.
     */
    public static final String FULL_BLOCK_BASELINE_KEY = "full_block_baseline";

    /**
     * Chunkis metadata flag indicating that the chunk currently contains at least
     * one nether portal block and should participate in portal lookup indexing.
     */
    public static final String PORTAL_CHUNK_KEY = "portal_chunk";

    /**
     * Chunkis metadata key for a vanilla-compatible serialized base chunk.
     */
    public static final String BASE_CHUNK_NBT_KEY = "base_chunk_nbt";

    /**
     * Synthetic NBT marker indicating that a chunk has CIS delta data stored
     * outside vanilla chunk NBT.
     */
    public static final String HAS_DELTA_KEY = "HasDelta";

    /**
     * Synthetic load-path marker describing whether persisted base chunk NBT was
     * used to build the temporary chunk NBT passed into vanilla deserialization.
     */
    public static final String LOAD_BASE_CHUNK_USAGE_KEY = "LoadBaseChunkUsage";

    /**
     * NBT key required by Minecraft's entity deserializer.
     */
    private static final String ENTITY_ID_KEY = "id";

    /**
     * Initial buffer size for small metadata serialization.
     */
    private static final int RAW_METADATA_INITIAL_CAPACITY = 256;

    private CisNbtUtil() {
        throw new AssertionError("Utility class");
    }

    /**
     * Creates a minimal vanilla-compatible chunk NBT compound.
     *
     * <p>The status is set to {@link #STATUS_EMPTY} so vanilla can regenerate
     * terrain if no persisted base chunk replaces this synthetic shell later.</p>
     *
     * @param pos         chunk position
     * @param dataVersion Minecraft data version
     * @return newly created base chunk NBT
     */
    public static NbtCompound createBaseNbt(
            final ChunkPos pos,
            final int dataVersion
    ) {
        Objects.requireNonNull(pos, "pos");

        return createBaseNbt(pos.x, pos.z, dataVersion);
    }

    /**
     * Creates a minimal vanilla-compatible chunk NBT compound.
     *
     * @param chunkX      chunk X coordinate
     * @param chunkZ      chunk Z coordinate
     * @param dataVersion Minecraft data version
     * @return newly created base chunk NBT
     */
    public static NbtCompound createBaseNbt(
            final int chunkX,
            final int chunkZ,
            final int dataVersion
    ) {
        final NbtCompound nbt = new NbtCompound();

        nbt.putInt(DATA_VERSION_KEY, dataVersion);
        nbt.putString(STATUS_KEY, STATUS_EMPTY);
        nbt.putInt(X_POS_KEY, chunkX);
        nbt.putInt(Z_POS_KEY, chunkZ);

        return nbt;
    }

    /**
     * Writes a Chunkis delta presence marker into synthetic chunk NBT.
     *
     * <p>The actual delta payload lives in CIS storage. This only tells the vanilla
     * deserialization path that Chunkis data exists and should be attached to the
     * generated chunk.</p>
     *
     * <p>No marker is written for null or empty deltas, avoiding an extra nested
     * compound for unmodified chunks.</p>
     *
     * @param root  root chunk NBT
     * @param delta delta to mark, may be {@code null}
     */
    public static void putDelta(
            final NbtCompound root,
            final ChunkDelta<BlockState, NbtCompound> delta
    ) {
        Objects.requireNonNull(root, "root");

        if (delta == null || delta.isEmpty()) {
            return;
        }

        final NbtCompound chunkisData = new NbtCompound();
        chunkisData.putBoolean(HAS_DELTA_KEY, true);

        root.put(CHUNKIS_DATA_KEY, chunkisData);
    }

    /**
     * Builds the chunk NBT passed into vanilla load conversion.
     *
     * <p>When persisted base chunk NBT exists and the delta does not carry a full
     * authoritative block baseline, that base becomes the deserialization
     * baseline and Chunkis only replays sparse post-capture deltas on top later.
     * Without a block-baseline-eligible base, Chunkis falls back to the synthetic
     * empty-shell NBT that triggers regeneration before replay.</p>
     *
     * @param pos         chunk position
     * @param dataVersion Minecraft data version
     * @param delta       Chunkis delta for the chunk, may be {@code null}
     * @return chunk NBT plus machine-readable base-chunk usage status
     */
    public static LoadChunkNbtResult buildLoadChunkNbt(
            final ChunkPos pos,
            final int dataVersion,
            final ChunkDelta<?, NbtCompound> delta
    ) {
        Objects.requireNonNull(pos, "pos");

        return buildLoadChunkNbt(pos.x, pos.z, dataVersion, delta);
    }

    /**
     * Builds the chunk NBT passed into vanilla load conversion.
     *
     * @param chunkX      chunk X coordinate
     * @param chunkZ      chunk Z coordinate
     * @param dataVersion Minecraft data version
     * @param delta       Chunkis delta for the chunk, may be {@code null}
     * @return chunk NBT plus machine-readable base-chunk usage status
     */
    public static LoadChunkNbtResult buildLoadChunkNbt(
            final int chunkX,
            final int chunkZ,
            final int dataVersion,
            final ChunkDelta<?, NbtCompound> delta
    ) {

        final Object metadata = delta != null ? delta.getChunkMetadata() : null;
        final NbtCompound baseChunkNbt = extractPersistedBaseChunkNbt(metadata);
        final boolean usePersistedBaseChunkForBlocks =
                shouldUsePersistedBaseChunkForBlockBaseline(metadata);
        final PersistedBaseChunkUsage baseChunkUsage;
        final NbtCompound root;

        if (baseChunkNbt != null && usePersistedBaseChunkForBlocks) {
            root = baseChunkNbt;
            baseChunkUsage = PersistedBaseChunkUsage.USED;

            if (delta != null && delta.countNonNullEntities() > 0) {
                replaceChunkEntitiesFromDelta(root, castDelta(delta));
            }
        } else {
            root = createBaseNbt(chunkX, chunkZ, dataVersion);
            baseChunkUsage = hasPersistedBaseChunkNbt(metadata)
                    ? PersistedBaseChunkUsage.SKIPPED
                    : PersistedBaseChunkUsage.MISSING;
        }

        if (delta != null) {
            putChunkMetadata(root, castDelta(delta));
            putDelta(root, castDelta(delta));
        }
        putLoadBaseChunkUsage(root, baseChunkUsage);

        return new LoadChunkNbtResult(root, baseChunkUsage);
    }

    /**
     * Merges sparse Chunkis block entity payloads into a vanilla-compatible base
     * chunk NBT compound.
     *
     * <p>Persisted base chunks may carry older block entity NBT. When a player
     * updates a chest inventory later, the sparse delta block entity must replace
     * the older base payload before vanilla deserializes the chunk.</p>
     *
     * @param root  base chunk NBT to mutate
     * @param delta chunk delta containing newer block entity payloads
     */
    public static void replaceChunkBlockEntitiesFromDelta(
            final NbtCompound root,
            final ChunkDelta<BlockState, NbtCompound> delta
    ) {
        Objects.requireNonNull(root, "root");

        if (delta == null) {
            return;
        }

        final NbtList blockEntities = new NbtList();

        delta.getBlockEntities().long2ObjectEntrySet().forEach(entry -> {
            final NbtCompound nbt = entry.getValue();
            if (nbt != null) {
                blockEntities.add(nbt.copy());
            }
        });

        root.put(BLOCK_ENTITIES_KEY, blockEntities);
    }

    /**
     * Replaces the vanilla {@code entities} list in chunk NBT from the delta's
     * saved entity payloads.
     *
     * @param root  base chunk NBT to mutate
     * @param delta chunk delta carrying authoritative non-player entities
     */
    public static void replaceChunkEntitiesFromDelta(
            final NbtCompound root,
            final ChunkDelta<BlockState, NbtCompound> delta
    ) {
        Objects.requireNonNull(root, "root");

        if (delta == null) {
            return;
        }

        final NbtList entities = new NbtList();
        delta.forEachEntity(nbt -> {
            if (nbt != null) {
                entities.add(nbt.copy());
            }
        });
        root.put("entities", entities);
    }

    /**
     * Copies persisted chunk metadata into synthetic chunk NBT.
     *
     * <p>Currently this exports vanilla structure metadata so structure starts and
     * references survive Chunkis' regenerate-on-load flow.</p>
     *
     * @param root  root chunk NBT
     * @param delta chunk delta, may be {@code null}
     */
    public static void putChunkMetadata(
            final NbtCompound root,
            final ChunkDelta<BlockState, NbtCompound> delta
    ) {
        Objects.requireNonNull(root, "root");

        if (delta == null) {
            return;
        }

        final NbtCompound structures =
                extractPersistedStructureMetadata(delta.getChunkMetadata());

        if (structures != null && !structures.isEmpty()) {
            root.put(STRUCTURES_KEY, structures);
        }
    }

    /**
     * Extracts vanilla structure metadata from a serialized chunk root.
     *
     * @param root serialized chunk NBT
     * @return copied structures compound, or {@code null}
     */
    public static NbtCompound extractStructureData(final NbtCompound root) {
        final NbtCompound structures = getCompoundOrNull(root, STRUCTURES_KEY);

        return hasStructureData(structures) ? structures.copy() : null;
    }

    /**
     * Creates a persisted Chunkis chunk metadata envelope.
     *
     * <p>This method defensively copies the supplied structure compound. Use
     * {@link #createChunkMetadataTakingOwnership(NbtCompound, boolean)} on hot
     * paths that already own an isolated compound.</p>
     *
     * @param structureData               serialized vanilla structure metadata, may be {@code null}
     * @param suppressInitialRepopulation whether restored loads should suppress
     *                                    replay-time repopulation
     * @return metadata envelope
     */
    public static NbtCompound createChunkMetadata(
            final NbtCompound structureData,
            final boolean suppressInitialRepopulation
    ) {
        final NbtCompound ownedStructures =
                structureData != null && !structureData.isEmpty()
                        ? structureData.copy()
                        : null;

        return createChunkMetadataTakingOwnership(
                ownedStructures,
                suppressInitialRepopulation
        );
    }

    /**
     * Creates a persisted Chunkis metadata envelope and takes ownership of the
     * supplied structure compound.
     *
     * @param structureData               structure metadata owned by the caller, may be {@code null}
     * @param suppressInitialRepopulation whether restored loads should suppress
     *                                    replay-time repopulation
     * @return metadata envelope
     */
    public static NbtCompound createChunkMetadataTakingOwnership(
            final NbtCompound structureData,
            final boolean suppressInitialRepopulation
    ) {
        return createChunkMetadataTakingOwnership(
                structureData,
                suppressInitialRepopulation,
                false
        );
    }

    /**
     * Creates a persisted Chunkis metadata envelope and takes ownership of the
     * supplied structure compound.
     *
     * @param structureData               structure metadata owned by the caller, may be {@code null}
     * @param suppressInitialRepopulation whether restored loads should suppress
     *                                    replay-time repopulation
     * @param fullBlockBaseline           whether the block payload contains a complete
     *                                    generated baseline, including air
     * @return metadata envelope
     */
    public static NbtCompound createChunkMetadataTakingOwnership(
            final NbtCompound structureData,
            final boolean suppressInitialRepopulation,
            final boolean fullBlockBaseline
    ) {
        return createChunkMetadataTakingOwnership(
                structureData,
                suppressInitialRepopulation,
                fullBlockBaseline,
                null
        );
    }

    /**
     * Creates a persisted Chunkis metadata envelope and takes ownership of supplied
     * child compounds.
     *
     * <p>The caller must not mutate {@code structureData} or {@code baseChunkNbt}
     * after passing them here unless it intentionally wants to mutate the metadata
     * envelope too.</p>
     *
     * @param structureData               structure metadata owned by caller, may be {@code null}
     * @param suppressInitialRepopulation replay suppression flag
     * @param fullBlockBaseline           full generated block baseline flag
     * @param baseChunkNbt                vanilla-compatible base chunk NBT owned by caller,
     *                                    may be {@code null}
     * @return metadata envelope
     */
    public static NbtCompound createChunkMetadataTakingOwnership(
            final NbtCompound structureData,
            final boolean suppressInitialRepopulation,
            final boolean fullBlockBaseline,
            final NbtCompound baseChunkNbt
    ) {
        return createChunkMetadataTakingOwnership(
                structureData,
                suppressInitialRepopulation,
                fullBlockBaseline,
                baseChunkNbt,
                false
        );
    }

    /**
     * Creates a persisted Chunkis metadata envelope and takes ownership of supplied
     * child compounds.
     *
     * @param structureData               structure metadata owned by caller, may be {@code null}
     * @param suppressInitialRepopulation replay suppression flag
     * @param fullBlockBaseline           full generated block baseline flag
     * @param baseChunkNbt                vanilla-compatible base chunk NBT owned by caller,
     *                                    may be {@code null}
     * @param portalChunk                 whether the chunk currently contains portal blocks
     * @return metadata envelope
     */
    public static NbtCompound createChunkMetadataTakingOwnership(
            final NbtCompound structureData,
            final boolean suppressInitialRepopulation,
            final boolean fullBlockBaseline,
            final NbtCompound baseChunkNbt,
            final boolean portalChunk
    ) {
        final NbtCompound metadata = new NbtCompound();

        if (structureData != null && !structureData.isEmpty()) {
            metadata.put(STRUCTURES_KEY, structureData);
        }

        if (baseChunkNbt != null && !baseChunkNbt.isEmpty()) {
            metadata.put(BASE_CHUNK_NBT_KEY, baseChunkNbt);
        }

        metadata.put(
                CHUNKIS_METADATA_KEY,
                createChunkisMetadata(
                        suppressInitialRepopulation,
                        fullBlockBaseline,
                        portalChunk
                )
        );

        return metadata;
    }

    /**
     * Creates the nested Chunkis-owned metadata compound.
     *
     * @param suppressInitialRepopulation replay suppression flag
     * @param fullBlockBaseline           full generated block baseline flag
     * @return Chunkis metadata compound
     */
    private static NbtCompound createChunkisMetadata(
            final boolean suppressInitialRepopulation,
            final boolean fullBlockBaseline,
            final boolean portalChunk
    ) {
        final NbtCompound chunkisMetadata = new NbtCompound();

        chunkisMetadata.putBoolean(
                SUPPRESS_INITIAL_REPOPULATION_KEY,
                suppressInitialRepopulation
        );

        chunkisMetadata.putBoolean(
                FULL_BLOCK_BASELINE_KEY,
                fullBlockBaseline
        );

        chunkisMetadata.putBoolean(
                PORTAL_CHUNK_KEY,
                portalChunk
        );

        return chunkisMetadata;
    }

    /**
     * Serializes one raw metadata payload using CIS' length-prefixed framing.
     *
     * <p>The returned byte array contains:</p>
     * <ol>
     *   <li>a four-byte big-endian payload length</li>
     *   <li>the raw NBT compound payload</li>
     * </ol>
     *
     * <p>This avoids the old double-buffer path. It writes a placeholder length,
     * serializes the payload once, patches the first four bytes, then returns one
     * compact byte array.</p>
     *
     * @param metadata metadata payload to serialize
     * @return encoded length-prefixed payload
     * @throws IOException if NBT serialization fails
     */
    public static byte[] serializeRawPayload(final NbtCompound metadata) throws IOException {
        Objects.requireNonNull(metadata, "metadata");

        final LengthPrefixedByteArrayOutputStream buffer =
                new LengthPrefixedByteArrayOutputStream(RAW_METADATA_INITIAL_CAPACITY);

        try (DataOutputStream output = new DataOutputStream(buffer)) {
            output.writeInt(0);
            NbtIo.writeCompound(metadata, output);
            output.flush();
        }

        buffer.patchLengthPrefix();
        return buffer.toSizedByteArray();
    }

    /**
     * Returns copied vanilla structure metadata from a persisted metadata payload.
     *
     * <p>Supports both formats:</p>
     * <ul>
     *   <li>new envelope format: metadata contains {@value #STRUCTURES_KEY}</li>
     *   <li>legacy v9 format: metadata itself is the vanilla structures compound</li>
     * </ul>
     *
     * @param chunkMetadata metadata stored in the delta
     * @return copied structure metadata, or {@code null}
     */
    public static NbtCompound extractPersistedStructureMetadata(
            final NbtCompound chunkMetadata
    ) {
        if (chunkMetadata == null || chunkMetadata.isEmpty()) {
            return null;
        }

        final NbtCompound envelopedStructures =
                getCompoundOrNull(chunkMetadata, STRUCTURES_KEY);

        if (envelopedStructures != null) {
            return hasStructureData(envelopedStructures)
                    ? envelopedStructures.copy()
                    : null;
        }

        return hasStructureData(chunkMetadata) ? chunkMetadata.copy() : null;
    }

    /**
     * Returns whether a metadata payload contains persisted vanilla structure data.
     *
     * <p>Used by hot save paths to avoid rebuilding metadata envelopes when there
     * is no structure payload to preserve.</p>
     *
     * @param chunkMetadata metadata stored in the delta
     * @return {@code true} if structure metadata exists
     */
    public static boolean hasPersistedStructureMetadata(final NbtCompound chunkMetadata) {
        if (chunkMetadata == null || chunkMetadata.isEmpty()) {
            return false;
        }

        final NbtCompound envelopedStructures =
                getCompoundOrNull(chunkMetadata, STRUCTURES_KEY);

        return envelopedStructures != null
                ? hasStructureData(envelopedStructures)
                : hasStructureData(chunkMetadata);
    }

    /**
     * Returns whether metadata says the CIS block payload contains a complete
     * generated block baseline.
     *
     * @param chunkMetadata metadata object, usually an {@link NbtCompound}
     * @return {@code true} only when the explicit flag exists and is true
     */
    public static boolean hasFullBlockBaseline(final Object chunkMetadata) {
        if (!(chunkMetadata instanceof NbtCompound metadata)) {
            return false;
        }

        final Boolean explicit = readFullBlockBaselineFlag(metadata);
        return explicit != null && explicit;
    }

    /**
     * Returns a copied vanilla-compatible base chunk NBT payload.
     *
     * @param chunkMetadata metadata object, usually an {@link NbtCompound}
     * @return copied base chunk NBT, or {@code null}
     */
    public static NbtCompound extractPersistedBaseChunkNbt(final Object chunkMetadata) {
        if (!(chunkMetadata instanceof NbtCompound metadata) || metadata.isEmpty()) {
            return null;
        }

        final NbtCompound baseChunkNbt =
                getCompoundOrNull(metadata, BASE_CHUNK_NBT_KEY);

        return baseChunkNbt != null && !baseChunkNbt.isEmpty()
                ? baseChunkNbt.copy()
                : null;
    }

    /**
     * Returns whether metadata contains a serialized base chunk payload.
     *
     * <p>This intentionally does not call {@link #extractPersistedBaseChunkNbt}
     * because that method performs a defensive deep copy.</p>
     *
     * @param chunkMetadata metadata object, usually an {@link NbtCompound}
     * @return {@code true} if a non-empty base chunk payload exists
     */
    public static boolean hasPersistedBaseChunkNbt(final Object chunkMetadata) {
        if (!(chunkMetadata instanceof NbtCompound metadata) || metadata.isEmpty()) {
            return false;
        }

        final NbtCompound baseChunkNbt =
                getCompoundOrNull(metadata, BASE_CHUNK_NBT_KEY);

        return baseChunkNbt != null && !baseChunkNbt.isEmpty();
    }

    /**
     * Returns whether persisted base chunk NBT should be used as the block
     * deserialization baseline for this delta.
     *
     * <p>V11 authoritative full-baseline snapshots own block truth in the CIS
     * payload. For those deltas, persisted base chunk NBT may still exist for
     * metadata/bootstrap compatibility but must not supply blocks that can
     * resurrect stale terrain.</p>
     *
     * @param chunkMetadata metadata object, usually an {@link NbtCompound}
     * @return {@code true} only when a persisted base exists and no full
     * authoritative block baseline is present
     */
    public static boolean shouldUsePersistedBaseChunkForBlockBaseline(final Object chunkMetadata) {
        return hasPersistedBaseChunkNbt(chunkMetadata)
                && !hasFullBlockBaseline(chunkMetadata);
    }

    /**
     * Returns whether metadata says the chunk currently contains portal blocks.
     *
     * @param chunkMetadata metadata object, usually an {@link NbtCompound}
     * @return {@code true} only when the explicit flag exists and is true
     */
    public static boolean hasPersistedPortalChunk(final Object chunkMetadata) {
        if (!(chunkMetadata instanceof NbtCompound metadata)) {
            return false;
        }

        final Boolean explicit = readPortalChunkFlag(metadata);
        return explicit != null && explicit;
    }

    /**
     * Returns whether restored loads should suppress initial repopulation work.
     *
     * <p>Explicit persisted metadata wins. Legacy chunks with a non-empty delta
     * but no explicit flag fall back to the synthetic root marker.</p>
     *
     * @param root  synthetic chunk NBT
     * @param delta loaded delta, may be {@code null}
     * @return {@code true} if initial repopulation should be suppressed
     */
    public static boolean shouldSuppressInitialRepopulation(
            final NbtCompound root,
            final ChunkDelta<?, ?> delta
    ) {
        if (delta == null || delta.isEmpty()) {
            return false;
        }

        final Boolean explicit = readSuppressInitialRepopulationFlag(delta);

        if (explicit != null) {
            return explicit;
        }

        return hasChunkisDeltaMarker(root);
    }

    /**
     * Returns whether a loaded non-empty Chunkis delta should suppress initial
     * repopulation work.
     *
     * <p>Explicit persisted metadata wins. Legacy non-empty deltas default to
     * suppression enabled because older Chunkis worlds reached the same behavior
     * through the synthetic delta marker path.</p>
     *
     * @param delta loaded delta, may be {@code null}
     * @return {@code true} if initial repopulation should be suppressed
     */
    public static boolean shouldSuppressInitialRepopulation(final ChunkDelta<?, ?> delta) {
        if (delta == null || delta.isEmpty()) {
            return false;
        }

        final Boolean explicit = readSuppressInitialRepopulationFlag(delta);
        return explicit != null ? explicit : true;
    }

    /**
     * Returns whether a chunk root came through the synthetic Chunkis load path.
     *
     * @param root serialized chunk NBT root
     * @return {@code true} when the synthetic Chunkis marker is present
     */
    public static boolean hasSyntheticChunkisLoadMarker(final NbtCompound root) {
        return root != null && hasChunkisDeltaMarker(root);
    }

    /**
     * Reads the explicit suppression flag from a delta's metadata.
     *
     * @param delta delta to inspect
     * @return explicit flag, or {@code null}
     */
    private static Boolean readSuppressInitialRepopulationFlag(final ChunkDelta<?, ?> delta) {
        final Object rawMetadata = delta.getChunkMetadata();

        return rawMetadata instanceof NbtCompound metadata
                ? readSuppressInitialRepopulationFlag(metadata)
                : null;
    }

    /**
     * Returns whether synthetic chunk NBT contains the Chunkis delta marker.
     *
     * @param root synthetic chunk NBT
     * @return {@code true} if the marker exists and is true
     */
    private static boolean hasChunkisDeltaMarker(final NbtCompound root) {
        final NbtCompound chunkisData = getCompoundOrNull(root, CHUNKIS_DATA_KEY);

        return chunkisData != null
                && chunkisData.getBoolean(HAS_DELTA_KEY).orElse(false);
    }

    /**
     * Records transient load-path baseline usage inside the synthetic Chunkis marker.
     *
     * @param root            chunk root being sent through vanilla deserialization
     * @param baseChunkUsage  whether persisted base chunk NBT was used
     */
    private static void putLoadBaseChunkUsage(
            final NbtCompound root,
            final PersistedBaseChunkUsage baseChunkUsage
    ) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(baseChunkUsage, "baseChunkUsage");

        final NbtCompound chunkisData = getOrCreateCompound(root, CHUNKIS_DATA_KEY);
        chunkisData.putString(LOAD_BASE_CHUNK_USAGE_KEY, baseChunkUsage.name());
    }

    /**
     * Returns whether a structures compound contains starts or references.
     *
     * @param structures vanilla structures compound
     * @return {@code true} if structure starts or references exist
     */
    public static boolean hasStructureData(final NbtCompound structures) {
        return structures != null
                && !structures.isEmpty()
                && (
                getCompoundOrNull(structures, STRUCTURE_STARTS_KEY) != null
                        || getCompoundOrNull(structures, STRUCTURE_REFERENCES_KEY) != null
        );
    }

    /**
     * Ensures an entity NBT compound contains the required {@code "id"} field.
     *
     * <p>Minecraft's entity deserializer requires the registry ID string, for
     * example {@code "minecraft:pig"}. Some serialization paths omit it, so this
     * inserts the ID from the live entity type when missing.</p>
     *
     * @param nbt    entity NBT compound
     * @param entity source entity
     */
    public static void ensureEntityIdPresent(
            final NbtCompound nbt,
            final Entity entity
    ) {
        Objects.requireNonNull(nbt, "nbt");
        Objects.requireNonNull(entity, "entity");

        if (!nbt.contains(ENTITY_ID_KEY)) {
            nbt.putString(ENTITY_ID_KEY, resolveEntityId(entity));
        }
    }

    /**
     * Resolves the registry ID string for an entity type.
     *
     * @param entity entity whose type should be resolved
     * @return registry ID string
     */
    private static String resolveEntityId(final Entity entity) {
        return Registries.ENTITY_TYPE.getId(entity.getType()).toString();
    }

    /**
     * Reads the explicit persisted suppression flag.
     *
     * @param chunkMetadata persisted metadata payload
     * @return explicit flag, or {@code null}
     */
    private static Boolean readSuppressInitialRepopulationFlag(
            final NbtCompound chunkMetadata
    ) {
        return readChunkisBooleanFlag(
                chunkMetadata,
                SUPPRESS_INITIAL_REPOPULATION_KEY
        );
    }

    /**
     * Reads the explicit persisted full-baseline flag.
     *
     * @param chunkMetadata persisted metadata payload
     * @return explicit flag, or {@code null}
     */
    private static Boolean readFullBlockBaselineFlag(final NbtCompound chunkMetadata) {
        return readChunkisBooleanFlag(
                chunkMetadata,
                FULL_BLOCK_BASELINE_KEY
        );
    }

    /**
     * Reads the explicit persisted portal-chunk flag.
     *
     * @param chunkMetadata persisted metadata payload
     * @return explicit flag, or {@code null}
     */
    private static Boolean readPortalChunkFlag(final NbtCompound chunkMetadata) {
        return readChunkisBooleanFlag(
                chunkMetadata,
                PORTAL_CHUNK_KEY
        );
    }

    /**
     * Reads a boolean flag from the nested Chunkis metadata envelope.
     *
     * @param chunkMetadata persisted metadata payload
     * @param key           flag key
     * @return explicit boolean value, or {@code null}
     */
    private static Boolean readChunkisBooleanFlag(
            final NbtCompound chunkMetadata,
            final String key
    ) {
        final NbtCompound chunkisMetadata =
                getCompoundOrNull(chunkMetadata, CHUNKIS_METADATA_KEY);

        if (chunkisMetadata == null || !chunkisMetadata.contains(key)) {
            return null;
        }

        return chunkisMetadata.getBoolean(key).orElse(null);
    }

    /**
     * Returns a nested compound, or {@code null} when the parent/key is absent.
     *
     * <p>This centralizes Minecraft's optional-returning NBT API and keeps callers
     * from repeatedly spelling {@code getCompound(...).orElse(null)}.</p>
     *
     * @param parent parent compound, may be {@code null}
     * @param key    nested compound key
     * @return nested compound, or {@code null}
     */
    private static NbtCompound getCompoundOrNull(
            final NbtCompound parent,
            final String key
    ) {
        if (parent == null || !parent.contains(key)) {
            return null;
        }

        return parent.getCompound(key).orElse(null);
    }

    /**
     * Returns an existing nested compound or creates and installs a new one.
     *
     * @param parent parent compound
     * @param key    nested compound key
     * @return mutable nested compound
     */
    private static NbtCompound getOrCreateCompound(
            final NbtCompound parent,
            final String key
    ) {
        final NbtCompound existing = getCompoundOrNull(parent, key);
        if (existing != null) {
            return existing;
        }

        final NbtCompound created = new NbtCompound();
        parent.put(key, created);
        return created;
    }

    @SuppressWarnings("unchecked")
    private static ChunkDelta<BlockState, NbtCompound> castDelta(
            final ChunkDelta<?, NbtCompound> delta
    ) {
        return (ChunkDelta<BlockState, NbtCompound>) delta;
    }

    public enum PersistedBaseChunkUsage {
        USED,
        SKIPPED,
        MISSING
    }

    public record LoadChunkNbtResult(
            NbtCompound root,
            PersistedBaseChunkUsage baseChunkUsage
    ) {
    }

    /**
     * Byte-array output stream whose first four bytes can be patched with the
     * payload length after serialization.
     *
     * <p>This avoids serializing metadata into a second temporary payload buffer
     * just to discover its size.</p>
     */
    private static final class LengthPrefixedByteArrayOutputStream
            extends ByteArrayOutputStream {

        /**
         * @param size initial capacity
         */
        private LengthPrefixedByteArrayOutputStream(final int size) {
            super(size);
        }

        /**
         * Patches the first four bytes with the payload length.
         *
         * <p>The payload begins immediately after the four-byte length prefix.</p>
         */
        private void patchLengthPrefix() {
            final int payloadLength = count - Integer.BYTES;

            buf[0] = (byte) (payloadLength >>> 24);
            buf[1] = (byte) (payloadLength >>> 16);
            buf[2] = (byte) (payloadLength >>> 8);
            buf[3] = (byte) payloadLength;
        }

        /**
         * Returns a compact byte array containing only written bytes.
         *
         * @return compact serialized byte array
         */
        private byte[] toSizedByteArray() {
            return count == buf.length ? buf : Arrays.copyOf(buf, count);
        }
    }
}

