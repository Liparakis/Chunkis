package io.liparakis.chunkis.world.tracking.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.config.ChunkisDebugLevel;
import io.liparakis.chunkis.debug.model.ChunkTraceEvent;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.world.restoration.nbt.CisNbtUtil;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GlobalChunkTracker}, verifying delta tracking state rules,
 * tracing behavior, debug log triggers, and assertion checks on cached deltas.
 */
class GlobalChunkTrackerTest {

    /**
     * Resets the tracker, trace store, and debug configurations after each test run.
     */
    @AfterEach
    void tearDown() {
        GlobalChunkTracker.clear();
        ChunkTraceStore.clear();
        ChunkTraceStore.resetForTests();
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.OFF);
    }

    /**
     * Verifies that the tracker retains the existing authoritative delta if the incoming replacement
     * delta has a weaker authority state.
     */
    @Test
    void keepsExistingAuthoritativeDeltaWhenIncomingReplacementIsWeaker() {
        final CompoundTag baseChunkNbt = new CompoundTag();
        baseChunkNbt.putString("status", "full");
        final ChunkDelta<String, CompoundTag> existing = new ChunkDelta<>();
        existing.setChunkMetadata(
                CisNbtUtil.createChunkMetadataTakingOwnership(
                        null,
                        false,
                        false,
                        baseChunkNbt
                ),
                false
        );
        final ChunkDelta<String, CompoundTag> weakerIncoming = new ChunkDelta<>();

        assertTrue(GlobalChunkTracker.shouldKeepExistingAuthoritativeDelta(existing, weakerIncoming));
    }

    /**
     * Verifies that the tracker allows replacing the existing delta if the incoming delta
     * is also flagged as authoritative.
     */
    @Test
    void allowsReplacementWhenIncomingDeltaIsAlsoAuthoritative() {
        final ChunkDelta<String, CompoundTag> existing = new ChunkDelta<>();
        existing.markDirty();
        final ChunkDelta<String, CompoundTag> incoming = new ChunkDelta<>();
        incoming.markDirty();

        assertFalse(GlobalChunkTracker.shouldKeepExistingAuthoritativeDelta(existing, incoming));
    }

    /**
     * Verifies that unloading a chunk with an active dirty delta generates a corresponding trace event.
     */
    @Test
    void tracesChunkUnloadWhenActiveDirtyDeltaWasPresent() {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);
        final ResourceKey<Level> overworld = ResourceKey.create(
                Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("minecraft", "overworld")
        );

        GlobalChunkTracker.noteChunkUnloaded(overworld, 4, -3, true);

        final List<ChunkTraceEvent> latest = ChunkTraceStore.latest(1);
        assertEquals(1, latest.size());
        assertEquals(ChunkTraceReason.TRACKER_CHUNK_UNLOADED,
                latest.getFirst()
                        .reason());
        assertEquals(Boolean.TRUE,
                latest.getFirst()
                        .dirtyState());
        assertEquals("minecraft:overworld",
                latest.getFirst()
                        .worldId());
        assertEquals(4,
                latest.getFirst()
                        .chunkKey()
                        .x());
        assertEquals(-3,
                latest.getFirst()
                        .chunkKey()
                        .z());
    }

    /**
     * Verifies that the mutation origin (source description) is correctly recorded
     * in the trace event when a delta is added or modified.
     */
    @Test
    void carriesMutationOriginIntoDirtyTrackerEvent() {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);
        final ResourceKey<Level> overworld = ResourceKey.create(
                Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("minecraft", "overworld")
        );
        final ChunkDelta<String, CompoundTag> delta = new ChunkDelta<>();
        delta.claimOwnership("PLAYER_OR_COMMAND_EDIT", "test");

        GlobalChunkTracker.addDelta(overworld, 7, 9, delta, "WorldChunkMixin#setBlockState");

        final List<ChunkTraceEvent> latest = ChunkTraceStore.latest(6);
        assertTrue(latest.stream()
                .anyMatch(event ->
                        event.reason() == ChunkTraceReason.TRACKER_DIRTY_MAP_PUT
                                && "WorldChunkMixin#setBlockState".equals(event.source())
                                && "minecraft:overworld".equals(event.worldId())
                                && event.chunkKey() != null
                                && event.chunkKey()
                                .x() == 7
                                && event.chunkKey()
                                .z() == 9));
    }

    /**
     * Verifies that adding a delta does not write any logs or trace events when
     * debug logging is configured to be OFF.
     */
    @Test
    void addDeltaDoesNotTraceWhenDebugIsOff() {
        final ResourceKey<Level> overworld = ResourceKey.create(
                Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("minecraft", "overworld")
        );
        final ChunkDelta<String, CompoundTag> delta = new ChunkDelta<>();
        delta.claimOwnership("PLAYER_OR_COMMAND_EDIT", "test");

        GlobalChunkTracker.addDelta(overworld, 7, 9, delta, "WorldChunkMixin#setBlockState");

        assertTrue(ChunkTraceStore.latest(10)
                .isEmpty());
    }

    /**
     * Verifies that completing an asynchronous save invalidates the unload cache
     * for a clean delta and logs appropriate mark-saved/invalidate trace events.
     */
    @Test
    void asyncSaveCompletionInvalidatesUnloadCacheForCleanDelta() {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);
        final ResourceKey<Level> overworld = ResourceKey.create(
                Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("minecraft", "overworld")
        );
        final ChunkDelta<String, CompoundTag> delta = new ChunkDelta<>();
        delta.claimOwnership("PLAYER_OR_COMMAND_EDIT", "test");

        GlobalChunkTracker.addDelta(overworld, 7, 9, delta, "test");

        final long savedGeneration = delta.getMutationGeneration();
        assertTrue(delta.isDirty());

        GlobalChunkTracker.markSavedIfUnchanged(overworld, 7, 9, delta, savedGeneration);

        assertFalse(delta.isDirty());
        assertNull(GlobalChunkTracker.getDelta(overworld, 7, 9));

        final List<ChunkTraceEvent> events = ChunkTraceStore.latest(4);
        assertTrue(events.stream()
                .anyMatch(event -> event.reason() == ChunkTraceReason.TRACKER_MARK_SAVED));
        assertTrue(events.stream()
                .anyMatch(event -> event.reason()
                        == ChunkTraceReason.TRACKER_UNLOAD_CACHE_INVALIDATED));
    }

    /**
     * A chunk reporting a clean delta, as it does whenever its delta is set (after a save as well), must not put it
     * in the tracker: nothing would ever remove it.
     */
    @Test
    void cleanDeltaReportedByChunkIsNotTracked() {
        final ResourceKey<Level> overworld = overworld();
        final ChunkDelta<String, CompoundTag> delta = new ChunkDelta<>();
        delta.claimOwnership("PLAYER_OR_COMMAND_EDIT", "test");
        assertFalse(delta.isDirty());

        GlobalChunkTracker.trackLiveDelta(DimensionChunkKey.of(overworld, 5, 6), delta, "test", null);

        assertNull(GlobalChunkTracker.getDelta(overworld, 5, 6));
    }

    /**
     * A clean report of a delta the tracker still holds, now saved, releases it.
     */
    @Test
    void cleanReportReleasesTrackedDeltaThatWasSaved() {
        final ResourceKey<Level> overworld = overworld();
        final ChunkDelta<String, CompoundTag> delta = new ChunkDelta<>();
        delta.claimOwnership("PLAYER_OR_COMMAND_EDIT", "test");
        GlobalChunkTracker.addDelta(overworld, 5, 6, delta, "test");
        delta.markSaved();

        GlobalChunkTracker.trackLiveDelta(DimensionChunkKey.of(overworld, 5, 6), delta, "test", null);

        assertNull(GlobalChunkTracker.getDelta(overworld, 5, 6));
    }

    /**
     * A clean report never displaces a different delta that holds unsaved changes.
     */
    @Test
    void cleanReportKeepsDifferentDirtyDelta() {
        final ResourceKey<Level> overworld = overworld();
        final ChunkDelta<String, CompoundTag> unsaved = new ChunkDelta<>();
        unsaved.claimOwnership("PLAYER_OR_COMMAND_EDIT", "test");
        GlobalChunkTracker.addDelta(overworld, 5, 6, unsaved, "test");
        final ChunkDelta<String, CompoundTag> clean = new ChunkDelta<>();
        clean.claimOwnership("PLAYER_OR_COMMAND_EDIT", "test");

        GlobalChunkTracker.trackLiveDelta(DimensionChunkKey.of(overworld, 5, 6), clean, "test", null);

        assertEquals(unsaved, GlobalChunkTracker.getDelta(overworld, 5, 6));
        assertTrue(unsaved.isDirty());
    }

    /**
     * A dirty report is tracked until its save completes.
     */
    @Test
    void dirtyDeltaReportedByChunkIsTrackedUntilSaved() {
        final ResourceKey<Level> overworld = overworld();
        final ChunkDelta<String, CompoundTag> delta = new ChunkDelta<>();
        delta.claimOwnership("PLAYER_OR_COMMAND_EDIT", "test");
        delta.markDirty("test");

        GlobalChunkTracker.trackLiveDelta(DimensionChunkKey.of(overworld, 5, 6), delta, "test", null);
        assertEquals(delta, GlobalChunkTracker.getDelta(overworld, 5, 6));

        GlobalChunkTracker.markSavedIfUnchanged(overworld, 5, 6, delta, delta.getMutationGeneration());
        assertNull(GlobalChunkTracker.getDelta(overworld, 5, 6));
    }

    /**
     * Returns the overworld's registry key.
     *
     * @return key
     */
    private static ResourceKey<Level> overworld() {
        return ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "overworld"));
    }

    /**
     * Verifies that a dirty delta check requires both the exact same object instance
     * and the exact mutation generation number to be considered current.
     */
    @Test
    void currentDirtyDeltaRequiresSameInstanceAndGeneration() {
        final ResourceKey<Level> overworld = ResourceKey.create(
                Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("minecraft", "overworld")
        );
        final ChunkDelta<String, CompoundTag> delta = new ChunkDelta<>();
        delta.claimOwnership("PLAYER_OR_COMMAND_EDIT", "test");
        GlobalChunkTracker.addDelta(overworld, 3, 4, delta, "test");

        assertTrue(GlobalChunkTracker.isCurrentDirtyDelta(
                overworld,
                3,
                4,
                delta,
                delta.getMutationGeneration()
        ));

        final ChunkDelta<String, CompoundTag> replacement = new ChunkDelta<>();
        replacement.claimOwnership("PLAYER_OR_COMMAND_EDIT", "test");
        GlobalChunkTracker.addDelta(overworld, 3, 4, replacement, "test");

        assertFalse(GlobalChunkTracker.isCurrentDirtyDelta(
                overworld,
                3,
                4,
                delta,
                delta.getMutationGeneration()
        ));
    }

    /**
     * Verifies that the tracker logs an assertion failure trace event if a client attempts to cache
     * a block entity-only payload without a baseline chunk.
     */
    @Test
    void assertsImmediatelyWhenCachingBlockEntityOnlyPayloadWithoutBase() {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);
        final ResourceKey<Level> overworld = ResourceKey.create(
                Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("minecraft", "overworld")
        );
        final ChunkDelta<String, CompoundTag> delta = new ChunkDelta<>();
        delta.addBlockEntityData(1, 64, 1, new CompoundTag());
        delta.claimOwnership("PLAYER_OR_COMMAND_EDIT", "test");

        GlobalChunkTracker.addDelta(overworld, 6, 85, delta, "WorldChunkMixin#setBlockEntity");

        final List<ChunkTraceEvent> events = ChunkTraceStore.latest(4);
        assertTrue(events.stream()
                .anyMatch(event ->
                        event.eventType() == ChunkTraceEventType.ASSERTION_FAILED
                                && event.reason() == ChunkTraceReason.INVALID_PAYLOAD
                                && "WorldChunkMixin#setBlockEntity".equals(event.source())));
    }
}
