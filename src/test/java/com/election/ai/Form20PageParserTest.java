package com.election.ai;

import com.election.ai.model.Constituency;
import com.election.ai.service.Form20PageParser;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class Form20PageParserTest {

    @Test
    void parsesAll234ConstituenciesFromSnapshot() throws Exception {
        List<Constituency> list;
        try (InputStream in = new ClassPathResource("snapshot/Form20_TNLA2026.html").getInputStream()) {
            list = Form20PageParser.parse(
                    Jsoup.parse(in, "UTF-8", "https://www.elections.tn.gov.in/Form20_TNLA2026.aspx"));
        }

        assertEquals(234, list.size());
        // every AC number 1..234 appears exactly once
        assertEquals(IntStream.rangeClosed(1, 234).boxed().toList(),
                list.stream().map(Constituency::number).sorted().toList());

        Constituency first = list.stream().filter(c -> c.number() == 1).findFirst().orElseThrow();
        assertEquals("Gummidipoondi", first.name());
        assertNull(first.reservation());
        assertEquals(1, first.districtNo());
        assertEquals("TIRUVALLUR", first.districtName());
        assertEquals("https://www.elections.tn.gov.in/Form20_TNLA2026/dt1/AC001.pdf", first.form20PdfUrl());

        Constituency ponneri = list.stream().filter(c -> c.number() == 2).findFirst().orElseThrow();
        assertEquals("Ponneri", ponneri.name());
        assertEquals("SC", ponneri.reservation());
    }
}
