package io.liparakis.chunkis.command;

import io.liparakis.chunkis.debug.ChunkTraceEvent;
import io.liparakis.chunkis.debug.ChunkTraceEventType;
import io.liparakis.chunkis.debug.ChunkTraceReason;
import io.liparakis.chunkis.debug.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.ChunkTraceSuspect;
import io.liparakis.chunkis.debug.ChunkTraceWatchpoints;
import io.liparakis.chunkis.debug.ChunkisDebugDomain;
import io.liparakis.chunkis.debug.DebugChunkKey;
import io.liparakis.chunkis.debug.DebugRegionKey;
import io.liparakis.chunkis.debug.PayloadWatchTarget;
import io.liparakis.chunkis.storage.AsyncCisSaveManager;
import io.liparakis.chunkis.storage.BaseChunkCaptureScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkDebugCommandTest {

    @AfterEach
    void tearDown() {
        ChunkTraceWatchpoints.clear();
    }

    @Test
    void formatsStructuredEventTimelineLine() {
        final ChunkTraceEvent event = new ChunkTraceEvent(
                42L,
                1_717_171_717_000L,
                "Server thread",
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.SAVE_FLUSH_COMPLETED,
                ChunkTraceSeverity.INFO,
                ChunkTraceReason.STORAGE_WRITE,
                "CisStorage#writePrepared",
                "flush completed",
                "minecraft:overworld",
                new DebugChunkKey(12, -8),
                new DebugRegionKey(0, -1),
                "save-12--8-7",
                Boolean.FALSE,
                512,
                PayloadWatchTarget.block("minecraft:overworld", 200, 70, -120),
                "restore",
                "pos=200,70,-120 state=minecraft:chest section=4"
        );

        final String formatted = ChunkDebugCommand.formatEvent(event);

        assertTrue(formatted.contains("#42"));
        assertTrue(formatted.contains("SAVE_FLUSH_COMPLETED"));
        assertTrue(formatted.contains("INFO/STORAGE_WRITE"));
        assertTrue(formatted.contains("world=minecraft:overworld"));
        assertTrue(formatted.contains("chunk=12,-8"));
        assertTrue(formatted.contains("region=0,-1"));
        assertTrue(formatted.contains("dirty=false"));
        assertTrue(formatted.contains("bytes=512"));
        assertTrue(formatted.contains("op=save-12--8-7"));
        assertTrue(formatted.contains("payload=block@200,70,-120 world=minecraft:overworld"));
        assertTrue(formatted.contains("stage=restore"));
        assertTrue(formatted.contains("payloadSummary=pos=200,70,-120 state=minecraft:chest section=4"));
        assertTrue(formatted.contains("src=CisStorage#writePrepared"));
        assertTrue(formatted.contains("thread=Server thread"));
        assertTrue(formatted.contains("msg=flush completed"));
    }

    @Test
    void formatsWatchpointSummary() {
        ChunkTraceWatchpoints.watchChunk(new DebugChunkKey(7, -2));
        ChunkTraceWatchpoints.watchRegion(new DebugRegionKey(0, -1));
        ChunkTraceWatchpoints.watchPayload(PayloadWatchTarget.entity("minecraft:overworld", "1234"));

        final String formatted = ChunkDebugCommand.formatWatchpointSummary();

        assertTrue(formatted.contains("chunks=7,-2"));
        assertTrue(formatted.contains("regions=0,-1"));
        assertTrue(formatted.contains("payloads=entity@1234 world=minecraft:overworld"));
    }

    @Test
    void formatsPendingSnapshotSummary() {
        final String formatted = ChunkDebugCommand.formatPendingSnapshot(
                new ChunkDebugCommand.PendingChunkSnapshot(
                        new DebugChunkKey(7, -2),
                        true,
                        new AsyncCisSaveManager.PendingSaveSnapshot(
                                new DebugChunkKey(7, -2),
                                "save-7--2-4",
                                4L,
                                true
                        ),
                        new BaseChunkCaptureScheduler.QueuedCaptureSnapshot(
                                new DebugChunkKey(7, -2),
                                true
                        )
                )
        );

        assertTrue(formatted.contains("chunk=7,-2"));
        assertTrue(formatted.contains("trackerDirty=true"));
        assertTrue(formatted.contains("asyncQueued=true"));
        assertTrue(formatted.contains("baseCaptureQueued=true"));
        assertTrue(formatted.contains("asyncOp=save-7--2-4"));
        assertTrue(formatted.contains("asyncGeneration=4"));
        assertTrue(formatted.contains("baseCaptureDirty=true"));
    }

    @Test
    void truncatesOversizedChatOutput() {
        final String oversized = "x".repeat(20_000);

        final ChunkDebugCommand.ChatMessage chatMessage = ChunkDebugCommand.truncateForChat(oversized);

        assertTrue(chatMessage.truncated());
        assertTrue(chatMessage.text().contains("[truncated; full trace written to log/file]"));
        assertTrue(chatMessage.text().length() < oversized.length());
    }

    @Test
    void leavesSmallChatOutputUntouched() {
        final ChunkDebugCommand.ChatMessage chatMessage = ChunkDebugCommand.truncateForChat("small");

        assertFalse(chatMessage.truncated());
        assertTrue("small".equals(chatMessage.text()));
    }

    @Test
    void formatsSuspectSummaryAndDetail() {
        final ChunkTraceEvent originalFailure = new ChunkTraceEvent(
                10L,
                1_717_171_717_000L,
                "Server thread",
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.RESTORE_COMPLETED,
                ChunkTraceSeverity.ERROR,
                ChunkTraceReason.RESTORE_EMPTY_RESULT,
                "ChunkRestorer",
                "restore applied zero blocks",
                "minecraft:overworld",
                new DebugChunkKey(7, -2),
                new DebugRegionKey(0, -1),
                "save-7--2-4",
                null,
                null
        );
        final ChunkTraceEvent latestEvent = new ChunkTraceEvent(
                21L,
                1_717_171_718_000L,
                "Server thread",
                ChunkisDebugDomain.CHUNK_LIFECYCLE,
                ChunkTraceEventType.RESTORE_FAILED,
                ChunkTraceSeverity.ERROR,
                ChunkTraceReason.RESTORE_EXCEPTION,
                "ChunkRestorer",
                "write failed",
                "minecraft:overworld",
                new DebugChunkKey(7, -2),
                new DebugRegionKey(0, -1),
                "save-7--2-4",
                null,
                null
        );
        final ChunkTraceSuspect suspect = new ChunkTraceSuspect(
                2L,
                originalFailure,
                latestEvent,
                new DebugChunkKey(7, -2),
                new DebugRegionKey(0, -1),
                "save-7--2-4",
                ChunkTraceReason.RESTORE_EMPTY_RESULT,
                ChunkTraceSeverity.ERROR,
                1_717_171_717_000L,
                1_717_171_718_000L,
                6,
                List.of(originalFailure, latestEvent),
                "write failed"
        );

        final String summary = ChunkDebugCommand.formatSuspectSummary(suspect);
        final String detail = ChunkDebugCommand.formatSuspectDetail(suspect);

        assertTrue(summary.contains("id=2 chunk=7,-2"));
        assertTrue(summary.contains("region=0,-1"));
        assertTrue(summary.contains("reason=RESTORE_EMPTY_RESULT"));
        assertTrue(summary.contains("latest=RESTORE_FAILED#21"));
        assertTrue(detail.contains("original=RESTORE_COMPLETED#10"));
        assertTrue(detail.contains("latest=RESTORE_FAILED#21"));
        assertTrue(detail.contains("op=save-7--2-4"));
        assertTrue(detail.contains("timeline=2"));
        assertTrue(detail.contains("inspect=/chunkis debug suspect timeline 2"));
    }
}
