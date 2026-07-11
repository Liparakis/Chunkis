package io.liparakis.chunkis.debug.config;

import io.liparakis.chunkis.debug.model.ChunkTraceSeverity;
import io.liparakis.chunkis.debug.model.ChunkisDebugDomain;

import java.util.Objects;

/**
 * Global configuration singleton for the Chunkis debug/tracing subsystem.
 *
 * <p>The active {@link ChunkisDebugLevel} controls whether trace events are
 * recorded. {@link #allows} is the hot-path gate; it is called before every
 * event is recorded, so it must stay allocation-free.</p>
 *
 * <p><b>Threading:</b> {@link #level} is {@code volatile} so readers on any
 * thread see writes made by the server-thread configuration command without
 * requiring a lock.</p>
 */
public final class ChunkisDebugConfig {

    /**
     * Current debug level. {@code volatile} because it may be written from a
     * command-handling thread and read from server/IO threads.
     */
    private static volatile ChunkisDebugLevel level = ChunkisDebugLevel.OFF;

    /**
     * Performs chunkis debug config.
     */
    private ChunkisDebugConfig() {
        throw new AssertionError("Utility class");
    }

    /**
     * Returns the currently active debug level.
     *
     * @return current level; never {@code null}
     */
    public static ChunkisDebugLevel level() {
        return level;
    }

    /**
     * Sets the active debug level. Takes effect immediately for all subsequent
     * {@link #allows} calls.
     *
     * @param newLevel new level; must not be {@code null}
     */
    public static void setLevel(final ChunkisDebugLevel newLevel) {
        level = Objects.requireNonNull(newLevel, "newLevel");
    }

    /**
     * Returns {@code true} if an event with the given domain and severity
     * should be recorded under the current debug level.
     *
     * <p>This is the hot-path gate called before every trace event. It must
     * remain allocation-free.</p>
     *
     * <p><b>Note:</b> {@code domain} is accepted for forward compatibility -
     * future level granularity will filter per-domain. Currently it is only
     * null-checked.</p>
     *
     * @param domain   the debug domain of the event; must not be {@code null}
     * @param severity the severity of the event; must not be {@code null}
     * @return {@code true} if the event should be recorded
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean allows(final ChunkisDebugDomain domain, final ChunkTraceSeverity severity) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(severity, "severity");

        return switch (level) {
            case OFF -> false;
            case ERRORS_ONLY -> severity == ChunkTraceSeverity.ERROR;
            case LIFECYCLE, VERBOSE, PARANOID -> true;
        };
    }
}
