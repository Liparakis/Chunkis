package io.liparakis.chunkis.migration.offline;

/**
 * Result of validating a migrated chunk against its expected authoritative snapshot.
 *
 * @param success     true when validation passed
 * @param failureCode short machine-readable failure identifier when unsuccessful
 * @param details     human-readable diagnostic details
 */
public record MigrationValidationResult(boolean ok, String failureCode, String details) {

    private static final MigrationValidationResult SUCCESS = new MigrationValidationResult(true, null, null);

    public static MigrationValidationResult success() {
        return SUCCESS;
    }

    public static MigrationValidationResult failure(final String code, final String details) {
        return new MigrationValidationResult(false, code, details);
    }

    public boolean success() {
        return ok;
    }
}
