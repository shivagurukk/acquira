package com.acquira.service;

/**
 * REMOVED: ScheduledDbPullJob was an auto-scheduled ingestion job that ran
 * at 2:00 AM daily. It has been removed because:
 *
 * 1. The scheduling will be managed externally (user's own scheduler)
 * 2. It lacked tenant scoping — queries ran without RLS context, risking
 *    cross-tenant data contamination
 * 3. The UniversalDatabaseClient connections were not tenant-isolated
 *
 * If automatic ingestion is needed in the future, use the batch job
 * endpoints in BatchJobController with proper tenant context.
 *
 * This file is kept as documentation. Safe to delete.
 */
