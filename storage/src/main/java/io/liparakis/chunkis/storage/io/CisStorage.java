package io.liparakis.chunkis.storage.io;

import io.liparakis.chunkis.core.compression.CompressionContext;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.ChunkDeltaView;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.config.ChunkisDebugLevel;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.model.key.DebugRegionKey;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.core.codec.CisDecoder;
import io.liparakis.chunkis.core.codec.CisEncoder;
import io.liparakis.chunkis.storage.io.region.RegionFile;
import io.liparakis.chunkis.storage.mapping.CisMapping;
import io.liparakis.chunkis.core.model.CisConstants;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Region-based storage system for Chunkis chunk deltas.
 *
 * <ul>
 *   <li>32-32 chunk region files</li>
 *   <li>bounded LRU cache of open region files</li>
 *   <li>thread-local encoder, decoder, and compression state</li>
 *   <li>automatic region lookup by chunk position</li>
 * </ul>
 *
 * <p>The storage format writes compressed CIS-encoded deltas into region files.
 * Empty deltas are treated as deletions and clear the corresponding chunk entry
 * from its region.</p>
 *
 * <p><b>Threading:</b> region cache mutation is protected by a write lock. Cache
 * hits also take the write lock because LRU order updates are mutations.
 * Compression and codec objects are per-thread to avoid contention during
 * concurrent save/load calls. Speculative loads use one bounded worker and
 * retain decoded results briefly. Region cache access and the corresponding
 * file operation share a storage monitor so eviction cannot close an active
 * handle.</p>
 *
 * <p><b>Ownership:</b> callers own the {@link ChunkDelta} instances they pass
 * in, but the storage instance owns its region cache and must be closed exactly
 * once by the layer that created it.</p>
 *
 * @param <B> block type
 * @param <S> block state type
 * @param <P> property type
 * @param <N> NBT type
 */
public final class CisStorage<B, S, P, N> {

    /**
     * Trace source label for normal save requests.
     */
    private static final String SAVE_SOURCE = "CisStorage#save";

    /**
     * Trace source label for prepared-write flushes.
     */
    private static final String WRITE_SOURCE = "CisStorage#writePrepared";

    /**
     * Trace source label for load requests.
     */
    private static final String LOAD_SOURCE = "CisStorage#load";

    /**
     * Minimum valid decompressed CIS payload size (magic + version = 8 bytes).
     * Mirrors the HEADER_SIZE constant in AbstractCisDecoder.
     */
    private static final int MIN_DECOMPRESSED_SIZE = 8;

    /**
     * Root directory where {@code .cis} region files are stored.
     */
    private final Path storageDir;

    /**
     * Global block/state mapping used by this storage instance.
     *
     * <p>The encoder may add entries to this mapping during save, so it is flushed
     * after encoding and before the region payload is written.</p>
     */
    private final CisMapping<B, S, P> mapping;

    /**
     * Opens, caches, evicts, compacts, and closes region files for this storage instance.
     */
    private final RegionFileCache regionFiles;

    /**
     * Per-thread compression state. Compression buffers are reused safely per
     * thread without synchronization.
     */
    private final ThreadLocal<CompressionContext> compressionContext = ThreadLocal.withInitial(CompressionContext::new);

    /**
     * Per-thread CIS encoder to avoid allocator churn on saves.
     */
    private final ThreadLocal<CisEncoder<S, N>> encoder;

    /**
     * Per-thread CIS decoder to avoid allocator churn on loads.
     */
    private final ThreadLocal<CisDecoder<S, N>> decoder;

    /**
     * Bounded decoded-load prefetches keyed by chunk position.
     */
    private final ConcurrentHashMap<CisChunkPos, CompletableFuture<LoadResult<S, N>>> prefetchedLoads =
            new ConcurrentHashMap<>();

