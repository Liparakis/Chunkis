package io.liparakis.chunkis.command;

import io.liparakis.chunkis.debug.model.ChunkTraceEvent;
import io.liparakis.chunkis.debug.model.ChunkTraceSuspect;
import io.liparakis.chunkis.debug.model.key.DebugChunkKey;
import io.liparakis.chunkis.debug.model.key.DebugRegionKey;
import io.liparakis.chunkis.debug.model.watch.PayloadWatchTarget;
import io.liparakis.chunkis.debug.trace.ChunkTraceStore;
import io.liparakis.chunkis.debug.watch.ChunkTraceWatchpoints;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Formatting and text-output helpers for {@link ChunkDebugCommand}.
 *
 * <p>This class owns presentation-only concerns: turning trace/debug state into
 * operator-facing strings, constraining chat payload size, and resolving export
 * paths for text output. Command registration and store orchestration stay in
 * {@link ChunkDebugCommand}.</p>
 */
final class ChunkDebugOutput {

    /**
     * Private constructor to prevent utility class instantiation.
     *
     * @throws AssertionError always
     */
    private ChunkDebugOutput() {
        throw new AssertionError("Utility class");
    }

    /**
     * Formats a single {@link ChunkTraceEvent} as a human-readable log line suitable for
     * in-game chat or a log file. Optional fields are appended only when non-null.
     *
     * @param event      the event to format; must not be null
     * @param timeFormat formatter used for event timestamps
     * @return a single-line string representation of the event
     */
    static String formatEvent(final ChunkTraceEvent event, final DateTimeFormatter timeFormat) {
        final StringBuilder sb = new StringBuilder(192);
        sb.append('#')
                .append(event.eventId())
                .append(' ')
                .append(timeFormat.format(Instant.ofEpochMilli(event.timestampMillis())))
                .append(" UTC ")
                .append(event.eventType())
                .append(' ')
                .append(event.severity())
                .append('/')
                .append(event.reason())
                .append(" [")
                .append(event.domain())
                .append(']');

        if (event.worldId() != null) {
            sb.append(" world=")
                    .append(event.worldId());
        }
        if (event.chunkKey() != null) {
            sb.append(" chunk=")
                    .append(event.chunkKey()
                            .x())
                    .append(',')
                    .append(event.chunkKey()
                            .z());
        }
        if (event.regionKey() != null) {
            sb.append(" region=")
                    .append(event.regionKey()
                            .x())
                    .append(',')
                    .append(event.regionKey()
                            .z());
        }
        if (event.dirtyState() != null) {
            sb.append(" dirty=")
                    .append(event.dirtyState());
        }
        if (event.byteSize() != null) {
            sb.append(" bytes=")
                    .append(event.byteSize());
        }
        if (event.operationId() != null) {
            sb.append(" op=")
                    .append(event.operationId());
        }
        if (event.payloadWatchTarget() != null) {
            sb.append(" payload=")
                    .append(event.payloadWatchTarget()
                            .describe());
        }
        if (event.payloadWatchStage() != null) {
            sb.append(" stage=")
                    .append(event.payloadWatchStage());
        }
        if (event.payloadWatchSummary() != null) {
            sb.append(" payloadSummary=")
                    .append(event.payloadWatchSummary());
        }

        sb.append(" src=")
                .append(event.source())
                .append(" thread=")
                .append(event.threadName())
                .append(" msg=")
                .append(event.message());
        return sb.toString();
    }

