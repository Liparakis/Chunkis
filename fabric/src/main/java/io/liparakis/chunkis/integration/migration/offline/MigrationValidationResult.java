package io.liparakis.chunkis.integration.migration.offline;

/**
 * Result of validating a migrated chunk against its expected authoritative snapshot.
 *
 * @param valid         true when validation passed
 * @param failureCode   short machine-readable failure identifier when unsuccessful
 * @param details       human-readable diagnostic details
 * @param expectedCount expected item count for count-based mismatches, or {@code 0} when unused
 * @param actualCount   actual item count for count-based mismatches, or {@code 0} when unused
 */
public record MigrationValidationResult(
        boolean valid,
        String failureCode,
        String details,
        int expectedCount,
        int actualCount
) {

    /**
     * Creates a successful validation result with no diagnostics attached.
     *
     * @return successful validation marker
     */
    public static MigrationValidationResult success() {
        return new MigrationValidationResult(true, null, null, 0, 0);
    }

    /**
     * Creates a failed validation result with a code and human-readable detail string.
     *
     * @param code    machine-readable failure identifier
     * @param details human-readable diagnostic detail
     * @return failed validation result
     */
    public static MigrationValidationResult failure(final String code, final String details) {
        return new MigrationValidationResult(false, code, details, 0, 0);
    }
}
