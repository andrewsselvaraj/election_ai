package com.election.ai.service;

import com.election.ai.model.Form7ACandidate;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Form7AClientTest {

    private static Document load(String name) throws Exception {
        try (InputStream in = new ClassPathResource("form7a/" + name).getInputStream()) {
            return Jsoup.parse(in, "UTF-8", "https://erolls.tn.gov.in/acwithcandidate_tnla2026/AC_List.aspx");
        }
    }

    @Test
    void findsPostBackTargetByAcNumber() throws Exception {
        Document list = load("AC_List.html");
        assertEquals("ctl00$ContentPlaceHolder1$lv_Candidate$ctrl0$ctl00$lnk_Pc_Name",
                Form7AClient.postBackTarget(list, 1));
        assertEquals("ctl00$ContentPlaceHolder1$lv_Candidate$ctrl233$ctl00$lnk_Pc_Name",
                Form7AClient.postBackTarget(list, 234));
        assertThrows(java.io.IOException.class, () -> Form7AClient.postBackTarget(list, 235));
    }

    @Test
    void parsesCandidatesWithParty() throws Exception {
        Form7AClient.Result r = Form7AClient.parse(load("AC001.html"));
        assertEquals("Gummidipoondi", r.acName());
        assertEquals(15, r.candidates().size());
        assertEquals(new Form7ACandidate(1, "T.J.GOVINDARAJAN", "Dravida Munnetra Kazhagam", "Rising sun"),
                r.candidates().get(0));
        assertEquals(new Form7ACandidate(7, "S.VIJAYAKUMAR", "Tamilaga Vettri Kazhagam", "Whistle"),
                r.candidates().get(6));
        assertEquals("Independent", r.candidates().get(14).party());
    }
}
