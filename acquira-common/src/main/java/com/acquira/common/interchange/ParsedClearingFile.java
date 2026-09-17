package com.acquira.common.interchange;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The result of parsing one BASE II clearing file: the framed records, the
 * logical transactions, and the batch structure (header / batches+trailers /
 * file trailer). Populated by {@link BaseIIClearingParser}; validated by
 * {@link BaseIIValidator}.
 */
public class ParsedClearingFile {

    /** A batch = the transactions between two boundaries, closed by a TC 91 trailer. */
    public static class Batch {
        public int index;                                   // 1-based batch ordinal
        public final List<ClearingTransaction> transactions = new ArrayList<>();
        public int draftTcrCount;                           // physical TCRs of draft transactions in this batch
        public ClearingRecord trailer;                      // the TC 91 record, or null if missing
    }

    public String fileName;
    public String direction;        // OUTGOING | INCOMING
    public String format = "CTF";   // CTF | ITF
    public int recordBytes = 168;

    public ClearingRecord header;   // TC 90, or null (header is optional)
    public ClearingRecord fileTrailer; // TC 92, or null if missing

    public final List<ClearingRecord> records = new ArrayList<>();       // every physical TCR, in order
    public final List<ClearingTransaction> transactions = new ArrayList<>();
    public final List<Batch> batches = new ArrayList<>();

    // Header-derived
    public String centerInfoBlock;
    public LocalDate processingDate;
    public LocalDate settlementDate;
    public boolean testFile;

    public long monetaryCount() {
        return transactions.stream().filter(t -> t.monetary).count();
    }
}
