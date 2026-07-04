package io.liparakis.chunkis.migrator;

import java.util.List;

/**
 * Represents the ordered set of version upgrades required to reach a target CIS
 * format.
 *
 * @param fromVersion starting version
 * @param toVersion   target version
 * @param steps       ordered upgrade steps
 */
public record CisVersionPath(int fromVersion, int toVersion, List<CisVersionEdge> steps) {

    /**
     * Returns {@code true} when the path contains no upgrade steps.
     *
     * @return {@code true} if the source and target versions are already equal
     */
    public boolean isNoOp() {
        return steps.isEmpty();
    }
}
