package com.election.ai.model;

import java.util.List;

/**
 * A constituency's Form 20 votes joined with its list of polling stations, so votes can be
 * looked up by polling area.
 *
 * @param result      the Form 20 result (candidates, parties, totals)
 * @param stations    every Form 20 polling station, with its building and areas where known
 * @param matched     how many of {@code stations} were found in the polling station list
 * @param listPdfUrl  the polling station list PDF used, or null if it could not be read
 * @param warnings    anything the reader should know (unreadable list, unmatched stations)
 */
public record PollingAreaResult(ConstituencyResult result, List<AreaStation> stations, int matched,
                                String listPdfUrl, List<String> warnings) {
}
