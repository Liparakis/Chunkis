package io.liparakis.chunkis.world.restoration.nbt;

import io.liparakis.chunkis.core.ChunkDelta;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.registry.Registries;
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
     * Chunkis metadata key for an opaque raw-serialized base chunk payload.
     *
     * <p>This stores the same logical base chunk as {@link #BASE_CHUNK_NBT_KEY}
     * without forcing CIS metadata decode to recursively parse a second full chunk
     * tree on every load. Legacy worlds may still carry the compound form.</p>
     */
    public static final String BASE_CHUNK_PAYLOAD_KEY = "base_chunk_payload";

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

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private CisNbtUtil() {
        throw new AssertionError("Utility class");
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
     * Serializes one raw NBT compound without CIS' outer length prefix.
     *
     * <p>Used for nested payloads that Chunkis wants to keep opaque inside larger
     * metadata structures, such as persisted base chunks.</p>
     *
     * @param payload target compound
     * @return raw serialized compound bytes
     * @throws IOException if serialization fails
     */
    static byte[] serializeRawCompound(final NbtCompound payload) throws IOException {
        Objects.requireNonNull(payload, "payload");

        try (java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(4096);
                java.io.DataOutputStream output = new java.io.DataOutputStream(buffer)) {
            NbtIo.writeCompound(payload, output);
            output.flush();
            return buffer.toByteArray();
        }
    }

    /**
     * Parses one raw serialized NBT compound without a length prefix.
     *
     * @param payload raw compound bytes
     * @return parsed compound
     * @throws IOException if parsing fails
     */
    static NbtCompound deserializeRawCompound(final byte[] payload) throws IOException {
        Objects.requireNonNull(payload, "payload");

        try (java.io.ByteArrayInputStream input = new java.io.ByteArrayInputStream(payload);
                java.io.DataInputStream dataInput = new java.io.DataInputStream(input)) {
            return NbtIo.readCompound(dataInput);
        }
    }

    /**
     * Serializes chunk metadata for CIS persistence, packing any embedded base
     * chunk compound into the opaque byte-array form used by newer saves.
     *
     * <p>This keeps the server-thread mutation path on the simple in-memory
     * compound shape while still writing the cheaper-to-load on-disk shape.</p>
     *
     * @param metadata in-memory metadata envelope
     * @return encoded CIS metadata payload including length prefix
     * @throws IOException if serialization fails
     */
    public static byte[] serializeChunkMetadataForStorage(final NbtCompound metadata) throws IOException {
        return serializeRawPayload(packPersistedBaseChunkPayload(metadata));
    }

    /**
     * Returns metadata ready for persistence, rewriting only the persisted base
     * chunk child from compound form to opaque bytes when needed.
     *
     * @param metadata in-memory metadata envelope
     * @return original metadata when no rewrite is needed, otherwise a packed copy
     * @throws IOException if base chunk serialization fails
     */
    static NbtCompound packPersistedBaseChunkPayload(final NbtCompound metadata) throws IOException {
        Objects.requireNonNull(metadata, "metadata");

        if (metadata.getByteArray(BASE_CHUNK_PAYLOAD_KEY).isPresent()) {
            return metadata;
        }

        final NbtCompound baseChunkNbt = getCompoundOrNull(metadata, BASE_CHUNK_NBT_KEY);
        if (baseChunkNbt == null || baseChunkNbt.isEmpty()) {
            return metadata;
        }

        final NbtCompound packed = metadata.copy();
        packed.remove(BASE_CHUNK_NBT_KEY);
        packed.putByteArray(BASE_CHUNK_PAYLOAD_KEY, serializeRawCompound(baseChunkNbt));
        return packed;
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
        return Registries.ENTITY_TYPE.getId(entity.getType())
                .toString();
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
         * Constructor.
         *
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
