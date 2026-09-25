package com.acquira.core.service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * What the digest knows about one tenant-day's feeds at the moment it is
 * gated, rendered and sent.
 *
 * Phase 1 finding I-1: the email used to print "DCC income 0.000" whether the
 * DCC file was loaded or simply never arrives for that bank, so a reader could
 * not tell a real zero from a missing feed. The gate already computed
 * per-feed presence — this record carries that knowledge through to the
 * renderer (completeness banner, "not loaded" rows) and onto the dispatch
 * ledger ({@code feeds_included}) so the audit copy says what it covered.
 */
public record DigestFeedCoverage(List<FeedState> feeds, Long rowsFact, Timestamp lastLoadedAt,
                                 boolean ingestRunning) {

    /** Feed keys, in the order they are gated and shown. */
    public static final String MERCHANT = "MERCHANT";
    public static final String TRX = "TRX";
    public static final String DCC = "DCC";
    public static final String RENTAL = "RENTAL";

    /**
     * One feed: whether the tenant's config requires it (the gate waits for it)
     * and whether it is in fact present for the day (data rows, or a completed
     * load covering the day — see the presence checks in DigestScheduler).
     */
    public record FeedState(String key, String label, boolean required, boolean present) {}

    public static DigestFeedCoverage none() {
        return new DigestFeedCoverage(new ArrayList<>(), null, null, false);
    }

    public FeedState get(String key) {
        if (feeds == null) return null;
        for (FeedState f : feeds) if (f.key().equals(key)) return f;
        return null;
    }

    /** True when the feed's data is in for the day (unknown feed = false). */
    public boolean present(String key) {
        FeedState f = get(key);
        return f != null && f.present();
    }

    /** Keys of every present feed, e.g. {@code TRX+DCC}; stamped on the ledger. */
    public String included() {
        List<String> keys = new ArrayList<>();
        if (feeds != null) for (FeedState f : feeds) if (f.present()) keys.add(f.key());
        return String.join("+", keys);
    }

    /** Keys of every required feed that is still missing, e.g. {@code DCC+RENTAL}. */
    public String missingRequired() {
        List<String> keys = new ArrayList<>();
        if (feeds != null) for (FeedState f : feeds) if (f.required() && !f.present()) keys.add(f.key());
        return String.join("+", keys);
    }
}
