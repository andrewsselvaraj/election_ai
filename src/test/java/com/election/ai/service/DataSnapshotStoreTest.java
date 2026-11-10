package com.election.ai.service;

import com.election.ai.model.CandidateResult;
import com.election.ai.model.ConstituencyResult;
import com.election.ai.model.PollingAreaResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The app works from the saved copy alone, as it must where the official sites are unreachable. */
class DataSnapshotStoreTest {

    // unreachable on purpose: everything below must come from src/test/resources/snapshot-test/
    private final ConstituencyService constituencies = new ConstituencyService("http://127.0.0.1:9/", 500);
    private final DataSnapshotStore store = new DataSnapshotStore(JsonMapper.builder().build(), "snapshot-test/");
    private final ConstituencyResultService results = new ConstituencyResultService(constituencies,
            new Form7AClient("http://127.0.0.1:9/", 500), store, 500);
    private final PollingAreaService areas = new PollingAreaService(results,
            new PollingStationListClient("http://127.0.0.1:9/", 500), store);

    @Test
    void servesResultsFromSavedData() throws Exception {
        ConstituencyResult r = results.result(1, false);

        assertEquals(344, r.pollingStations().size());
        assertTrue(r.warnings().isEmpty(), r.warnings()::toString);
        CandidateResult winner = r.candidates().stream().filter(CandidateResult::winner).findFirst().orElseThrow();
        assertEquals("S.VIJAYAKUMAR", winner.name());
        assertEquals("Tamilaga Vettri Kazhagam", winner.party());
        assertEquals(94320, winner.totalVotes());
    }

    @Test
    void servesPollingAreasFromSavedData() throws Exception {
        PollingAreaResult a = areas.result(1, false);

        assertEquals(344, a.matched());
        assertTrue(a.stations().get(19).info().areas().contains("Pondavakkam Pudu Colony"));
        assertTrue(a.listPdfUrl().endsWith("/English/AC001.pdf"));
    }

    @Test
    void fallsBackToSavedDataWhenLiveSitesAreUnreachable() throws Exception {
        assertEquals(344, results.result(1, true).pollingStations().size());
    }

    @Test
    void scannedForm20IsStillReportedAsScanned() {
        assertThrows(Form20PdfParser.NoTextException.class, () -> results.result(151, false));
    }
}