    /**
     * Returns a summary of all currently configured watchpoints, or a "none configured" message.
     *
     * @return formatted watchpoint summary string
     */
    static String formatWatchpointSummary() {
        final List<DebugChunkKey> chunks = ChunkTraceWatchpoints.watchedChunks();
        final List<DebugRegionKey> regions = ChunkTraceWatchpoints.watchedRegions();
        final List<PayloadWatchTarget> payloads = ChunkTraceWatchpoints.watchedPayloads();
        if (chunks.isEmpty() && regions.isEmpty() && payloads.isEmpty()) {
            return "No trace watchpoints configured.";
        }

        final StringBuilder sb = new StringBuilder("Watchpoints:");
        if (!chunks.isEmpty()) {
            sb.append(" chunks=")
                    .append(formatChunks(chunks));
        }
        if (!regions.isEmpty()) {
            sb.append(" regions=")
                    .append(formatRegions(regions));
        }
        if (!payloads.isEmpty()) {
            sb.append(" payloads=")
                    .append(formatPayloads(payloads));
        }
        return sb.toString();
    }

    /**
     * Returns a diagnostic message explaining why a watched-event query returned no results.
     *
     * @param timeFormat formatter used for event timestamps inside summaries
     * @return non-empty diagnostic message
     */
    static String formatNoWatchedTraceMessage(final DateTimeFormatter timeFormat) {
        final List<ChunkTraceEvent> payloadEvents = ChunkTraceStore.latestMatching(
                1,
                event -> event.payloadWatchTarget() != null
        );
        if (payloadEvents.isEmpty()) {
            return "[Chunkis] No watched trace events stored. Store currently has no payload-watch events. "
                    + formatWatchpointSummary();
        }
        return "[Chunkis] No watched trace events matched current watchpoints. latestPayloadEvent="
                + formatEvent(payloadEvents.getFirst(), timeFormat) + " " + formatWatchpointSummary();
    }

    /**
     * Formats the pending save/capture state of a single watched chunk as a flat key=value string.
     *
     * @param snapshot pending status snapshot container
     * @return formatted snapshot details string
     */
    static String formatPendingSnapshot(final ChunkDebugCommand.PendingChunkSnapshot snapshot) {
        final StringBuilder sb = new StringBuilder(128);
        sb.append("chunk=")
                .append(snapshot.chunkKey()
                        .x())
                .append(',')
                .append(snapshot.chunkKey()
                        .z())
                .append(" trackerDirty=")
                .append(snapshot.trackerDirty())
                .append(" asyncQueued=")
                .append(snapshot.asyncPending() != null);

        if (snapshot.asyncPending() != null) {
            sb.append(" asyncOp=")
                    .append(snapshot.asyncPending()
                            .operationId())
                    .append(" asyncGeneration=")
                    .append(snapshot.asyncPending()
                            .generation())
                    .append(" asyncDirty=")
                    .append(snapshot.asyncPending()
                            .dirtyState());
        }
        return sb.toString();
    }

    /**
     * Formats one inspected chunk row showing live, tracked, and persisted state.
     *
     * @param snapshot inspected chunk details
     * @return one-line inspection summary
     */
    static String formatChunkInspectSnapshot(final ChunkDebugCommand.ChunkInspectSnapshot snapshot) {
        final StringBuilder sb = new StringBuilder(256);
        sb.append("chunk=")
                .append(snapshot.chunkKey().x())
                .append(',')
                .append(snapshot.chunkKey().z())
                .append(" loaded=")
                .append(snapshot.loaded())
                .append(" diskPresent=")
                .append(snapshot.persistedPresent())
                .append(" live=")
                .append(snapshot.liveState() != null ? snapshot.liveState() : "<none>")
                .append(" tracked=")
                .append(snapshot.trackedState() != null ? snapshot.trackedState() : "<none>")
                .append(" disk=")
                .append(snapshot.persistedState() != null ? snapshot.persistedState() : "<none>");
        if (snapshot.persistenceError() != null) {
            sb.append(" diskError=")
                    .append(snapshot.persistenceError());
        }
        return sb.toString();
    }

