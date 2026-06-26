package io.liparakis.chunkis.debug;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class ChunkTraceJsonl {

    private ChunkTraceJsonl() {
        throw new AssertionError("Utility class");
    }

    public static String toJsonLine(final ChunkTraceEvent event) {
        Objects.requireNonNull(event, "event");

        final JsonObject root = new JsonObject();
        root.addProperty("eventId", event.eventId());
        root.addProperty("timestampMillis", event.timestampMillis());
        root.addProperty("threadName", event.threadName());
        root.addProperty("domain", event.domain().name());
        root.addProperty("eventType", event.eventType().name());
        root.addProperty("severity", event.severity().name());
        root.addProperty("reason", event.reason().name());
        root.addProperty("source", event.source());
        root.addProperty("message", event.message());

        if (event.worldId() != null) {
            root.addProperty("worldId", event.worldId());
        }
        if (event.chunkKey() != null) {
            root.addProperty("chunkX", event.chunkKey().x());
            root.addProperty("chunkZ", event.chunkKey().z());
        }
        if (event.regionKey() != null) {
            root.addProperty("regionX", event.regionKey().x());
            root.addProperty("regionZ", event.regionKey().z());
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
            root.addProperty("payloadType", event.payloadWatchTarget().type().name());
            root.addProperty("payloadWorldId", event.payloadWatchTarget().worldId());
            if (event.payloadWatchTarget().blockX() != null) {
                root.addProperty("payloadX", event.payloadWatchTarget().blockX());
                root.addProperty("payloadY", event.payloadWatchTarget().blockY());
                root.addProperty("payloadZ", event.payloadWatchTarget().blockZ());
            }
            if (event.payloadWatchTarget().entityUuid() != null) {
                root.addProperty("payloadUuid", event.payloadWatchTarget().entityUuid());
            }
        }
        if (event.payloadWatchStage() != null) {
            root.addProperty("payloadStage", event.payloadWatchStage());
        }
        if (event.payloadWatchSummary() != null) {
            root.addProperty("payloadSummary", event.payloadWatchSummary());
        }

        return root.toString();
    }

    public static void write(final Path path, final List<ChunkTraceEvent> events) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(events, "events");

        final Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        final StringBuilder builder = new StringBuilder(events.size() * 192);
        for (final ChunkTraceEvent event : events) {
            builder.append(toJsonLine(event)).append('\n');
        }
        Files.writeString(path, builder.toString(), StandardCharsets.UTF_8);
    }
}
