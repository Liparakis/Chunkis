package io.liparakis.chunkis.world.restoration.nbt;

import io.liparakis.chunkis.core.ChunkDelta;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.ChunkPos;

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
     * Vanilla root key for serialized heightmaps.
     */
    public static final String HEIGHTMAPS_KEY = "Heightmaps";

    /**
     * Vanilla root key recording whether light data was stored for the chunk.
     */
    public static final String IS_LIGHT_ON_KEY = "isLightOn";

    /**
     * Vanilla root key for serialized section payloads.
     */
    public static final String SECTIONS_KEY = "sections";

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
     * Chunkis metadata flag identifying an offline-translated authoritative MCA
     * snapshot.
     */
    public static final String MIGRATED_AUTHORITATIVE_CHUNK_KEY = "migrated_authoritative_chunk";
    /**
     * Chunkis metadata key holding preserved auxiliary vanilla chunk NBT that is
     * not modeled explicitly by Chunkis.
     */
    public static final String PRESERVED_AUXILIARY_CHUNK_NBT_KEY = "preserved_auxiliary_chunk_nbt";
    /**
     * Chunkis metadata key for a vanilla-compatible serialized base chunk.
     */
    public static final String BASE_CHUNK_NBT_KEY = "base_chunk_nbt";
    /**
     * Chunkis metadata key for an opaque raw-serialized base chunk payload.
     *
     * <p>This stores the same logical base chunk as {@link #BASE_CHUNK_NBT_KEY}
     * without forcing CIS metadata decode to recursively parse a second full chunk
     * tree on every load. Legacy worlds may still carry the compound form.</p>
     */
    public static final String BASE_CHUNK_PAYLOAD_KEY = "base_chunk_payload";

    /**
     * Per-section biomes and light stored with a full block baseline ({@link StoredSections}), so the chunk
     * can be loaded as a finished chunk without regenerating terrain. Absent on older entries, which load
     * through terrain generation as before.
     */
    public static final String STORED_SECTIONS_KEY = "stored_sections";

    /**
     * Whether {@link #STORED_SECTIONS_KEY} holds the chunk's complete light. Always written next to the
     * sections, so a later unlit capture never inherits an earlier capture's flag.
     */
    public static final String STORED_LIGHT_KEY = "stored_light";

    /**
     * Metadata key for a stored chunk's pending block and fluid ticks; see {@link PendingTicksNbt}.
     */
    public static final String PENDING_TICKS_KEY = "pending_ticks";

    /**
     * Metadata key for a stored chunk's heightmaps, in vanilla's {@code Heightmaps} form, captured with its blocks.
     */
    public static final String STORED_HEIGHTMAPS_KEY = "stored_heightmaps";
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
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private CisNbtUtil() {
        throw new AssertionError("Utility class");
    }

    /**
     * Extracts compound entries from a named list if present.
     *
     * @param root source NBT root
     * @param key  list key to inspect
     * @return copied view of compound entries, or an empty list
     */
    public static List<NbtCompound> extractCompoundList(final NbtCompound root, final String key) {
        if (root == null || key == null || key.isBlank()) {
            return List.of();
        }
        final NbtList entries = root.getList(key)
                .orElse(null);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        final List<NbtCompound> compounds = new ArrayList<>(entries.size());
        entries.forEach(element -> {
            if (element instanceof NbtCompound compound) {
                compounds.add(compound);
            }
        });
        return compounds;
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
        return ChunkLoadNbtBuilder.buildLoadChunkNbt(chunkX, chunkZ, dataVersion, delta);
    }

    /**
     * Extracts vanilla structure metadata from a serialized chunk root.
     *
     * @param root serialized chunk NBT
     * @return copied structures compound, or {@code null}
     */
    public static NbtCompound extractStructureData(final NbtCompound root) {
        return StructureMetadataNbt.extractStructureData(root);
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
        return ChunkMetadataEnvelope.create(
                structureData,
                suppressInitialRepopulation,
                fullBlockBaseline,
                baseChunkNbt,
                portalChunk
        );
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
        return StructureMetadataNbt.extractPersistedStructureMetadata(chunkMetadata);
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
        return PersistedBaseChunkAccess.extractCopiedBaseChunkNbt(chunkMetadata);
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
        return PersistedBaseChunkAccess.hasPersistedBaseChunkNbt(chunkMetadata);
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
     *         authoritative block baseline is present
     */
    public static boolean shouldUsePersistedBaseChunkForBlockBaseline(final Object chunkMetadata) {
        return PersistedBaseChunkAccess.shouldUsePersistedBaseChunkForBlockBaseline(chunkMetadata);
    }

    /**
     * Reports whether rebuilding the metadata envelope from {@code structureData} would reproduce
     * {@code existingMetadata}: same structures compound, repopulation already suppressed, and the same
     * portal flag. The base chunk, full-baseline flag and carried keys are copied over unchanged by a
     * rebuild, so they cannot differ.
     *
     * @param existingMetadata current enveloped metadata, possibly {@code null}
     * @param structureData    freshly extracted structures compound
     * @param portalChunk      whether the live chunk contains portal blocks
     * @return true when no rebuild is needed
     */
    public static boolean isStructureMetadataCurrent(final NbtCompound existingMetadata,
            final NbtCompound structureData, final boolean portalChunk) {
        final NbtCompound existingStructures = getCompoundOrNull(existingMetadata, STRUCTURES_KEY);
        final Boolean suppressed = existingMetadata == null ? null : readSuppressInitialRepopulationFlag(existingMetadata);
        final Boolean portal = existingMetadata == null ? null : readPortalChunkFlag(existingMetadata);
        return existingStructures != null
                && Boolean.TRUE.equals(suppressed)
                && portal != null && portal == portalChunk
                && existingStructures.equals(structureData);
    }

    /**
     * Copies a delta payload for an async-save snapshot, sharing its stored sections instead of copying them.
     *
     * <p>A capture always puts a new {@link #STORED_SECTIONS_KEY} list into new metadata and nothing modifies the
     * list afterwards ({@link #getStoredSections} hands out copies), so the snapshot can share it; it holds a
     * chunk's light, the largest part of the metadata.</p>
     *
     * @param payload block-entity, entity or metadata payload
     * @return a copy that may share the stored sections list
     */
    public static NbtCompound copySharingStoredSections(final NbtCompound payload) {
        final NbtElement storedSections = payload.get(STORED_SECTIONS_KEY);
        if (storedSections == null) {
            return payload.copy();
        }
        final NbtCompound copy = new NbtCompound();
        for (final String key : payload.getKeys()) {
            final NbtElement element = payload.get(key);
            copy.put(key, element == storedSections ? element : element.copy());
        }
        return copy;
    }

    /**
     * Returns the stored per-section biomes and light, if this entry has them.
     *
     * @param chunkMetadata metadata object, usually an {@link NbtCompound}
     * @return a copy of the vanilla-format section list, or {@code null} when absent
     */
    public static NbtList getStoredSections(final Object chunkMetadata) {
        if (!(chunkMetadata instanceof NbtCompound metadata)) {
            return null;
        }
        return metadata.getList(STORED_SECTIONS_KEY)
                .filter(list -> !list.isEmpty())
                .map(NbtList::copy)
                .orElse(null);
    }

    /**
     * Returns whether this entry loads as a finished chunk: its blocks come from the full block baseline and
     * its biomes from the stored sections, so no terrain is generated for it. A full baseline also means no
     * persisted base chunk supplies blocks ({@link #shouldUsePersistedBaseChunkForBlockBaseline}).
     *
     * @param chunkMetadata metadata object, usually an {@link NbtCompound}
     * @return true when the entry loads without terrain generation
     */
    public static boolean loadsAsFinishedChunk(final Object chunkMetadata) {
        return hasFullBlockBaseline(chunkMetadata)
                && chunkMetadata instanceof NbtCompound metadata
                && !metadata.getListOrEmpty(STORED_SECTIONS_KEY).isEmpty();
    }

    /**
     * Returns whether this entry loads as a finished chunk whose light is stored too, so its light already
     * matches the restored blocks and nothing needs relighting.
     *
     * @param chunkMetadata metadata object, usually an {@link NbtCompound}
     * @return true when the entry loads lit
     */
    public static boolean loadsWithStoredLight(final Object chunkMetadata) {
        return loadsAsFinishedChunk(chunkMetadata)
                && ((NbtCompound) chunkMetadata).getBoolean(STORED_LIGHT_KEY, false);
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
     * Marks metadata as an authoritative offline-translated chunk snapshot.
     *
     * @param chunkMetadata metadata envelope to mutate
     */
    public static void markMigratedAuthoritativeChunk(final NbtCompound chunkMetadata) {
        Objects.requireNonNull(chunkMetadata, "chunkMetadata");

        final NbtCompound chunkisMetadata = getOrCreateChunkisMetadata(chunkMetadata);
        chunkisMetadata.putBoolean(MIGRATED_AUTHORITATIVE_CHUNK_KEY, true);
    }

    /**
     * Returns whether metadata is marked as an authoritative offline-translated
     * chunk snapshot.
     *
     * @param chunkMetadata metadata object, usually an {@link NbtCompound}
     * @return {@code true} only when the explicit marker exists and is true
     */
    public static boolean isMigratedAuthoritativeChunk(final Object chunkMetadata) {
        if (!(chunkMetadata instanceof NbtCompound metadata)) {
            return false;
        }

        final NbtCompound chunkisMetadata = getCompoundOrNull(metadata, CHUNKIS_METADATA_KEY);
        return chunkisMetadata != null
                && chunkisMetadata.getBoolean(MIGRATED_AUTHORITATIVE_CHUNK_KEY)
                .orElse(false);
    }

    /**
     * Stores preserved auxiliary vanilla chunk metadata in the persisted Chunkis
     * envelope.
     *
     * @param chunkMetadata metadata envelope to mutate
     * @param auxiliaryNbt  opaque auxiliary payload, may be {@code null}
     */
    public static void putPreservedAuxiliaryChunkNbt(
            final NbtCompound chunkMetadata,
            final NbtCompound auxiliaryNbt
    ) {
        Objects.requireNonNull(chunkMetadata, "chunkMetadata");

        if (auxiliaryNbt == null || auxiliaryNbt.isEmpty()) {
            chunkMetadata.remove(PRESERVED_AUXILIARY_CHUNK_NBT_KEY);
            return;
        }

        chunkMetadata.put(PRESERVED_AUXILIARY_CHUNK_NBT_KEY, auxiliaryNbt);
    }

    /**
     * Carries every key a freshly rebuilt metadata envelope does not own over from
     * the previous envelope.
     *
     * <p>Save-time recapture rebuilds the envelope from runtime state, which only
     * knows the structures, base chunk and the three {@code chunkis} flags. Everything
     * else (the migrated-authoritative marker, preserved auxiliary vanilla NBT, keys
     * written by a newer build or by future features) is copied from {@code
     * sourceMetadata} so a recapture never discards it. The envelope-owned root keys
     * are left as rebuilt, because their absence there is an intentional removal.</p>
     *
     * @param sourceMetadata metadata to read from, may be {@code null}
     * @param targetMetadata freshly rebuilt metadata to mutate
     */
    public static void preserveCarriedMetadata(
            final NbtCompound sourceMetadata,
            final NbtCompound targetMetadata
    ) {
        Objects.requireNonNull(targetMetadata, "targetMetadata");

        if (sourceMetadata == null) {
            return;
        }

        for (final String key : sourceMetadata.getKeys()) {
            // The base chunk, in either form, is only ever re-added on purpose; carrying it would keep a full
            // vanilla copy of the chunk inside entries that no longer need it.
            // Pending ticks are re-captured with every snapshot; carrying them would replay ticks that already ran.
            if (STRUCTURES_KEY.equals(key) || BASE_CHUNK_NBT_KEY.equals(key) || BASE_CHUNK_PAYLOAD_KEY.equals(key)
                    || CHUNKIS_METADATA_KEY.equals(key) || PENDING_TICKS_KEY.equals(key)
                    || STORED_HEIGHTMAPS_KEY.equals(key)
                    || targetMetadata.contains(key)) {
                continue;
            }
            targetMetadata.put(key, sourceMetadata.get(key)
                    .copy());
        }

        final NbtCompound sourceChunkis = sourceMetadata.getCompound(CHUNKIS_METADATA_KEY)
                .orElse(null);
        if (sourceChunkis == null) {
            return;
        }
        final NbtCompound targetChunkis = getOrCreateChunkisMetadata(targetMetadata);
        for (final String key : sourceChunkis.getKeys()) {
            if (!targetChunkis.contains(key)) {
                targetChunkis.put(key, sourceChunkis.get(key)
                        .copy());
            }
        }
    }

    /**
     * Returns a copied preserved auxiliary vanilla chunk payload.
     *
     * @param chunkMetadata metadata object, usually an {@link NbtCompound}
     * @return copied auxiliary payload, or {@code null}
     */
    public static NbtCompound extractPreservedAuxiliaryChunkNbt(final Object chunkMetadata) {
        if (!(chunkMetadata instanceof NbtCompound metadata)) {
            return null;
        }

        final NbtCompound auxiliary = getCompoundOrNull(metadata, PRESERVED_AUXILIARY_CHUNK_NBT_KEY);
        return auxiliary != null ? auxiliary.copy() : null;
    }

    /**
     * Extracts the opaque auxiliary vanilla chunk payload from a raw chunk NBT
     * root by removing data that Chunkis stores explicitly elsewhere.
     *
     * @param sourceRoot raw vanilla chunk root
     * @return copied auxiliary payload, or {@code null} if nothing remains
     */
    public static NbtCompound extractPreservedAuxiliaryChunkNbtFromChunkRoot(final NbtCompound sourceRoot) {
        Objects.requireNonNull(sourceRoot, "sourceRoot");

        final NbtCompound auxiliary = sourceRoot.copy();
        auxiliary.remove(STRUCTURES_KEY);
        auxiliary.remove(HEIGHTMAPS_KEY);
        auxiliary.remove(IS_LIGHT_ON_KEY);
        auxiliary.remove(SECTIONS_KEY);
        auxiliary.remove("block_entities");
        auxiliary.remove("entities");

        return auxiliary.isEmpty() ? null : auxiliary;
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

        return SyntheticChunkisLoadMarker.hasDeltaMarker(root);
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
        return root != null && SyntheticChunkisLoadMarker.hasDeltaMarker(root);
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
     * Returns whether a structures compound contains starts or references.
     *
     * @param structures vanilla structures compound
     * @return {@code true} if structure starts or references exist
     */
    public static boolean hasStructureData(final NbtCompound structures) {
        return StructureMetadataNbt.hasStructureData(structures);
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
        return ChunkisMetadataFlags.readSuppressInitialRepopulationFlag(chunkMetadata);
    }

    /**
     * Reads the explicit persisted full-baseline flag.
     *
     * @param chunkMetadata persisted metadata payload
     * @return explicit flag, or {@code null}
     */
    private static Boolean readFullBlockBaselineFlag(final NbtCompound chunkMetadata) {
        return ChunkisMetadataFlags.readFullBlockBaselineFlag(chunkMetadata);
    }

    /**
     * Reads the explicit persisted portal-chunk flag.
     *
     * @param chunkMetadata persisted metadata payload
     * @return explicit flag, or {@code null}
     */
    private static Boolean readPortalChunkFlag(final NbtCompound chunkMetadata) {
        return ChunkisMetadataFlags.readPortalChunkFlag(chunkMetadata);
    }

    /**
     * Returns the nested Chunkis metadata compound, creating it when missing.
     *
     * @param chunkMetadata parent persisted metadata envelope
     * @return existing or newly inserted nested Chunkis metadata compound
     */
    private static NbtCompound getOrCreateChunkisMetadata(final NbtCompound chunkMetadata) {
        final NbtCompound existing = getCompoundOrNull(chunkMetadata, CHUNKIS_METADATA_KEY);
        if (existing != null) {
            return existing;
        }

        final NbtCompound created = new NbtCompound();
        chunkMetadata.put(CHUNKIS_METADATA_KEY, created);
        return created;
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
    static NbtCompound getCompoundOrNull(
            final NbtCompound parent,
            final String key
    ) {
        return StructureMetadataNbt.getCompoundOrNull(parent, key);
    }

    /**
     * Flag statuses tracking base chunk usage choices during load.
     */
    public enum PersistedBaseChunkUsage {
        /**
         * Persisted base chunk NBT used during loads.
         */
        USED,

        /**
         * Persisted base chunk was skipped.
         */
        SKIPPED,

        /**
         * Persisted base chunk is missing.
         */
        MISSING
    }

    /**
     * Wrapper containing root compounds and usage mappings.
     *
     * @param root           chunk load NBT compound
     * @param baseChunkUsage usage code mapping
     */
    public record LoadChunkNbtResult(
            NbtCompound root,
            PersistedBaseChunkUsage baseChunkUsage
    ) {

    }

}
