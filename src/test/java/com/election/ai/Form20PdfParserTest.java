package com.election.ai;

import com.election.ai.model.Form20Sheet;
import com.election.ai.model.PollingStation;
import com.election.ai.service.Form20PdfParser;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Form20PdfParserTest {

    private static Form20Sheet parse(String name) throws Exception {
        return Form20PdfParser.parse(new ClassPathResource("form20/" + name + ".pdf").getContentAsByteArray());
    }

    @Test
    void parsesGummidipoondi() throws Exception {
        Form20Sheet s = parse("AC001");

        assertEquals("1-GUMMIDIPOONDI", s.acTitle());
        assertEquals(254175, s.totalElectors());
        assertEquals(15, s.candidateCount());
        assertEquals(List.of(), s.unreadableRows());
        assertEquals(344, s.pollingStations().size());
        assertTrue(s.pollingStations().stream().allMatch(p -> p.votes().consistent()),
                "every row's candidate votes add up to its valid total");

        PollingStation first = s.pollingStations().get(0);
        assertEquals("1", first.serialNo());
        assertEquals(170, first.votes().candidateVotes().get(0));
        assertEquals(653, first.votes().totalValid());

        // row 46 shares its line with the "Page 1" footer in plain text extraction
        PollingStation row46 = s.pollingStations().stream()
                .filter(p -> p.serialNo().equals("46")).findFirst().orElseThrow();
        assertEquals(64, row46.votes().candidateVotes().get(0));
        assertEquals(750, row46.votes().totalValid());

        assertEquals(229969, s.evmTotal().totalValid());
        assertEquals(1616, s.postalTotal().totalValid());
        assertEquals(108, s.postalTotal().rejected());
        assertEquals(94320, s.grandTotal().candidateVotes().get(6));
        assertEquals(232630, s.grandTotal().total());

        assertEquals("T.J.GOVINDARAJAN", s.candidateNames().get(0));
        assertEquals("SUDHAKAR.V", s.candidateNames().get(1));
        assertEquals("S.VIJAYAKUMAR", s.candidateNames().get(6));
    }

    @Test
    void parsesPonneri() throws Exception {
        Form20Sheet s = parse("AC002");

        assertEquals("2-PONNERI (SC)", s.acTitle());
        assertEquals(13, s.candidateCount());
        assertEquals(List.of(), s.unreadableRows());
        assertTrue(s.pollingStations().stream().allMatch(p -> p.votes().consistent()));
        assertEquals("327", s.pollingStations().getLast().serialNo());
        assertEquals(224817, s.evmTotal().totalValid());
        assertEquals(226873, s.grandTotal().total());
    }
}