    /**
     * Formats a one-line summary of a suspect for use in suspect listings.
     *
     * @param suspect chunk trace suspect snapshot
     * @return one-line summary string
     */
    static String formatSuspectSummary(final ChunkTraceSuspect suspect) {
        final StringBuilder sb = new StringBuilder(160);
        sb.append("id=")
                .append(suspect.suspectId())
                .append(" chunk=")
                .append(suspect.chunkKey()
                        .x())
                .append(',')
                .append(suspect.chunkKey()
                        .z());
        if (suspect.regionKey() != null) {
            sb.append(" region=")
                    .append(suspect.regionKey()
                            .x())
                    .append(',')
                    .append(suspect.regionKey()
                            .z());
        }
        sb.append(" reason=")
                .append(suspect.reason())
                .append(" latest=")
                .append(suspect.latestEvent()
                        .eventType())
                .append('#')
                .append(suspect.latestEvent()
                        .eventId())
                .append(" severity=")
                .append(suspect.severity())
                .append(" occurrences=")
                .append(suspect.occurrenceCount());
        return sb.toString();
    }

    /**
     * Formats a detailed multi-field suspect description for operator output.
     *
     * @param suspect    suspect to describe
     * @param timeFormat formatter used for first/last seen timestamps
     * @return flattened detailed suspect description
     */
    static String formatSuspectDetail(final ChunkTraceSuspect suspect, final DateTimeFormatter timeFormat) {
        final StringBuilder sb = new StringBuilder(384);
        sb.append("suspectId=")
                .append(suspect.suspectId())
                .append(" chunk=")
                .append(suspect.chunkKey()
                        .x())
                .append(',')
                .append(suspect.chunkKey()
                        .z());
        if (suspect.regionKey() != null) {
            sb.append(" region=")
                    .append(suspect.regionKey()
                            .x())
                    .append(',')
                    .append(suspect.regionKey()
                            .z());
        }
        sb.append(" reason=")
                .append(suspect.reason())
                .append(" original=")
                .append(suspect.originalFailureEvent()
                        .eventType())
                .append('#')
                .append(suspect.originalFailureEvent()
                        .eventId())
                .append(" latest=")
                .append(suspect.latestEvent()
                        .eventType())
                .append('#')
                .append(suspect.latestEvent()
                        .eventId())
                .append(" severity=")
                .append(suspect.severity())
                .append(" firstSeen=")
                .append(timeFormat.format(
                        Instant.ofEpochMilli(suspect.firstSeenTimestampMillis())))
                .append(" UTC")
                .append(" lastSeen=")
                .append(timeFormat.format(
                        Instant.ofEpochMilli(suspect.lastSeenTimestampMillis())))
                .append(" UTC")
                .append(" occurrences=")
                .append(suspect.occurrenceCount());
        if (suspect.operationId() != null) {
            sb.append(" op=")
                    .append(suspect.operationId());
        }
        sb.append(" timeline=")
                .append(suspect.copiedTimeline()
                        .size())
                .append(" latestMsg=")
                .append(suspect.latestMessage())
                .append(" inspect=/chunkis debug suspect timeline ")
                .append(suspect.suspectId());
        return sb.toString();
    }

    /**
     * Formats a list of chunk keys into a space-separated string.
     *
     * @param chunks chunk keys list
     * @return space-separated coordinates string
     */
    private static String formatChunks(final List<DebugChunkKey> chunks) {
        return chunks.stream()
                .map(chunk -> chunk.x() + "," + chunk.z())
                .collect(Collectors.joining(" "));
    }

    /**
     * Formats a list of region keys into a space-separated string.
     *
     * @param regions region keys list
     * @return space-separated coordinates string
     */
    private static String formatRegions(final List<DebugRegionKey> regions) {
        return regions.stream()
                .map(region -> region.x() + "," + region.z())
                .collect(Collectors.joining(" "));
    }

    /**
     * Formats a list of payload watch targets into a pipe-separated string.
     *
     * @param payloads payload watch targets list
     * @return pipe-separated targets string
     */
    private static String formatPayloads(final List<PayloadWatchTarget> payloads) {
        return payloads.stream()
                .map(PayloadWatchTarget::describe)
                .collect(Collectors.joining(" | "));
    }
}
