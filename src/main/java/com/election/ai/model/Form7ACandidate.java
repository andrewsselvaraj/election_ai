package com.election.ai.model;

/**
 * One row of the official contesting-candidates list (Form 7A) published by CEO Tamil Nadu.
 *
 * @param serial ballot serial number, which is also the candidate's column order in Form 20
 */
public record Form7ACandidate(int serial, String name, String party, String symbol) {
}
