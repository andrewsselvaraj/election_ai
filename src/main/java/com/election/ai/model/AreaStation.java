package com.election.ai.model;

/**
 * One polling station with its votes (from Form 20) and its building and polling areas (from the
 * official list of polling stations). {@code info} is null when the list has no matching station.
 */
public record AreaStation(String serialNo, PollingStationInfo info, VoteCounts votes) {
}
