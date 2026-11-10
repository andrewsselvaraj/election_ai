package com.election.ai.model;

import java.util.List;

/**
 * Everything read from one Form 20 (final result sheet) PDF.
 *
 * @param acTitle        constituency as printed, e.g. "1-GUMMIDIPOONDI"
 * @param totalElectors  "Total No. of Electors" from the sheet header, or null if not found
 * @param candidateNames candidate names from the column headers, in ballot order
 * @param evmTotal       "Total EVM Votes" row, or null if absent
 * @param postalTotal    "Total Postal Ballot Votes" row, or null if absent
 * @param grandTotal     "Total Votes Polled" row, or null if absent
 * @param unreadableRows rows that looked like polling-station rows but could not be read
 */
public record Form20Sheet(
        String acTitle,
        Integer totalElectors,
        List<String> candidateNames,
        List<PollingStation> pollingStations,
        VoteCounts evmTotal,
        VoteCounts postalTotal,
        VoteCounts grandTotal,
        List<String> unreadableRows) {

    public int candidateCount() {
        return candidateNames.size();
    }
}
