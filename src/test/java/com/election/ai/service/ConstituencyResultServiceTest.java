package com.election.ai.service;

import com.election.ai.model.CandidateResult;
import com.election.ai.model.Constituency;
import com.election.ai.model.ConstituencyResult;
import com.election.ai.model.Form20Sheet;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstituencyResultServiceTest {

    private static final Constituency GUMMIDIPOONDI = new Constituency(1, "Gummidipoondi", null, 1, "TIRUVALLUR",
            "https://www.elections.tn.gov.in/Form20_TNLA2026/dt1/AC001.pdf");

    private static Form20Sheet sheet(Integer expected) throws Exception {
        return Form20PdfParser.parse(new ClassPathResource("form20/AC001.pdf").getContentAsByteArray(), expected);
    }

    private static Form7AClient.Result form7a() throws Exception {
        try (InputStream in = new ClassPathResource("form7a/AC001.html").getInputStream()) {
            return Form7AClient.parse(Jsoup.parse(in, "UTF-8", "https://erolls.tn.gov.in/"));
        }
    }

    private static ConstituencyResultService service() {
        return new ConstituencyResultService(new ConstituencyService("http://unused", 1000),
                new Form7AClient("http://unused", 1000), 1000);
    }

    @Test
    void fillsMissingPartiesFromForm7A() throws Exception {
        Form7AClient.Result web = form7a();
        ConstituencyResult r = service().build(GUMMIDIPOONDI, sheet(web.candidates().size()), web, null);

        assertEquals(List.of(), r.warnings());
        assertEquals(344, r.pollingStations().size());
        CandidateResult dmk = r.candidates().get(0);
        assertEquals("T.J.GOVINDARAJAN", dmk.name());
        assertEquals("Dravida Munnetra Kazhagam", dmk.party());
        assertEquals("Rising sun", dmk.symbol());
        assertEquals(ConstituencyResultService.FORM7A_SOURCE, dmk.partySource());
        assertEquals(62492, dmk.totalVotes());

        CandidateResult winner = r.candidates().stream().filter(CandidateResult::winner).findFirst().orElseThrow();
        assertEquals("S.VIJAYAKUMAR", winner.name());
        assertEquals("Tamilaga Vettri Kazhagam", winner.party());
        assertEquals(94320, winner.totalVotes());
        assertEquals(93820, winner.evmVotes());
        assertEquals(500, winner.postalVotes());

        // Form 20 prints "R.SRIDAR. B.L.," while Form 7A has "R.SRIDAR. B.L."; the official name wins
        assertEquals("R.SRIDAR. B.L.", r.candidates().get(3).name());
        assertEquals("R.SRIDAR. B.L.,", r.candidates().get(3).nameOnForm20());
    }

    @Test
    void keepsForm20DataWhenPartyLookupFails() throws Exception {
        ConstituencyResult r = service().build(GUMMIDIPOONDI, sheet(null), null, "site unreachable");

        assertEquals(15, r.candidates().size());
        assertEquals("T.J.GOVINDARAJAN", r.candidates().get(0).name());
        assertNull(r.candidates().get(0).party());
        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("site unreachable"));
    }
}
