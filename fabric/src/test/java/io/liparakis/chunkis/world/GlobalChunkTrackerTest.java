package io.liparakis.chunkis.world;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.debug.ChunkTraceEvent;
import io.liparakis.chunkis.debug.ChunkTraceReason;
import io.liparakis.chunkis.debug.ChunkisDebugConfig;
import io.liparakis.chunkis.debug.ChunkisDebugLevel;
import io.liparakis.chunkis.debug.ChunkTraceStore;
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

        GlobalChunkTracker.addDelta(overworld, 7, 9, delta, "WorldChunkMixin#setBlockState");

        final List<ChunkTraceEvent> latest = ChunkTraceStore.latest(1);
        assertEquals(1, latest.size());
        assertEquals(ChunkTraceReason.TRACKER_DIRTY_MAP_PUT, latest.getFirst().reason());
        assertEquals("WorldChunkMixin#setBlockState", latest.getFirst().source());
        assertEquals("minecraft:overworld", latest.getFirst().worldId());
        assertEquals(7, latest.getFirst().chunkKey().x());
        assertEquals(9, latest.getFirst().chunkKey().z());
    }
}
