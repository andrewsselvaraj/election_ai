package com.election.ai.service;

import com.election.ai.model.PollingStationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PollingStationListParserTest {

    private static PollingStationListParser.Result parse(String file) throws Exception {
        return PollingStationListParser.parse(new ClassPathResource("pslist/" + file).getContentAsByteArray());
    }

    private static Map<String, PollingStationInfo> byNo(PollingStationListParser.Result r) {
        return r.stations().stream().collect(Collectors.toMap(PollingStationInfo::psNo, Function.identity()));
    }

    /** Rotated pages; cells centred on the number, so a row's text starts above it. */
    @Test
    void readsCentredRowsOnRotatedPages() throws Exception {
        PollingStationListParser.Result r = parse("AC001.pdf");
        assertEquals(1, r.acNumber());
        List<PollingStationInfo> s = r.stations();
        assertEquals(344, s.size());
        for (int i = 0; i < s.size(); i++) {
            PollingStationInfo p = s.get(i);
            assertEquals(String.valueOf(i + 1), p.psNo());
            assertTrue(p.areas().startsWith("[1]"), () -> "areas start with item 1: " + p);
        }
        PollingStationInfo p20 = byNo(r).get("20");
        assertEquals("Grama Sevai Maiyam, West Building, East Facing, Pondavakkam -601202", p20.location());
        assertTrue(p20.areas().startsWith("[1] -Pondavakkam (R.V) and (P), Pondavakkam Colony Ward - 2"));
        assertTrue(p20.areas().endsWith("[3] -Pondavakkam (P), Irular Colony (Ward - 3)"));
        assertEquals("ALL VOTERS", p20.type());
        assertEquals("Panchayat Union Middle School West Building East Facing, Sanaputhur- 601201",
                byNo(r).get("21").location());
    }

    /** A long area list overflowing into the next row is put back by its numbering. */
    @Test
    void keepsOverflowingAreasWithTheirStation() throws Exception {
        PollingStationListParser.Result r = parse("AC151.pdf");
        assertEquals(151, r.acNumber());
        // the last page ends with the officer's signature block, which must not hide its stations
        assertEquals(279, r.stations().size());
        assertEquals("279", r.stations().get(278).psNo());
        Map<String, PollingStationInfo> s = byNo(r);
        assertTrue(s.get("3").areas().endsWith("6.Arasangudi (R.V) And (P) West Street"));
        assertTrue(s.get("4").areas().startsWith("1.Arasangudi (R.V), S.Pudur (P) North Street"));
        assertTrue(s.get("4").areas().endsWith("5.Arasangudi (R.V), S.Pudur (P) Aasari Street"));
        assertTrue(s.get("5").areas().startsWith("1.Arasangudi (R.V), S.Pudur (P) Pooiyar Street"));
        assertEquals("Panchayat Union Elementary School, Building Facing South,Arasangudi - 606123", s.get("3").location());
    }

    /** Areas printed as two sub-columns ("1.Karur (C) | street") are still the areas column. */
    @Test
    void readsAreaSubColumns() throws Exception {
        PollingStationListParser.Result r = parse("AC135.pdf");
        assertEquals(135, r.acNumber());
        assertEquals(283, r.stations().size());
        PollingStationInfo p82 = byNo(r).get("82");
        assertEquals("1.Karur (C) Thiruppur Kumaran Street Ward 7, 2.Karur (C) Kannadasan Street Ward 7", p82.areas());
        assertTrue(p82.location().startsWith("Panchayat Union Elementary School"));
        assertTrue(r.stations().stream().allMatch(p -> PollingStationListParser.startsWithItem(p.areas())));
    }

    @Test
    void psNumberKeepsAuxiliarySuffix() {
        assertEquals("12", PollingStationListParser.psNo(List.of("12", "12")));
        assertEquals("12A", PollingStationListParser.psNo(List.of("15", "12", "A")));
        assertEquals("12A", PollingStationListParser.psNo(List.of("12A")));
    }
}
