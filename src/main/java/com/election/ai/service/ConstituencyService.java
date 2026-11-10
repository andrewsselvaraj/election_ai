package com.election.ai.service;

import com.election.ai.model.Constituency;
import com.election.ai.model.District;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads the 234 constituencies from the live CEO Tamil Nadu page, falling back to a
 * bundled snapshot of the same page if the site is unreachable. Results are cached in memory.
 */
@Service
public class ConstituencyService {

    private static final Logger log = LoggerFactory.getLogger(ConstituencyService.class);
    private static final String SNAPSHOT = "snapshot/Form20_TNLA2026.html";

    private final String sourceUrl;
    private final int timeoutMillis;

    private volatile Snapshot cache;

    public ConstituencyService(
            @Value("${form20.source-url}") String sourceUrl,
            @Value("${form20.timeout-millis:30000}") int timeoutMillis) {
        this.sourceUrl = sourceUrl;
        this.timeoutMillis = timeoutMillis;
    }

    public record Snapshot(List<Constituency> constituencies, String source, Instant loadedAt) {
    }

    public Snapshot snapshot() {
        Snapshot s = cache;
        if (s == null) {
            synchronized (this) {
                if (cache == null) {
                    cache = load();
                }
                s = cache;
            }
        }
        return s;
    }

    public synchronized Snapshot refresh() {
        cache = load();
        return cache;
    }

    public java.util.Optional<Constituency> find(int acNumber) {
        return snapshot().constituencies().stream().filter(c -> c.number() == acNumber).findFirst();
    }

    public List<District> districts(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Map<Integer, District> byNo = new LinkedHashMap<>();
        for (Constituency c : snapshot().constituencies()) {
            if (!q.isEmpty() && !matches(c, q)) {
                continue;
            }
            byNo.computeIfAbsent(c.districtNo(),
                    n -> new District(n, c.districtName(), new java.util.ArrayList<>()))
                .constituencies().add(c);
        }
        return byNo.values().stream().sorted(Comparator.comparingInt(District::number)).toList();
    }

    private static boolean matches(Constituency c, String q) {
        return c.displayName().toLowerCase(Locale.ROOT).contains(q)
                || c.districtName().toLowerCase(Locale.ROOT).contains(q)
                || String.valueOf(c.number()).equals(q);
    }

    private Snapshot load() {
        try {
            Document doc = Jsoup.connect(sourceUrl)
                    .userAgent("Mozilla/5.0 (election-ai)")
                    .timeout(timeoutMillis)
                    .get();
            List<Constituency> list = Form20PageParser.parse(doc);
            if (!list.isEmpty()) {
                log.info("Loaded {} constituencies from {}", list.size(), sourceUrl);
                return new Snapshot(list, sourceUrl, Instant.now());
            }
            log.warn("Live page returned no constituencies; using bundled snapshot");
        } catch (IOException | RuntimeException e) {
            log.warn("Could not load {} ({}); using bundled snapshot", sourceUrl, e.toString());
        }
        return loadSnapshot();
    }

    private Snapshot loadSnapshot() {
        try (InputStream in = new ClassPathResource(SNAPSHOT).getInputStream()) {
            // baseUri makes relative PDF links resolve to the real site
            Document doc = Jsoup.parse(in, "UTF-8", sourceUrl);
            List<Constituency> list = Form20PageParser.parse(doc);
            log.info("Loaded {} constituencies from bundled snapshot", list.size());
            return new Snapshot(list, "bundled snapshot of " + sourceUrl, Instant.now());
        } catch (IOException e) {
            throw new IllegalStateException("Bundled snapshot missing: " + SNAPSHOT, e);
        }
    }
}
