package com.election.ai.service;

import com.election.ai.model.AreaStation;
import com.election.ai.model.Constituency;
import com.election.ai.model.ConstituencyResult;
import com.election.ai.model.PollingAreaResult;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PollingAreaServiceTest {

    private static final Constituency GUMMIDIPOONDI = new Constituency(1, "Gummidipoondi", null, 1, "TIRUVALLUR",
            "https://www.elections.tn.gov.in/Form20_TNLA2026/dt1/AC001.pdf");

    private static ConstituencyResult form20() throws Exception {
        var sheet = Form20PdfParser.parse(new ClassPathResource("form20/AC001.pdf").getContentAsByteArray());
        var service = new ConstituencyResultService(new ConstituencyService("http://unused", 1000),
                new Form7AClient("http://unused", 1000),
                new DataSnapshotStore(tools.jackson.databind.json.JsonMapper.builder().build(), "no-data/"), 1000);
        return service.build(GUMMIDIPOONDI, sheet, null, "offline");
    }

    private static PollingStationListClient.Fetched list() throws Exception {
        return new PollingStationListClient.Fetched(
                PollingStationListParser.parse(new ClassPathResource("pslist/AC001.pdf").getContentAsByteArray()),
                "https://www.elections.tn.gov.in/PSLIST_06042026/dt1/English/AC001.pdf");
    }

    @Test
    void joinsVotesWithPollingAreasByStationNumber() throws Exception {
        PollingAreaResult a = PollingAreaService.join(1, form20(), list(), null);

        assertEquals(344, a.stations().size());
        assertEquals(344, a.matched());
        assertTrue(a.warnings().isEmpty(), a.warnings()::toString);
        AreaStation s46 = a.stations().get(45);
        assertEquals("46", s46.serialNo());
        assertTrue(s46.info().areas().startsWith("[1]"));
        AreaStation s20 = a.stations().get(19);
        assertTrue(s20.info().areas().contains("Pondavakkam Pudu Colony"));
        assertEquals(s20.votes().totalValid(), s20.votes().candidateVotes().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void refusesAnotherConstituencysList() throws Exception {
        PollingAreaResult a = PollingAreaService.join(2, form20(), list(), null);

        assertEquals(0, a.matched());
        assertNull(a.listPdfUrl());
        assertNull(a.stations().get(0).info());
        assertTrue(a.warnings().get(0).contains("constituency 1"));
    }

    @Test
    void keepsVotesWhenListIsUnreadable() throws Exception {
        PollingAreaResult a = PollingAreaService.join(1, form20(), null, "scanned");

        assertEquals(344, a.stations().size());
        assertEquals(0, a.matched());
        assertEquals(1, a.warnings().size());
    }

    @Test
    void stationNumbersMatchLoosely() {
        assertEquals("12A", PollingAreaService.key("12 a"));
        assertEquals("12", PollingAreaService.key("012"));
        assertEquals("12A", PollingAreaService.key("12-A"));
        assertEquals("0", PollingAreaService.key("0"));
    }

    @Test
    void findsTheEnglishPdfForAConstituency() throws Exception {
        var doc = Jsoup.parse("""
                <a href="PSLIST_06042026/dt1/Tamil/AC001.pdf">t</a>
                <a href="PSLIST_06042026/dt1/English/AC001.pdf">e</a>
                <a href="PSLIST_06042026/dt30/English/AC234.pdf">e</a>""", "https://www.elections.tn.gov.in/");
        assertEquals("https://www.elections.tn.gov.in/PSLIST_06042026/dt1/English/AC001.pdf",
                PollingStationListClient.pdfUrl(doc, 1));
        assertEquals("https://www.elections.tn.gov.in/PSLIST_06042026/dt30/English/AC234.pdf",
                PollingStationListClient.pdfUrl(doc, 234));
        assertThrows(IOException.class, () -> PollingStationListClient.pdfUrl(doc, 2));
    }
}
