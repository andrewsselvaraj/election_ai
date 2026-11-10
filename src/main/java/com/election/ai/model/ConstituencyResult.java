package com.election.ai.model;

import java.time.Instant;
import java.util.List;

/** Form 20 data for one constituency, merged with candidate party details. */
public record ConstituencyResult(
        Constituency constituency,
        String acTitleOnForm20,
        Integer totalElectors,
        List<CandidateResult> candidates,
        List<PollingStation> pollingStations,
        VoteCounts evmTotal,
        VoteCounts postalTotal,
        VoteCounts grandTotal,
        List<String> warnings,
        Instant loadedAt) {
}
