package com.election.ai.service;

import com.election.ai.model.CandidateResult;
import com.election.ai.model.Constituency;
import com.election.ai.model.ConstituencyResult;
import com.election.ai.model.Form20Sheet;
import com.election.ai.model.Form7ACandidate;
import com.election.ai.model.PollingStation;
import com.election.ai.model.VoteCounts;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Builds the result for one constituency when its Form 20 link is clicked:
 * download and parse the Form 20 PDF, then fill in each candidate's party. Form 20 sheets do
 * not print parties, so a missing party is looked up on the web in the official Form 7A list
 * of contesting candidates, matched by ballot serial number (the Form 20 column order).
 */
@Service
public class ConstituencyResultService {

    private static final Logger log = LoggerFactory.getLogger(ConstituencyResultService.class);
    static final String FORM7A_SOURCE = "Form 7A (web)";

    private final ConstituencyService constituencies;
    private final Form7AClient form7a;
    private final DataSnapshotStore snapshots;
    private final int timeoutMillis;
    private final Map<Integer, ConstituencyResult> cache = new ConcurrentHashMap<>();

    public ConstituencyResultService(
            ConstituencyService constituencies,
            Form7AClient form7a,
            DataSnapshotStore snapshots,
            @Value("${form20.timeout-millis:30000}") int timeoutMillis) {
        this.constituencies = constituencies;
        this.form7a = form7a;
        this.snapshots = snapshots;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * The saved copy of the official data is used when there is one; {@code refresh} reads the
     * live sites instead, falling back to the saved copy if they cannot be reached.
     */
    public ConstituencyResult result(int acNumber, boolean refresh) throws IOException {
        if (!refresh) {
            ConstituencyResult cached = cache.get(acNumber);
            if (cached != null) {
                return cached;
            }
        }
        Constituency c = constituencies.find(acNumber)
                .orElseThrow(() -> new NoSuchElementException("No constituency numbered " + acNumber));
        Optional<DataSnapshotStore.Snapshot> saved = snapshots.find(acNumber);
        ConstituencyResult result;
        if (saved.isPresent() && !refresh) {
            result = fromSnapshot(c, saved.get());
        } else {
            try {
                result = live(c);
            } catch (Form20PdfParser.NoTextException e) {
                throw e;
            } catch (IOException e) {
                if (saved.isEmpty()) {
                    throw e;
                }
                log.warn("Live sites unreachable for AC {} ({}); using saved data", acNumber, e.toString());
                result = fromSnapshot(c, saved.get());
            }
        }
        cache.put(acNumber, result);
        return result;
    }

    private ConstituencyResult fromSnapshot(Constituency c, DataSnapshotStore.Snapshot s) throws IOException {
        if (s.form20() == null) {
            if (DataSnapshotStore.SCANNED.equals(s.form20Error())) {
                throw new Form20PdfParser.NoTextException("This Form 20 PDF is a scanned image with no text layer");
            }
            throw new IOException(s.form20Error());
        }
        return build(c, s.form20(), s.form7a(), s.form7aError());
    }

    private ConstituencyResult live(Constituency c) throws IOException {
        int acNumber = c.number();
        // Form 7A first: its candidate count tells the parser how many vote columns to expect.
        Form7AClient.Result candidates = null;
        String form7aError = null;
        try {
            candidates = form7a.fetch(acNumber);
        } catch (IOException | RuntimeException e) {
            log.warn("Form 7A lookup failed for AC {}: {}", acNumber, e.toString());
            form7aError = e.getMessage();
        }

        byte[] pdf = Jsoup.connect(c.form20PdfUrl())
                .userAgent("Mozilla/5.0 (election-ai)")
                .timeout(timeoutMillis)
                .ignoreContentType(true)
                .maxBodySize(0)
                .execute()
                .bodyAsBytes();
        Form20Sheet sheet = Form20PdfParser.parse(pdf, candidates == null ? null : candidates.candidates().size());
        return build(c, sheet, candidates, form7aError);
    }

    /**
     * @param web      the Form 7A list, or null if it could not be fetched
     * @param webError why the Form 7A list is missing, if it is
     */
    ConstituencyResult build(Constituency c, Form20Sheet sheet, Form7AClient.Result web, String webError) {
        List<String> warnings = new ArrayList<>();
        int n = sheet.candidateCount();

        // Form 20 has no party column, so parties start empty and are filled from Form 7A.
        String[] party = new String[n];
        String[] symbol = new String[n];
        String[] partySource = new String[n];
        String[] name = sheet.candidateNames().stream().map(s -> s.isBlank() ? null : s).toArray(String[]::new);

        if (Arrays.stream(party).anyMatch(p -> p == null)) {
            if (web == null) {
                warnings.add("Party names could not be fetched from the Form 7A list"
                        + (webError == null ? "" : ": " + webError));
            } else {
                if (web.candidates().size() != n) {
                    warnings.add("Form 20 has " + n + " candidate columns but Form 7A lists "
                            + web.candidates().size() + " candidates; matched by serial number");
                }
                for (Form7ACandidate fc : web.candidates()) {
                    int i = fc.serial() - 1;
                    if (i < 0 || i >= n) {
                        continue;
                    }
                    name[i] = fc.name(); // the official spelling; Form 20 headers wrap and truncate
                    if (party[i] == null && fc.party() != null) {
                        party[i] = fc.party();
                        symbol[i] = fc.symbol();
                        partySource[i] = FORM7A_SOURCE;
                    }
                }
            }
        }

        List<PollingStation> stations = sheet.pollingStations();
        VoteCounts stationSum = sumOfStations(stations, n);
        VoteCounts evm = sheet.evmTotal();
        if (evm == null) {
            evm = stationSum;
            warnings.add("The sheet has no \"Total EVM votes\" row; EVM totals are the sum of the polling-station rows");
        } else if (!evm.equals(stationSum)) {
            warnings.add("The polling-station rows add up to " + stationSum.totalValid()
                    + " valid votes but the sheet's EVM total is " + evm.totalValid()
                    + "; some rows may not have been read");
        }
        VoteCounts postal = sheet.postalTotal();
        VoteCounts grand = sheet.grandTotal();
        if (grand == null) {
            grand = postal == null ? evm : add(evm, postal);
            warnings.add(postal == null
                    ? "The sheet has no postal-ballot or \"Total votes polled\" row; totals cover EVM votes only"
                    : "The sheet has no \"Total votes polled\" row; totals are EVM + postal");
        }

        List<String> duplicates = stations.stream()
                .collect(Collectors.groupingBy(PollingStation::serialNo, java.util.LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).toList();
        if (!duplicates.isEmpty()) {
            warnings.add("Polling station serial numbers listed more than once: " + String.join(", ", duplicates));
        }
        List<String> unreadable = sheet.unreadableRows();
        if (!unreadable.isEmpty()) {
            warnings.add(unreadable.size() + " row(s) did not add up and were left out: "
                    + String.join(" | ", unreadable.subList(0, Math.min(5, unreadable.size())))
                    + (unreadable.size() > 5 ? " ..." : ""));
        }

        int validTotal = grand.totalValid();
        int best = 0;
        for (int i = 1; i < n; i++) {
            if (grand.candidateVotes().get(i) > grand.candidateVotes().get(best)) {
                best = i;
            }
        }
        List<CandidateResult> candidates = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int total = grand.candidateVotes().get(i);
            candidates.add(new CandidateResult(
                    i + 1,
                    name[i] != null ? name[i] : "Candidate " + (i + 1),
                    sheet.candidateNames().get(i),
                    party[i],
                    symbol[i],
                    partySource[i],
                    evm.candidateVotes().get(i),
                    postal == null ? 0 : postal.candidateVotes().get(i),
                    total,
                    validTotal == 0 ? 0 : 100.0 * total / validTotal,
                    i == best));
        }
        return new ConstituencyResult(c, sheet.acTitle(), sheet.totalElectors(), candidates,
                stations, evm, postal, grand, warnings, Instant.now());
    }

    private static VoteCounts sumOfStations(List<PollingStation> stations, int n) {
        return stations.stream().map(PollingStation::votes)
                .reduce(new VoteCounts(java.util.Collections.nCopies(n, 0), 0, 0, 0, 0, 0),
                        ConstituencyResultService::add);
    }

    private static VoteCounts add(VoteCounts a, VoteCounts b) {
        List<Integer> votes = new ArrayList<>(a.candidateVotes().size());
        for (int i = 0; i < a.candidateVotes().size(); i++) {
            votes.add(a.candidateVotes().get(i) + b.candidateVotes().get(i));
        }
        return new VoteCounts(votes, a.totalValid() + b.totalValid(), a.rejected() + b.rejected(),
                a.nota() + b.nota(), a.total() + b.total(), a.tendered() + b.tendered());
    }
}
