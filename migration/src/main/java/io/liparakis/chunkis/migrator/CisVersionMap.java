package io.liparakis.chunkis.migrator;

import io.liparakis.chunkis.core.model.CisConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * Knows the explicit upgrade edges between historical CIS versions.
 *
 * <p>Execution today is still a decode-and-reencode cycle through core codecs,
 * but keeping the version graph explicit makes it clear which format changes
 * exist and whether a requested source-to-target upgrade path is valid.
 *
 * <p>The edge list is traversed linearly; given the historically small number
 * of CIS format versions this is cheaper than a map in practice.
 *
 * <p><b>Threading:</b> All methods are stateless and safe to call from any thread.
 *
 */
public final class CisVersionMap {

    /**
     * Ordered list of all known CIS format upgrade edges.
     *
     * <p>Edges must be stored in ascending {@code fromVersion} order so that
     * {@link #buildPath} can walk them in a single forward pass.
     */
    private static final List<CisVersionEdge> EDGES = List.of(
            new CisVersionEdge(7, 8, "Expanded dense-section palette width from 8 bits to 12 bits."),
            new CisVersionEdge(8, 9, "Added chunk-level metadata storage for structure starts and references."),
            new CisVersionEdge(9, 10, "Replaced per-payload compressed NBT blobs with raw length-prefixed NBT inside " +
                    "the outer CIS compression stream."),
            new CisVersionEdge(10, 11, "Promoted CIS payloads to authoritative compact chunk snapshots and finalized " +
                    "the unreleased v11 section codec.")
    );

    private CisVersionMap() {
        throw new AssertionError("Utility class");
    }

    /**
     * Returns the newest CIS version supported by the current runtime.
     *
     * @return the latest CIS format version
     */
    public static int latestVersion() {
        return CisConstants.VERSION;
    }

    /**
     * Builds the ordered migration path required to upgrade a chunk from one CIS
     * version to another.
     *
     * @param fromVersion source CIS version
     * @param toVersion   target CIS version
     * @return the explicit list of migration steps to execute; empty if versions are equal
     * @throws IllegalArgumentException if the requested path is a downgrade or if
     *                                  no supported upgrade route exists
     */
    public static CisVersionPath plan(final int fromVersion, final int toVersion) {
        if (fromVersion > toVersion) {
            throw new IllegalArgumentException(
                    "Downgrades are not supported: " + fromVersion + " -> " + toVersion);
        }
        if (fromVersion == toVersion) {
            return new CisVersionPath(fromVersion, toVersion, List.of());
        }
        return new CisVersionPath(fromVersion, toVersion, buildPath(fromVersion, toVersion));
    }

    /**
     * Traverses {@link #EDGES} to assemble an ordered step list from
     * {@code fromVersion} to {@code toVersion}.
     *
     * <p>Each iteration looks up the unique outgoing edge from the current
     * version. If no edge exists, or the next hop would overshoot the target,
     * the path is considered invalid and an exception is thrown.
     *
     * @param fromVersion source version to start from
     * @param toVersion   target version to reach
     * @return an immutable list of migration edges covering the full range
     * @throws IllegalArgumentException if no valid path exists
     */
    private static List<CisVersionEdge> buildPath(final int fromVersion, final int toVersion) {
        final List<CisVersionEdge> steps = new ArrayList<>();
        int current = fromVersion;

        while (current < toVersion) {
            final CisVersionEdge edge = findEdgeFrom(current);
            if (edge == null || edge.toVersion() > toVersion) {
                throw new IllegalArgumentException(
                        "No CIS migration path available from version " + fromVersion + " to " + toVersion);
            }
            steps.add(edge);
            current = edge.toVersion();
        }

        return List.copyOf(steps);
    }

    /**
     * Returns the direct upgrade edge that starts at {@code version}, or
     * {@code null} if no such edge is registered.
     *
     * @param version source version to look up
     * @return the matching {@link CisVersionEdge}, or {@code null}
     */
    private static CisVersionEdge findEdgeFrom(final int version) {
        for (final CisVersionEdge edge : EDGES) {
            if (edge.fromVersion() == version) {
                return edge;
            }
        }
        return null;
    }
}
