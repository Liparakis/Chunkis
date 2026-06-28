package io.liparakis.chunkis.world;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.model.ChunkTraceEvent;
import io.liparakis.chunkis.debug.model.ChunkTraceEventType;
import io.liparakis.chunkis.debug.model.ChunkTraceReason;
import io.liparakis.chunkis.debug.config.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.config.ChunkisDebugLevel;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.storage.CisNbtUtil;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.junit.jupiter.api.AfterEach;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalChunkTrackerTest {

    @AfterEach
    void tearDown() {
        GlobalChunkTracker.clear();
        ChunkTraceStore.clear();
        ChunkTraceStore.resetForTests();
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.OFF);
    }

    @Test
    void keepsExistingAuthoritativeDeltaWhenIncomingReplacementIsWeaker() {
        final NbtCompound baseChunkNbt = new NbtCompound();
        baseChunkNbt.putString("status", "full");
        final ChunkDelta<String, NbtCompound> existing = new ChunkDelta<>();
        existing.setChunkMetadata(
                CisNbtUtil.createChunkMetadataTakingOwnership(
                        null,
                        false,
                        false,
                        baseChunkNbt
                ),
                false
        );
        final ChunkDelta<String, NbtCompound> weakerIncoming = new ChunkDelta<>();

        assertTrue(GlobalChunkTracker.shouldKeepExistingAuthoritativeDelta(existing, weakerIncoming));
    }

    @Test
    void allowsReplacementWhenIncomingDeltaIsAlsoAuthoritative() {
        final ChunkDelta<String, NbtCompound> existing = new ChunkDelta<>();
        existing.markDirty();
        final ChunkDelta<String, NbtCompound> incoming = new ChunkDelta<>();
        incoming.markDirty();

        assertFalse(GlobalChunkTracker.shouldKeepExistingAuthoritativeDelta(existing, incoming));
    }

    @Test
    void tracesChunkUnloadWhenActiveDirtyDeltaWasPresent() {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);
        final RegistryKey<World> overworld = RegistryKey.of(
                RegistryKeys.WORLD,
                Identifier.of("minecraft", "overworld")
        );

        GlobalChunkTracker.noteChunkUnloaded(overworld, 4, -3, true);

        final List<ChunkTraceEvent> latest = ChunkTraceStore.latest(1);
        assertEquals(1, latest.size());
        assertEquals(ChunkTraceReason.TRACKER_CHUNK_UNLOADED, latest.getFirst().reason());
        assertEquals(Boolean.TRUE, latest.getFirst().dirtyState());
        assertEquals("minecraft:overworld", latest.getFirst().worldId());
        assertEquals(4, latest.getFirst().chunkKey().x());
        assertEquals(-3, latest.getFirst().chunkKey().z());
    }

    @Test
    void carriesMutationOriginIntoDirtyTrackerEvent() {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);
        final RegistryKey<World> overworld = RegistryKey.of(
                RegistryKeys.WORLD,
                Identifier.of("minecraft", "overworld")
        );
        final ChunkDelta<String, NbtCompound> delta = new ChunkDelta<>();
        delta.claimOwnership("PLAYER_OR_COMMAND_EDIT", "test");

        GlobalChunkTracker.addDelta(overworld, 7, 9, delta, "WorldChunkMixin#setBlockState");

        final List<ChunkTraceEvent> latest = ChunkTraceStore.latest(6);
        assertTrue(latest.stream().anyMatch(event ->
                event.reason() == ChunkTraceReason.TRACKER_DIRTY_MAP_PUT
                        && "WorldChunkMixin#setBlockState".equals(event.source())
                        && "minecraft:overworld".equals(event.worldId())
                        && event.chunkKey() != null
                        && event.chunkKey().x() == 7
                        && event.chunkKey().z() == 9));
    }

    @Test
    void asyncSaveCompletionInvalidatesUnloadCacheForCleanDelta() {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);
        final RegistryKey<World> overworld = RegistryKey.of(
                RegistryKeys.WORLD,
                Identifier.of("minecraft", "overworld")
        );
        final ChunkDelta<String, NbtCompound> delta = new ChunkDelta<>();
        delta.claimOwnership("PLAYER_OR_COMMAND_EDIT", "test");

        GlobalChunkTracker.addDelta(overworld, 7, 9, delta, "test");

        final long savedGeneration = delta.getMutationGeneration();
        assertTrue(delta.isDirty());

        GlobalChunkTracker.markSavedIfUnchanged(overworld, 7, 9, delta, savedGeneration);

        assertFalse(delta.isDirty());
        assertNull(GlobalChunkTracker.getDelta(overworld, 7, 9));

        final List<ChunkTraceEvent> events = ChunkTraceStore.latest(4);
        assertTrue(events.stream().anyMatch(event -> event.reason() == ChunkTraceReason.TRACKER_MARK_SAVED));
        assertTrue(events.stream().anyMatch(event -> event.reason() == ChunkTraceReason.TRACKER_UNLOAD_CACHE_INVALIDATED));
    }

    @Test
    void currentDirtyDeltaRequiresSameInstanceAndGeneration() {
        final RegistryKey<World> overworld = RegistryKey.of(
                RegistryKeys.WORLD,
                Identifier.of("minecraft", "overworld")
        );
        final ChunkDelta<String, NbtCompound> delta = new ChunkDelta<>();
        delta.claimOwnership("PLAYER_OR_COMMAND_EDIT", "test");
        GlobalChunkTracker.addDelta(overworld, 3, 4, delta, "test");

        assertTrue(GlobalChunkTracker.isCurrentDirtyDelta(
                overworld,
                3,
                4,
                delta,
                delta.getMutationGeneration()
        ));

        final ChunkDelta<String, NbtCompound> replacement = new ChunkDelta<>();
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

    @Test
    void assertsImmediatelyWhenCachingBlockEntityOnlyPayloadWithoutBase() {
        ChunkisDebugConfig.setLevel(ChunkisDebugLevel.LIFECYCLE);
        final RegistryKey<World> overworld = RegistryKey.of(
                RegistryKeys.WORLD,
                Identifier.of("minecraft", "overworld")
        );
        final ChunkDelta<String, NbtCompound> delta = new ChunkDelta<>();
        delta.addBlockEntityData(1, 64, 1, new NbtCompound());
        delta.claimOwnership("PLAYER_OR_COMMAND_EDIT", "test");

        GlobalChunkTracker.addDelta(overworld, 6, 85, delta, "WorldChunkMixin#setBlockEntity");

        final List<ChunkTraceEvent> events = ChunkTraceStore.latest(4);
        assertTrue(events.stream().anyMatch(event ->
                event.eventType() == ChunkTraceEventType.ASSERTION_FAILED
                        && event.reason() == ChunkTraceReason.INVALID_PAYLOAD
                        && "WorldChunkMixin#setBlockEntity".equals(event.source())));
    }
}
