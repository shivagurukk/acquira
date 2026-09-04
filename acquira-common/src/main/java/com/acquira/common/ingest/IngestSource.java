package com.acquira.common.ingest;

/**
 * Where an ingestion came from.
 *
 * The last two are the reason this ledger exists at all: BACKFILL and
 * BULK_MIGRATION are not Spring Batch jobs, so before the ledger they left no
 * trace anywhere — an operator could not tell that a month had been rewritten.
 */
public enum IngestSource {
    /** Interactive upload through /upload. */
    UPLOAD,
    /** Server File Processor picking a file off disk. */
    SERVER_FILE,
    /** Scheduled external-database pull. */
    DB_PULL,
    /** BackfillIngestionService re-deriving history. */
    BACKFILL,
    /** BulkMigrationService rebuilding summaries. */
    BULK_MIGRATION;

    public static IngestSource parse(String raw) {
        if (raw == null || raw.isBlank()) return UPLOAD;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return UPLOAD;
        }
    }
}
