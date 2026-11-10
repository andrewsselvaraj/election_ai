package com.election.ai.model;

/**
 * One polling-station row of a Form 20 sheet.
 *
 * @param serialNo "Serial No. of Polling Station" as printed (usually a number, may carry a suffix)
 */
public record PollingStation(String serialNo, VoteCounts votes) {
}
