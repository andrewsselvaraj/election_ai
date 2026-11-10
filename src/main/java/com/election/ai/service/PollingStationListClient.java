package com.election.ai.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Downloads and reads the official "List of Polling Stations" of a constituency from the CEO
 * Tamil Nadu page that links one PDF per constituency (English and Tamil editions).
 */
@Component
public class PollingStationListClient {

    private static final String USER_AGENT = "Mozilla/5.0 (election-ai)";

    private final String pageUrl;
    private final int timeoutMillis;
    private final Map<Integer, Fetched> cache = new ConcurrentHashMap<>();
    private volatile Document page;

    /** The parsed list and the PDF it came from. */
    public record Fetched(PollingStationListParser.Result list, String pdfUrl) {
    }

    public PollingStationListClient(
            @Value("${pslist.page-url}") String pageUrl,
            @Value("${form20.timeout-millis:30000}") int timeoutMillis) {
        this.pageUrl = pageUrl;
        this.timeoutMillis = timeoutMillis;
    }

    public String pageUrl() {
        return pageUrl;
    }

    public Fetched fetch(int acNumber, boolean refresh) throws IOException {
        if (!refresh) {
            Fetched cached = cache.get(acNumber);
            if (cached != null) {
                return cached;
            }
        }
        String url = pdfUrl(acNumber, refresh);
        byte[] pdf = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(timeoutMillis)
                .ignoreContentType(true)
                .maxBodySize(0)
                .execute()
                .bodyAsBytes();
        Fetched fetched = new Fetched(PollingStationListParser.parse(pdf), url);
        cache.put(acNumber, fetched);
        return fetched;
    }

    /** The English PDF linked for the constituency, e.g. .../dt1/English/AC001.pdf. */
    String pdfUrl(int acNumber, boolean refresh) throws IOException {
        Document doc = page;
        if (doc == null || refresh) {
            doc = Jsoup.connect(pageUrl).userAgent(USER_AGENT).timeout(timeoutMillis).maxBodySize(0).get();
            page = doc;
        }
        return pdfUrl(doc, acNumber);
    }

    static String pdfUrl(Document doc, int acNumber) throws IOException {
        Pattern english = Pattern.compile("(?i)/English/AC0*" + acNumber + "\\.pdf$");
        for (Element a : doc.select("a[href]")) {
            if (english.matcher(a.attr("href")).find()) {
                return a.absUrl("href");
            }
        }
        throw new IOException("No polling station list is linked for constituency " + acNumber);
    }
}
