package com.election.ai.model;

/**
 * One Assembly Constituency row from the TN 2026 Form 20 page.
 *
 * @param number       AC number (1..234)
 * @param name         constituency name without the reservation suffix, e.g. "Ponneri"
 * @param reservation  "SC", "ST" or null for general seats
 * @param districtNo   district number as listed on the page
 * @param districtName district name as listed on the page
 * @param form20PdfUrl absolute URL of the Form 20 PDF for this constituency
 */
public record Constituency(
        int number,
        String name,
        String reservation,
        int districtNo,
        String districtName,
        String form20PdfUrl) {

    public String displayName() {
        return reservation == null ? name : name + " (" + reservation + ")";
    }
}