    /**
     * Single worker keeps speculative disk work from competing with the server.
     */
    private final ExecutorService prefetchExecutor = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "Chunkis-CisPrefetch");
        thread.setDaemon(true);
        return thread;
    });

    /** Stores prefetch requests. */
    private final LongAdder prefetchRequests = new LongAdder();
    /** Stores prefetch accepted. */
    private final LongAdder prefetchAccepted = new LongAdder();
    /** Stores prefetch hits. */
    private final LongAdder prefetchHits = new LongAdder();
    /** Stores prefetch drops. */
    private final LongAdder prefetchDrops = new LongAdder();
    /** Stores prefetch stored entries. */
    private final LongAdder prefetchStoredEntries = new LongAdder();
    /** Stores prefetch non empty deltas. */
    private final LongAdder prefetchNonEmptyDeltas = new LongAdder();

    /** Stores max prefetches. */
    private static final int MAX_PREFETCHES = 32;
    /** Stores prefetch ttl millis. */
    private static final long PREFETCH_TTL_MILLIS = 2_000L;

    /**
     * Creates a new CIS storage instance.
     *
     * @param storageDir root storage directory
     * @param mapping    global block/state mapping
     * @param nbtAdapter NBT adapter
     * @param airState   canonical air state
     */
    public CisStorage(
            final Path storageDir, final CisMapping<B, S, P> mapping,
            final NbtAdapter<N> nbtAdapter, final S airState) {
        this.storageDir = Objects.requireNonNull(storageDir, "storageDir");
        this.mapping = Objects.requireNonNull(mapping, "mapping");

        // Captured into lambdas - validate once here so the ThreadLocal suppliers
        // never receive null.
        final NbtAdapter<N> safeNbtAdapter = Objects.requireNonNull(nbtAdapter, "nbtAdapter");
        final S safeAirState = Objects.requireNonNull(airState, "airState");

        this.regionFiles = new RegionFileCache(this.storageDir);

        this.encoder = ThreadLocal.withInitial(() -> new CisEncoder<>(
                this.mapping, safeNbtAdapter,
                safeAirState
        ));

        this.decoder = ThreadLocal.withInitial(() -> new CisDecoder<>(
                this.mapping, safeNbtAdapter,
                safeAirState
        ));
    }

    /**
     * Creates a fresh empty delta. Centralised so all missing/corrupt load exits
     * are consistent and easy to audit.
     */
    private static <S, N> ChunkDelta<S, N> newEmptyDelta() {
        return new ChunkDelta<>();
    }

    /**
     * Maps load/decode failures to the closest trace reason for diagnostics.
     */
    private static ChunkTraceReason classifyLoadFailure(final Exception error) {
        final String message = error.getMessage();
        if (message == null) {
            return ChunkTraceReason.DECODE_FAILED;
        }
        if (message.contains("Failed to decompress") || message.contains("Decompressed CIS data too small")) {
            return ChunkTraceReason.DECOMPRESSION_FAILED;
        }
        if (message.contains("Unknown Block ID")) {
            return ChunkTraceReason.MAPPING_LOOKUP_FAILED;
        }
        return ChunkTraceReason.DECODE_FAILED;
    }

    /**
     * Converts a storage position into the trace-model chunk key shape.
     */
    private static DebugChunkKey toChunkKey(final CisChunkPos pos) {
        return new DebugChunkKey(pos.x(), pos.z());
    }

    /**
     * Converts a storage position into the trace-model region key shape.
     */
    private static DebugRegionKey toRegionKey(final CisChunkPos pos) {
        final var regionKey = RegionFileCache.regionKey(pos);
        return new DebugRegionKey(regionKey.x(), regionKey.z());
    }

    /**
     * Saves a chunk delta to CIS storage.
     *
     * <p>If the delta is empty the chunk entry is cleared from its region file
     * instead. This handles the case where all edits were reverted and stale
     * persisted data must be removed.</p>
     *
     * @param pos   chunk position
     * @param delta chunk delta to save
     * @return {@code true} if the save or clear succeeded
     */
    public boolean save(final CisChunkPos pos, final ChunkDelta<S, N> delta) {
        return save(pos, delta, ChunkTraceStore.nextOperationId("save"));
    }

    /**
     * Saves a chunk delta using a caller-supplied operation id for trace correlation.
     *
     * <p>This overload exists for higher-level save pipelines that want one stable
     * identifier across snapshot capture, encoding, compression, and region-file I/O.</p>
     *
     * @param pos         chunk position
     * @param delta       chunk delta to save
     * @param operationId trace correlation id
     * @return {@code true} if the save or clear succeeded
     */
    public boolean save(final CisChunkPos pos, final ChunkDelta<S, N> delta, final String operationId) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(delta, "delta");

        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE, ChunkTraceEventType.SAVE_TX_START,
                ChunkTraceSeverity.INFO, ChunkTraceReason.NONE, SAVE_SOURCE, "save requested", null, toChunkKey(pos),
                toRegionKey(pos), operationId, delta.isDirty(), null
        );

        try {
            final PreparedSave preparedSave = prepareSave(pos, delta);

            if (!writePrepared(pos, preparedSave, operationId)) {
                return false;
            }

            delta.setSourceVersion(CisConstants.VERSION);
            delta.markSaved();
            return true;
        } catch (final IOException e) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.REGION_STORAGE, ChunkTraceEventType.SAVE_FLUSH_FAILED,
                    ChunkTraceSeverity.ERROR, ChunkTraceReason.IO_EXCEPTION, SAVE_SOURCE, "save failed", null,
                    toChunkKey(pos), toRegionKey(pos), operationId, delta.isDirty(), null
            );
            Chunkis.LOGGER.error("Chunkis: Failed to save CIS chunk {}", pos, e);
            return false;
        }
    }

    /**
     * Replaces any existing chunk payload with the given authoritative snapshot.
     *
     * <p>This is intended for offline migration only. It guarantees that the
     * written delta is exactly the supplied one and does not merge with any
     * previous data for the same chunk position.</p>
     *
     * @param pos   chunk position
     * @param delta authoritative full snapshot
     * @return true if replace succeeded
     */
    public boolean replace(final CisChunkPos pos, final ChunkDelta<S, N> delta) {
        return replace(pos, delta, ChunkTraceStore.nextOperationId("replace"));
    }

    /**
     * Replaces any existing chunk payload with the given authoritative snapshot.
     *
     * @param pos         chunk position
     * @param delta       authoritative full snapshot
     * @param operationId trace correlation id
     * @return true if replace succeeded
     */
    public boolean replace(final CisChunkPos pos, final ChunkDelta<S, N> delta, final String operationId) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(delta, "delta");

        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE, ChunkTraceEventType.SAVE_TX_START,
                ChunkTraceSeverity.INFO, ChunkTraceReason.NONE, SAVE_SOURCE,
                "replace requested (authoritative)", null, toChunkKey(pos), toRegionKey(pos),
                operationId, delta.isDirty(), null
        );

        try {
            // First clear any stale entry so the new payload cannot merge with old data
            clearChunk(pos);

            final PreparedSave preparedSave = prepareSave(pos, delta);

            if (!writePrepared(pos, preparedSave, operationId)) {
                return false;
            }

            delta.setSourceVersion(CisConstants.VERSION);
            delta.markSaved();
            return true;
        } catch (final IOException e) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.REGION_STORAGE, ChunkTraceEventType.SAVE_FLUSH_FAILED,
                    ChunkTraceSeverity.ERROR, ChunkTraceReason.IO_EXCEPTION, SAVE_SOURCE,
                    "replace failed", null, toChunkKey(pos), toRegionKey(pos), operationId,
                    delta.isDirty(), null
            );
            Chunkis.LOGGER.error("Chunkis: Failed to replace CIS chunk {}", pos, e);
            return false;
        }
    }

    /**
     * Encodes an immutable save payload for later compression and region write.
     *
     * <p>Intended for callers that want to move compression and file I/O off the
     * server thread while still running mapping-sensitive CIS encoding at call
     * time.</p>
     *
     * @param pos   chunk position
     * @param delta chunk delta to encode
     * @return prepared save payload
     * @throws IOException if encoding or mapping flush fails
     */
    public PreparedSave prepareSave(final CisChunkPos pos, final ChunkDeltaView<S, N> delta) throws IOException {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(delta, "delta");

        if (delta.isEmpty()) {
            return PreparedSave.clear();
        }

        final byte[] rawData = encoder.get()
                .encode(delta);
        mapping.flush();
        return PreparedSave.write(rawData);
    }

    /**
     * Compresses and writes a prepared save payload to its owning region file.
     *
     * <p>This is the second half of the split save pipeline used by async save
     * paths. A prepared clear operation removes the chunk entry instead of
     * writing bytes. A prepared write operation is compressed, written, and
     * optionally read back in paranoid debug mode.</p>
     *
     * @param pos          chunk position
     * @param preparedSave prepared clear or write payload
     * @param operationId  trace correlation id
     * @return {@code true} if the prepared operation completed successfully
     * @throws IOException if compression or region-file I/O fails
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean writePrepared(final CisChunkPos pos, final PreparedSave preparedSave, final String operationId)
            throws IOException {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(preparedSave, "preparedSave");
        prefetchedLoads.remove(pos);

        if (preparedSave.clearChunk()) {
            return clearChunk(pos);
        }

        ChunkTraceStore.trace(
                ChunkisDebugDomain.REGION_STORAGE, ChunkTraceEventType.SAVE_FLUSH_STARTED,
                ChunkTraceSeverity.INFO, ChunkTraceReason.STORAGE_WRITE, WRITE_SOURCE, "flush started", null,
                toChunkKey(pos), toRegionKey(pos), operationId, null, preparedSave.rawData().length
        );

        final byte[] compressedData = compressionContext.get()
                .compress(preparedSave.rawData());
        synchronized (regionFiles) {
            final RegionFile regionFile = getRegionFile(pos, true);

            if (regionFile == null) {
                return false;
            }

            regionFile.write(pos, compressedData, operationId);
            verifyParanoidReadBack(pos, regionFile, compressedData, operationId);
        }
        prefetchedLoads.remove(pos);
        ChunkTraceStore.trace(
                ChunkisDebugDomain.REGION_STORAGE, ChunkTraceEventType.SAVE_FLUSH_COMPLETED,
                ChunkTraceSeverity.INFO, ChunkTraceReason.STORAGE_WRITE, WRITE_SOURCE, "flush completed", null,
                toChunkKey(pos), toRegionKey(pos), operationId, null, compressedData.length
        );
        return true;
    }

    /** Performs verify paranoid read back. */
    private void verifyParanoidReadBack(
            final CisChunkPos pos, final RegionFile regionFile,
            final byte[] expectedBytes, final String operationId) {
        if (ChunkisDebugConfig.level() != ChunkisDebugLevel.PARANOID) {
            return;
        }

        final String verifyOperationId = operationId + "-verify";
        try {
            final byte[] readBack = regionFile.read(pos, verifyOperationId);
            if (!Arrays.equals(expectedBytes, readBack)) {
                ChunkTraceStore.trace(
                        ChunkisDebugDomain.ASSERTIONS, ChunkTraceEventType.ASSERTION_FAILED,
                        ChunkTraceSeverity.ERROR, ChunkTraceReason.INVALID_PAYLOAD, WRITE_SOURCE, readBack == null ?
                                "paranoid read-back missing written entry" :
                                "paranoid read-back bytes mismatched " + "written payload", null, toChunkKey(pos),
                        toRegionKey(pos), operationId, null, expectedBytes.length
                );
            }
        } catch (final IOException e) {
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.ASSERTIONS, ChunkTraceEventType.ASSERTION_FAILED,
                    ChunkTraceSeverity.ERROR, ChunkTraceReason.IO_EXCEPTION, WRITE_SOURCE, "paranoid read-back " +
                            "failed: " + e.getMessage(), null, toChunkKey(pos), toRegionKey(pos), operationId, null,
                    expectedBytes.length
            );
        }
    }

    /**
     * Loads a chunk delta from CIS storage.
     *
     * <p>Missing regions or missing entries return a fresh empty delta. Corrupted
     * entries are cleared from storage and also return an empty delta.</p>
     *
     * @param pos chunk position
     * @return loaded chunk delta, or an empty delta if missing or corrupt
     */
    public ChunkDelta<S, N> load(final CisChunkPos pos) {
        return loadWithPresence(pos, ChunkTraceStore.nextOperationId("load")).delta();
    }

    /**
     * Loads a chunk delta using a caller-supplied operation id for trace correlation.
     *
     * <p>Missing, invalid, or self-healed entries still return an empty delta; the
     * caller-supplied id only affects observability.</p>
     *
     * @param pos         chunk position
     * @param operationId trace correlation id
     * @return loaded chunk delta, or an empty delta if missing or corrupt
     */
    public ChunkDelta<S, N> load(final CisChunkPos pos, final String operationId) {
        return loadWithPresence(pos, operationId).delta();
    }

    /**
     * Starts a bounded speculative load. The normal load path consumes the
     * result if it is ready, otherwise it keeps its existing synchronous
     * behavior.
     *
     * @param pos         chunk position
     * @param operationId trace correlation id for the background load
     */
    public void prefetch(final CisChunkPos pos, final String operationId) {
        Objects.requireNonNull(pos, "pos");
        prefetchRequests.increment();
        if (prefetchedLoads.size() >= MAX_PREFETCHES && !prefetchedLoads.containsKey(pos)) {
            prefetchDrops.increment();
            return;
        }

        prefetchedLoads.computeIfAbsent(pos, key -> {
            prefetchAccepted.increment();
            final CompletableFuture<LoadResult<S, N>> future = CompletableFuture.supplyAsync(
                    () -> loadWithPresenceSync(key, operationId, false),
                    prefetchExecutor
            );
            future.whenComplete((result, error) -> {
                if (error != null) {
                    prefetchedLoads.remove(key, future);
                    return;
                }
                if (result.storageEntryPresent()) {
                    prefetchStoredEntries.increment();
                }
                if (!result.delta().isEmpty()) {
                    prefetchNonEmptyDeltas.increment();
                }
                CompletableFuture.delayedExecutor(PREFETCH_TTL_MILLIS, TimeUnit.MILLISECONDS)
                        .execute(() -> prefetchedLoads.remove(key, future));
            });
            return future;
        });
    }

    /**
     * Loads a chunk delta and reports whether a storage entry existed before decoding.
     *
     * <p>The existence check and read share the same cached region file lookup, avoiding
     * a second storage probe for callers that need decode observability.</p>
     *
     * @param pos         chunk position
     * @param operationId trace correlation id
     * @return decoded delta and pre-decode storage presence
     */
    public LoadResult<S, N> loadWithPresence(final CisChunkPos pos, final String operationId) {
        Objects.requireNonNull(pos, "pos");
        final CompletableFuture<LoadResult<S, N>> prefetched = prefetchedLoads.remove(pos);
        if (prefetched != null) {
            prefetchHits.increment();
            try {
                return prefetched.join();
            } catch (final CompletionException ignored) {
                // Fall back to the established synchronous recovery path.
            }
        }
        return loadWithPresenceSync(pos, operationId);
    }

    /** Performs load with presence sync. */
    private LoadResult<S, N> loadWithPresenceSync(final CisChunkPos pos, final String operationId) {
        return loadWithPresenceSync(pos, operationId, true);
    }

    /** Performs load with presence sync. */
    private LoadResult<S, N> loadWithPresenceSync(
            final CisChunkPos pos,
            final String operationId,
            final boolean clearOnFailure
    ) {
        ChunkTraceStore.trace(
                ChunkisDebugDomain.CHUNK_LIFECYCLE, ChunkTraceEventType.LOAD_TX_START,
                ChunkTraceSeverity.INFO, ChunkTraceReason.NONE, LOAD_SOURCE, "load requested", null, toChunkKey(pos),
                toRegionKey(pos), operationId, null, null
        );

        final RegionRead regionRead;
        try {
            regionRead = readRegion(pos, operationId);
        } catch (final IOException e) {
            final ChunkTraceReason reason = classifyLoadFailure(e);
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE, ChunkTraceEventType.LOAD_TX_END,
                    ChunkTraceSeverity.ERROR, reason, LOAD_SOURCE,
                    "load failed and entry will be cleared: " + e.getMessage(), null, toChunkKey(pos),
                    toRegionKey(pos), operationId, null, null
            );
            Chunkis.LOGGER.error(
                    "Chunkis: Failed to open CIS region for {}.{} Error: {}",
                    pos,
                    clearOnFailure ? " Clearing corrupted data." : "",
                    e.getMessage()
            );
            if (clearOnFailure) {
                clearChunk(pos);
            }
            return new LoadResult<>(newEmptyDelta(), false);
        }
        final boolean storageEntryPresent = regionRead.entryPresent();

        try {
            final ChunkDelta<S, N> delta = decodeCompressed(pos, regionRead.compressedData());
            if (!delta.isEmpty()) {
                ChunkTraceStore.trace(
                        ChunkisDebugDomain.CHUNK_LIFECYCLE, ChunkTraceEventType.LOAD_SOURCE_RESOLVED,
                        ChunkTraceSeverity.INFO, ChunkTraceReason.CHUNKIS_STORAGE, LOAD_SOURCE,
                        "load source resolved: storage entry decoded", null, toChunkKey(pos), toRegionKey(pos),
                        operationId, delta.isDirty(), null
                );
            }
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE,
                    ChunkTraceEventType.LOAD_TX_END,
                    ChunkTraceSeverity.INFO,
                    delta.isEmpty() ? ChunkTraceReason.NEITHER :
                            ChunkTraceReason.CHUNKIS_STORAGE,
                    LOAD_SOURCE,
                    delta.isEmpty() ? "load returned empty delta"
                            : "load returned stored delta",
                    null,
                    toChunkKey(pos),
                    toRegionKey(pos),
                    operationId,
                    delta.isDirty(),
                    null
            );
            return new LoadResult<>(delta, storageEntryPresent);
        } catch (final Exception e) {
            final ChunkTraceReason reason = classifyLoadFailure(e);
            ChunkTraceStore.trace(
                    ChunkisDebugDomain.CHUNK_LIFECYCLE, ChunkTraceEventType.LOAD_TX_END,
                    ChunkTraceSeverity.ERROR, reason, LOAD_SOURCE,
                    "load failed and entry will be cleared: " + e.getMessage(), null, toChunkKey(pos),
                    toRegionKey(pos), operationId, null, null
            );
            Chunkis.LOGGER.error(
                    "Chunkis: Failed to decode CIS chunk at {}.{} Error: {}", pos,
                    clearOnFailure ? " Clearing corrupted data." : "",
                    e.getMessage()
            );
            if (clearOnFailure) {
                clearChunk(pos);
            }
            return new LoadResult<>(newEmptyDelta(), storageEntryPresent);
        }
    }

    /**
     * Returns whether a region file currently contains a stored entry for the chunk.
     *
     * <p>I/O failures are treated as a negative answer so callers can use this as
     * a lightweight existence probe.</p>
     *
     * @param pos chunk position
     * @return {@code true} if an entry exists in storage
     */
    public boolean contains(final CisChunkPos pos) {
        Objects.requireNonNull(pos, "pos");
        try {
            synchronized (regionFiles) {
                final RegionFile regionFile = getRegionFile(pos, false);
                return regionFile != null && regionFile.hasChunk(pos);
            }
        } catch (final IOException e) {
            return false;
        }
    }

    /**
     * Loads a chunk delta without clearing the underlying storage entry on decode
     * failure.
     *
     * <p>Intended for recovery-oriented workflows such as migration, where
     * preserving the original bytes matters more than self-healing. Missing regions
     * or chunk slots still return a fresh empty delta.</p>
     *
     * @param pos chunk position
     * @return loaded chunk delta, or an empty delta if absent
     * @throws IOException if region I/O or decode fails
     */
    public ChunkDelta<S, N> loadWithoutClearing(final CisChunkPos pos) throws IOException {
        Objects.requireNonNull(pos, "pos");
        return loadUnchecked(pos);
    }

    /**
     * Closes all cached region files and releases current-thread codec state.
     *
     * <p>The cache is drained under the write lock and files are compacted and
     * closed outside it, keeping the lock held only for cache mutation and not
     * for potentially slow file work. No further storage operations are valid
     * after this method returns.</p>
     */
    public void close() {
        prefetchExecutor.shutdown();
        try {
            if (!prefetchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                prefetchExecutor.shutdownNow();
            }
        } catch (final InterruptedException e) {
            Thread.currentThread()
                    .interrupt();
            prefetchExecutor.shutdownNow();
        }
        if (prefetchRequests.sum() > 0) {
            Chunkis.LOGGER.info(
                    "Chunkis prefetch summary: requests={}, accepted={}, storedEntries={}, nonEmpty={}, hits={}, drops={}, unused={}",
                    prefetchRequests.sum(),
                    prefetchAccepted.sum(),
                    prefetchStoredEntries.sum(),
                    prefetchNonEmptyDeltas.sum(),
                    prefetchHits.sum(),
                    prefetchDrops.sum(),
                    Math.max(0L, prefetchAccepted.sum() - prefetchHits.sum())
            );
        }
        prefetchedLoads.clear();

        synchronized (regionFiles) {
            regionFiles.closeAll();
        }

        compressionContext.remove();
        encoder.remove();
        decoder.remove();
    }

    /**
     * Clears a chunk entry from its region file.
     *
     * <p>Does not create a missing region just to clear a non-existent entry.
     * When no region file exists the clear is considered a no-op success.</p>
     */
    private boolean clearChunk(final CisChunkPos pos) {
        try {
            synchronized (regionFiles) {
                prefetchedLoads.remove(pos);
                final RegionFile regionFile = getRegionFile(pos, false);

                if (regionFile != null) {
                    regionFile.write(pos, null);
                }
            }

            return true;
        } catch (final IOException e) {
            Chunkis.LOGGER.warn("Chunkis: Failed to clear CIS chunk {}", pos, e);
            return false;
        }
    }

    /**
     * Shared load implementation used by both {@link #load} and
     * {@link #loadWithoutClearing}.
     *
     * <p>Absent regions or chunk entries are represented as empty deltas. Decode
     * failures propagate so the caller can decide whether to clear or preserve
     * the source entry.</p>
     *
     * @throws IOException if region I/O, decompression, or decode fails
     */
    private ChunkDelta<S, N> loadUnchecked(final CisChunkPos pos) throws IOException {
        return decodeCompressed(pos, readRegion(pos, null).compressedData());
    }

    /** Performs read region. */
    private RegionRead readRegion(final CisChunkPos pos, final String operationId) throws IOException {
        synchronized (regionFiles) {
            final RegionFile regionFile = getRegionFile(pos, false);
            if (regionFile == null) {
                return new RegionRead(false, null);
            }
            return new RegionRead(regionFile.hasChunk(pos), regionFile.read(pos, operationId));
        }
    }

    /** Performs decode compressed. */
    private ChunkDelta<S, N> decodeCompressed(final CisChunkPos pos, final byte[] compressedData) throws IOException {
        if (compressedData == null) {
            return newEmptyDelta();
        }

        final byte[] decompressed;
        try {
            decompressed = compressionContext.get()
                    .decompress(compressedData);
        } catch (final IOException e) {
            throw e;
        } catch (final Exception e) {
            throw new IOException("Failed to decompress CIS chunk " + pos, e);
        }

        if (decompressed.length < MIN_DECOMPRESSED_SIZE) {
            throw new IOException("Decompressed CIS data too small for chunk " + pos + ": " + decompressed.length +
                    " bytes (compressed size: " + compressedData.length + ")");
        }

        return decoder.get()
                .decode(decompressed);
    }

    /**
     * Gets or opens the region file containing the given chunk.
     *
     * <p>Cache hits are moved to the front of the linked map to maintain LRU
     * order. New files are also inserted at the front; eviction removes from the
     * back.</p>
     *
     * @param pos    chunk position
     * @param create whether to create the region file when it does not exist
     * @return region file, or {@code null} when {@code create == false} and no
     *         file exists on disk
     * @throws IOException if the region file cannot be opened
     */
    private RegionFile getRegionFile(final CisChunkPos pos, final boolean create) throws IOException {
        return regionFiles.get(pos, create);
    }

    /**
     * Drains and returns all cached region files.
     * Package-private for use by maintenance / test helpers.
     *
     * @return cached region files previously held open by this storage instance
     */
    List<RegionFile> drainRegionCache() {
        synchronized (regionFiles) {
            return regionFiles.drain();
        }
    }

    /**
     * Returns the backing region directory for package-local maintenance helpers.
     *
     * @return root directory containing this storage instance's {@code .cis} files
     */
    Path storageDir() {
        return storageDir;
    }

    /**
     * Result of a load together with storage presence observed before decoding.
     *
     * @param delta               decoded delta
     * @param storageEntryPresent whether the region contained an entry before decode
     */
    public record LoadResult<S, N>(ChunkDelta<S, N> delta, boolean storageEntryPresent) {

    }

    private record RegionRead(boolean entryPresent, byte[] compressedData) {

    }

    /**
     * Immutable encoded payload for deferred compression and write.
     *
     * <p>A clear payload has {@link #clearChunk} set to {@code true} and
     * {@link #rawData} is {@code null}. A write payload carries the uncompressed
     * CIS bytes.</p>
     */
    public static final class PreparedSave {

        /**
         * Whether this prepared operation clears the chunk entry instead of writing bytes.
         */
        private final boolean clearChunk;

        /**
         * Uncompressed encoded CIS payload for deferred compression and write.
         */
        private final byte[] rawData;

        /**
         * Creates a prepared save operation.
         *
         * @param clearChunk whether the operation clears the chunk entry
         * @param rawData    encoded uncompressed CIS bytes, or {@code null} for clear operations
         */
        private PreparedSave(final boolean clearChunk, final byte[] rawData) {
            this.clearChunk = clearChunk;
            this.rawData = rawData;
        }

        /**
         * Creates a prepared clear operation.
         */
        public static PreparedSave clear() {
            return new PreparedSave(true, null);
        }

        /**
         * Creates a prepared write operation.
         *
         * @param rawData encoded uncompressed CIS bytes
         */
        public static PreparedSave write(final byte[] rawData) {
            return new PreparedSave(false, Objects.requireNonNull(rawData, "rawData"));
        }

        /**
         * Returns whether this prepared operation clears rather than writes the chunk entry.
         *
         * @return {@code true} for clear operations, {@code false} for write operations
         */
        public boolean clearChunk() {
            return clearChunk;
        }

        /**
         * Returns the uncompressed CIS payload for a write operation.
         *
         * @return raw encoded payload bytes, or {@code null} for clear operations
         */
        public byte[] rawData() {
            return rawData;
        }
    }
}
