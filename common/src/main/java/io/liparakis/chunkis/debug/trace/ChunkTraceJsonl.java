package io.liparakis.chunkis.debug.trace;

import com.google.gson.JsonObject;
import io.liparakis.chunkis.debug.model.ChunkTraceEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Serializes {@link ChunkTraceEvent} instances to JSONL (newline-delimited JSON).
 *
 * <p>Each event is serialized as a single JSON object on its own line. Optional
 * event fields are omitted when {@code null} so the output remains compact.</p>
 */
public final class ChunkTraceJsonl {

    /**
     * Estimated average JSON line length in bytes, used to pre-size the
     * {@link StringBuilder} in {@link #write}.
     */
    private static final int ESTIMATED_LINE_BYTES = 192;

    private ChunkTraceJsonl() {
        throw new AssertionError("Utility class");
    }

    /**
     * Serializes one event to a JSON object string (no trailing newline).
     *
     * <p>Required fields are always present. Optional fields ({@code worldId},
     * {@code chunkKey}, {@code regionKey}, {@code operationId}, {@code dirtyState},
     * {@code byteSize}, payload watch fields) are included only when non-{@code null}.</p>
     *
     * @param event the event to serialize; must not be {@code null}
     * @return a single-line JSON string
     */
    public static String toJsonLine(final ChunkTraceEvent event) {
        Objects.requireNonNull(event, "event");

        final JsonObject root = new JsonObject();
        root.addProperty("eventId", event.eventId());
        root.addProperty("timestampMillis", event.timestampMillis());
        root.addProperty("threadName", event.threadName());
        root.addProperty("domain",
                event.domain()
                        .name());
        root.addProperty("eventType",
                event.eventType()
                        .name());
        root.addProperty("severity",
                event.severity()
                        .name());
        root.addProperty("reason",
                event.reason()
                        .name());
        root.addProperty("source", event.source());
        root.addProperty("message", event.message());

        if (event.worldId() != null) {
            root.addProperty("worldId", event.worldId());
        }
        if (event.chunkKey() != null) {
            root.addProperty("chunkX",
                    event.chunkKey()
                            .x());
            root.addProperty("chunkZ",
                    event.chunkKey()
                            .z());
        }
        if (event.regionKey() != null) {
            root.addProperty("regionX",
                    event.regionKey()
                            .x());
            root.addProperty("regionZ",
                    event.regionKey()
                            .z());
        }
        if (event.operationId() != null) {
            root.addProperty("operationId", event.operationId());
        }
        if (event.dirtyState() != null) {
            root.addProperty("dirtyState", event.dirtyState());
        }
        if (event.byteSize() != null) {
            root.addProperty("byteSize", event.byteSize());
        }

        if (event.payloadWatchTarget() != null) {
            addPayloadWatchFields(root, event);
        }
        if (event.payloadWatchStage() != null) {
            root.addProperty("payloadStage", event.payloadWatchStage());
        }
        if (event.payloadWatchSummary() != null) {
            root.addProperty("payloadSummary", event.payloadWatchSummary());
        }

        return root.toString();
    }

    /**
     * Writes a list of events to a JSONL file, creating parent directories as
     * needed.
     *
     * <p>All events are serialized into a single buffer before the file is
     * written, so the write is one {@link Files#writeString} call.</p>
     *
     * @param path   destination file; parent directories are created if absent
     * @param events events to write in order; must not be {@code null}
     * @throws IOException if the file cannot be written
     */
    public static void write(final Path path, final List<ChunkTraceEvent> events) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(events, "events");

        final Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        final StringBuilder sb = new StringBuilder(events.size() * ESTIMATED_LINE_BYTES);
        for (final ChunkTraceEvent event : events) {
            sb.append(toJsonLine(event))
                    .append('\n');
        }
        Files.writeString(path, sb, StandardCharsets.UTF_8);
    }

    /**
     * Populates payload-watch fields in {@code root} from
     * {@code event.payloadWatchTarget()}.
     *
     * <p>Only called when {@code payloadWatchTarget} is non-null. Block coordinate
     * fields are written only when the target has coordinates; entity UUID is
     * written only when present.</p>
     *
     * @param root  JSON object to mutate
     * @param event source event with a non-null payload watch target
     */
    private static void addPayloadWatchFields(final JsonObject root, final ChunkTraceEvent event) {
        final var target = event.payloadWatchTarget();
        root.addProperty("payloadType",
                target.type()
                        .name());
        root.addProperty("payloadWorldId", target.worldId());
        if (target.blockX() != null) {
            root.addProperty("payloadX", target.blockX());
            root.addProperty("payloadY", target.blockY());
            root.addProperty("payloadZ", target.blockZ());
        }
        if (target.entityUuid() != null) {
            root.addProperty("payloadUuid", target.entityUuid());
        }
    }
}