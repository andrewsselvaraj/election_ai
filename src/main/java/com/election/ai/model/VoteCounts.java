package com.election.ai.model;

import java.util.List;

/**
 * One line of numbers from a Form 20 sheet: votes per candidate (in ballot order) followed by
 * the five summary columns printed on every row.
 */
public record VoteCounts(
        List<Integer> candidateVotes,
        int totalValid,
        int rejected,
        int nota,
        int total,
        int tendered) {

    /** True when the candidate votes add up to the valid total and valid + rejected + NOTA = total. */
    public boolean consistent() {
        int sum = candidateVotes.stream().mapToInt(Integer::intValue).sum();
        return sum == totalValid && totalValid + rejected + nota == total;
    }
}
