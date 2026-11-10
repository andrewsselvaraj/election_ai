package com.election.ai.model;

/**
 * A candidate with their votes from Form 20 and party details.
 *
 * @param serial       ballot serial number (Form 20 column order)
 * @param name         candidate name (from Form 7A when available, else as printed on Form 20)
 * @param nameOnForm20 name as read from the Form 20 column header
 * @param party        party affiliation, or null if it could not be found
 * @param symbol       election symbol, or null if not known
 * @param partySource  where the party came from ("Form 20" or "Form 7A (web)"), or null
 * @param voteShare    percentage of total valid votes polled
 */
public record CandidateResult(
        int serial,
        String name,
        String nameOnForm20,
        String party,
        String symbol,
        String partySource,
        int evmVotes,
        int postalVotes,
        int totalVotes,
        double voteShare,
        boolean winner) {
}
