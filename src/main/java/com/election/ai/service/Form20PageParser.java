package com.election.ai.service;

import com.election.ai.model.Constituency;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the Form 20 table on https://www.elections.tn.gov.in/Form20_TNLA2026.aspx.
 *
 * The table has three columns (District No., District Name, AC). The first row of each
 * district carries the district cells with a rowspan; following rows only have the AC cell.
 */
public final class Form20PageParser {

    // "2. Ponneri (SC)" -> number=2, name="Ponneri", reservation="SC"
    private static final Pattern AC_TITLE =
            Pattern.compile("^\\s*(\\d+)\\s*\\.\\s*(.+?)\\s*(?:\\((SC|ST)\\))?\\s*$");

    private Form20PageParser() {
    }

    public static List<Constituency> parse(Document doc) {
        List<Constituency> result = new ArrayList<>();
        int districtNo = 0;
        String districtName = null;

        for (Element row : doc.select("tbody tr")) {
            Element link = row.selectFirst("a[href*=Form20_TNLA2026/]");
            if (link == null) {
                continue;
            }
            var cells = row.select("> td");
            if (cells.size() >= 3) {
                districtNo = Integer.parseInt(cells.get(0).text().trim());
                districtName = cells.get(1).text().trim();
            }
            if (districtName == null) {
                throw new IllegalStateException("Constituency row found before any district row: " + row);
            }

            String label = link.hasAttr("title") ? link.attr("title") : link.text();
            Matcher m = AC_TITLE.matcher(label);
            if (!m.matches()) {
                throw new IllegalStateException("Unexpected constituency label: " + label);
            }
            result.add(new Constituency(
                    Integer.parseInt(m.group(1)),
                    m.group(2).trim(),
                    m.group(3),
                    districtNo,
                    districtName,
                    link.absUrl("href")));
        }
        return result;
    }
}
