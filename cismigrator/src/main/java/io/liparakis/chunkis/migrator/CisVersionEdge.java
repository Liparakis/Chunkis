package io.liparakis.chunkis.migrator;

/**
 * Describes a supported one-step CIS format upgrade.
 *
 * <p>
 * The migrator keeps upgrade steps explicit instead of assuming every older
 * version can be rewritten blindly. This gives callers a reliable map of which
 * historical versions are intentionally supported.
 *
 * @param fromVersion source CIS version
 * @param toVersion   target CIS version
 * @param description short explanation of the format change
 * @author Liparakis
 * @version 1.0
 *
 */
public record CisVersionEdge(int fromVersion, int toVersion, String description) {

}
