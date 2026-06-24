package io.liparakis.chunkis.debug;

import java.util.Objects;

public final class ChunkisDebugConfig {

    private static volatile ChunkisDebugLevel level = ChunkisDebugLevel.OFF;

    private ChunkisDebugConfig() {
        throw new AssertionError("Utility class");
    }

    public static ChunkisDebugLevel level() {
        return level;
    }

    public static void setLevel(final ChunkisDebugLevel newLevel) {
        level = Objects.requireNonNull(newLevel, "newLevel");
    }

    public static boolean isEnabled() {
        return level != ChunkisDebugLevel.OFF;
    }

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
