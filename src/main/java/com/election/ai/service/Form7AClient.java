package com.election.ai.service;

import com.election.ai.model.Form7ACandidate;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches party affiliations from the official list of contesting candidates (Form 7A) on
 * erolls.tn.gov.in. Form 20 PDFs carry candidate names but not parties.
 *
 * <p>The list page is ASP.NET WebForms: each constituency link is a {@code __doPostBack}, so we
 * load the list (for its cookies and hidden form state), then post back the chosen link.
 */
@Component
public class Form7AClient {

    private static final Pattern POSTBACK = Pattern.compile("__doPostBack\\('([^']+)'");
    private static final String USER_AGENT = "Mozilla/5.0 (election-ai)";

    private final String listUrl;
    private final int timeoutMillis;
    private final Map<Integer, Result> cache = new ConcurrentHashMap<>();

    public record Result(String acName, List<Form7ACandidate> candidates, String sourceUrl) {
    }

    public Form7AClient(
            @Value("${form7a.list-url}") String listUrl,
            @Value("${form20.timeout-millis:30000}") int timeoutMillis) {
        this.listUrl = listUrl;
        this.timeoutMillis = timeoutMillis;
    }

    /** Candidates for one constituency; answers are cached since the list no longer changes. */
    public Result fetch(int acNumber) throws IOException {
        Result cached = cache.get(acNumber);
        if (cached != null) {
            return cached;
        }
        Connection session = Jsoup.newSession()
                .userAgent(USER_AGENT)
                .timeout(timeoutMillis)
                .maxBodySize(0);

        Document list = session.newRequest(listUrl).get();
        String target = postBackTarget(list, acNumber);

        Connection post = session.newRequest(listUrl)
                .data("__EVENTTARGET", target)
                .data("__EVENTARGUMENT", "");
        for (Element input : list.select("form#form1 input[type=hidden][name]")) {
            String name = input.attr("name");
            if (!name.equals("__EVENTTARGET") && !name.equals("__EVENTARGUMENT")) {
                post.data(name, input.val());
            }
        }
        Document page = post.post();
        Result result = parse(page);
        if (result.candidates().isEmpty()) {
            throw new IOException("Form 7A page for AC " + acNumber + " listed no candidates");
        }
        cache.put(acNumber, result);
        return result;
    }

    /** Finds the postback target of the row whose first cell is the AC number. */
    static String postBackTarget(Document list, int acNumber) throws IOException {
        for (Element row : list.select("tr")) {
            Element first = row.selectFirst("> td");
            Element link = row.selectFirst("a[id*=lnk_Pc_Name]");
            if (first != null && link != null && first.text().trim().equals(String.valueOf(acNumber))) {
                Matcher m = POSTBACK.matcher(link.attr("href"));
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        throw new IOException("AC " + acNumber + " not found on Form 7A list page");
    }

    static Result parse(Document page) {
        Element acName = page.getElementById("ContentPlaceHolder1_lb_ac_name");
        List<Form7ACandidate> candidates = new ArrayList<>();
        for (Element row : page.select("table.tableList tr")) {
            var cells = row.select("> td");
            if (cells.size() >= 3 && cells.get(0).text().trim().matches("\\d+")) {
                candidates.add(new Form7ACandidate(
                        Integer.parseInt(cells.get(0).text().trim()),
                        cells.get(1).text().trim(),
                        blankToNull(cells.get(2).text()),
                        cells.size() > 3 ? blankToNull(cells.get(3).text()) : null));
            }
        }
        return new Result(acName == null ? null : acName.text().trim(), candidates, page.location());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
