package com.election.ai.model;

/**
 * One row of the official "List of Polling Stations": where the station is and which polling
 * areas (streets, wards, villages) vote there.
 *
 * @param psNo     polling-station number, e.g. "12" or "12A" (auxiliary); matches the Form 20 serial
 * @param location name of the building / locality
 * @param areas    the polling areas served, as printed
 * @param type     "All Voters", "Men only" or "Women only", when printed
 */
public record PollingStationInfo(String psNo, String location, String areas, String type) {
}
