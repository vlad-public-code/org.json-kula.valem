package org.json_kula.valem.persistence.audit;

/**
 * Result of verifying an audit trail's hash chain (see {@link AuditStore#verify}).
 *
 * @param valid               {@code true} if the whole chain is intact
 * @param recordsChecked      number of records examined
 * @param firstBrokenSequence sequence of the first record that failed, or {@code null} when valid
 * @param detail              human-readable explanation of the break, or {@code "ok"} when valid
 */
public record AuditVerification(
        boolean valid,
        long recordsChecked,
        Long firstBrokenSequence,
        String detail) {

    public static AuditVerification valid(long recordsChecked) {
        return new AuditVerification(true, recordsChecked, null, "ok");
    }

    public static AuditVerification broken(long atSequence, long recordsChecked, String detail) {
        return new AuditVerification(false, recordsChecked, atSequence, detail);
    }
}
