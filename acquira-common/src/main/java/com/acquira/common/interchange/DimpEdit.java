package com.acquira.common.interchange;

/**
 * One Mastercard DIMP (Data Integrity Monitoring Program) edit definition, as
 * loaded from dimp-edits.json (the Acquirer Clearing 1240 edit set).
 */
public class DimpEdit {
    /** How far Acquira can evaluate this edit from the clearing file alone. */
    public enum Status {
        FILE,      // fully checked from the clearing message
        PARTIAL,   // clearing-side presence/format checked; DE 43 subfield / PDS detail simplified
        AUTH       // a MATCH edit — the auth comparison isn't performed; we show/validate the clearing value
    }

    public String editNumber;
    public String name;
    public String title;
    public String billingCode;
    public String description;
    public Status status = Status.PARTIAL;
    /** The clearing field(s) this edit inspects, for display ("what is there"). */
    public String field;

    public String label() {
        return "Edit " + editNumber + " " + (title != null ? title : name)
                + (billingCode != null ? " [" + billingCode + "]" : "");
    }
}
