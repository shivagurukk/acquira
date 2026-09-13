package com.acquira.common.interchange;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * One logical BASE II transaction: a Transaction Code (TC) plus its component
 * records (TCRs), starting with TCR 0. Decoded summary fields are populated by
 * the parser from TCR 0 / TCR 1 for display and (later) reconciliation.
 */
public class ClearingTransaction {
    public long seqInFile;              // 1-based ordinal within the file
    public Integer batchNumber;         // index of the batch this belongs to
    public String tc;                   // e.g. 05
    public String qualifier;            // TCR0 pos 3
    public boolean monetary = true;
    public final List<ClearingRecord> tcrs = new ArrayList<>();

    // Decoded (TCR 0 unless noted). PAN is NEVER stored in full.
    public String accountMasked;        // first6..last4
    public String acquirerRefNumber;    // ARN (pos 27-49)
    public String acquirerBid;          // pos 50-57
    public String purchaseDate;         // MMDD as read
    public BigDecimal destinationAmount;
    public String destinationCcy;
    public BigDecimal sourceAmount;
    public String sourceCcy;
    public String merchantName;
    public String merchantCountry;
    public String mcc;
    public String feeProgramIndicator;  // FPI (TCR 1 pos 76-78)
    public String reimbursementAttr;    // pos 168

    /** Comma-joined TCR sequence numbers present, e.g. "0,1,5,7". */
    public String tcrPresent() {
        StringBuilder sb = new StringBuilder();
        for (ClearingRecord r : tcrs) {
            if (sb.length() > 0) sb.append(',');
            sb.append(r.tcr.trim());
        }
        return sb.toString();
    }

    public boolean hasTcr(String seq) {
        for (ClearingRecord r : tcrs) if (seq.equals(r.tcr.trim())) return true;
        return false;
    }
}
