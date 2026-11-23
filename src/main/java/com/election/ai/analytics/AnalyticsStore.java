package com.election.ai.analytics;

import com.election.ai.analytics.FirestoreRest.Inc;
import com.election.ai.analytics.FirestoreRest.Write;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Keeps visitor statistics in Firestore, so they survive restarts and are shared by all instances.
 *
 * <ul>
 *   <li>{@code visits/{auto}}: one document per page view (no IP address); deleted after
 *       {@code analytics.retention-days} by a Firestore TTL policy on {@code expireAt}.</li>
 *   <li>{@code daily/{yyyy-MM-dd}}: counters for that day (IST): views, unique visitors and
 *       breakdowns by page, country, city, browser, OS, device and referrer. Kept forever.</li>
 *   <li>{@code daily/{day}/visitors/{id}} and {@code visitors/{id}}: which pseudonymous visitor ids
 *       were seen that day / ever, used to count unique and new visitors.</li>
 * </ul>
 *
 * Page views are queued in memory and written in batches every few seconds (and on shutdown), so
 * requests never wait for Firestore. Firestore is reached through its REST API ({@link FirestoreRest}).
 */
@Component
public class AnalyticsStore {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsStore.class);
    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    /** One recorded page view. {@code anonymous}: the browser asked not to be tracked (DNT/GPC). */
    public record Visit(Instant ts, String path, String page, String ac, String kind, String visitor,
                        GeoLocator.Place place, UserAgentInfo ua, String referrer, String consumer,
                        boolean anonymous) {
    }

    private final FirestoreRest db;
    private final int retentionDays;
    private final ConcurrentLinkedQueue<Visit> queue = new ConcurrentLinkedQueue<>();
    // ids already known to exist in Firestore, so they need not be read again (bounded LRU)
    private final Set<String> knownVisitors = lruSet(100_000);
    private final Set<String> knownDaily = lruSet(100_000);

    private final long flushMillis;

    public AnalyticsStore(@Value("${analytics.enabled:true}") boolean enabled,
                          @Value("${analytics.retention-days:180}") int retentionDays,
                          @Value("${analytics.flush-millis:5000}") long flushMillis,
                          @Value("${analytics.project-id:}") String projectId,
                          @Value("${analytics.access-token:}") String accessToken,
                          JsonMapper json) {
        this.retentionDays = retentionDays;
        this.flushMillis = flushMillis;
        FirestoreRest f = null;
        if (enabled) {
            try {
                f = new FirestoreRest(json, projectId, accessToken);
                log.info("Visitor statistics are stored in Firestore (project {})", f.projectId());
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("Firestore is not available; visitor statistics are disabled: {}", e.toString());
            }
        }
        this.db = f;
    }

    public boolean enabled() {
        return db != null;
    }

    public void record(Visit v) {
        if (db != null && queue.size() < 50_000) {
            queue.add(v);
        }
    }

    private volatile long lastFlush = System.currentTimeMillis();

    /**
     * Saves queued page views if the last save was over {@code analytics.flush-millis} ago. Called at
     * the end of requests: Cloud Run gives an instance almost no CPU between requests, so a purely
     * background timer would rarely get to run.
     */
    public void flushIfDue() {
        if (System.currentTimeMillis() - lastFlush >= flushMillis && !queue.isEmpty()) {
            flush();
        }
    }

    /** Backup for instances that have CPU between requests (and for a quiet tail of traffic). */
    @Scheduled(fixedDelayString = "${analytics.flush-millis:5000}")
    public void scheduledFlush() {
        flush();
    }

    @PreDestroy
    public synchronized void flush() {
        if (db == null || queue.isEmpty()) {
            return;
        }
        lastFlush = System.currentTimeMillis();
        List<Visit> visits = new ArrayList<>();
        for (Visit v; visits.size() < 5_000 && (v = queue.poll()) != null; ) {
            visits.add(v);
        }
        try {
            write(visits);
        } catch (Exception e) {
            log.warn("Could not save {} page views to Firestore: {}", visits.size(), e.toString());
        }
    }

    private void write(List<Visit> visits) throws Exception {
        // which visitor ids are new (ever / today)? read only the ones not already known
        Map<String, String> toCheck = new LinkedHashMap<>();      // key -> document path
        for (Visit v : visits) {
            if (v.anonymous() || v.ua().bot()) continue;
            String day = day(v.ts());
            if (!knownVisitors.contains(v.visitor())) toCheck.put("v|" + v.visitor(), visitorRef(v.visitor()));
            if (!knownDaily.contains(day + "|" + v.visitor())) toCheck.put("d|" + day + "|" + v.visitor(), dailyVisitorRef(day, v.visitor()));
        }
        Set<String> existing = new HashSet<>();
        List<String> keys = new ArrayList<>(toCheck.keySet());
        for (int i = 0; i < keys.size(); i += 300) {
            List<String> part = keys.subList(i, Math.min(keys.size(), i + 300));
            Map<String, Map<String, Object>> found = db.get(part.stream().map(toCheck::get).toList());
            for (String k : part) {
                if (found.containsKey(toCheck.get(k))) existing.add(k);
            }
        }

        Map<String, Map<String, Object>> daily = new HashMap<>();   // day -> nested increments
        Map<String, Object> totals = new HashMap<>();
        List<Write> ops = new ArrayList<>();
        Set<String> newThisBatch = new HashSet<>();
        Set<String> newDailyThisBatch = new HashSet<>();

        for (Visit v : visits) {
            String day = day(v.ts());
            Map<String, Object> d = daily.computeIfAbsent(day, k -> new HashMap<>());
            if (v.anonymous()) { inc(d, "anonViews"); inc(totals, "anonViews"); continue; }
            if (v.ua().bot()) { inc(d, "botViews"); inc(totals, "botViews"); continue; }
            inc(d, "views"); inc(totals, "views");
            inc(d, "pages", v.page());
            if (v.ac() != null) inc(d, "constituencies", v.ac());
            inc(d, "countries", v.place().country());
            inc(d, "cities", v.place().label());
            inc(d, "browsers", v.ua().browser());
            inc(d, "os", v.ua().os());
            inc(d, "devices", v.ua().device());
            if (v.referrer() != null) inc(d, "referrers", v.referrer());
            if (v.consumer() != null) inc(d, "apiConsumers", v.consumer());

            String vid = v.visitor();
            Instant ts = v.ts();
            boolean newVisitor = !knownVisitors.contains(vid) && !existing.contains("v|" + vid) && !newThisBatch.contains(vid);
            Map<String, Object> vdoc = new HashMap<>();
            vdoc.put("lastSeen", ts);
            vdoc.put("visits", new Inc(1));
            vdoc.put("place", v.place().label());
            vdoc.put("device", v.ua().device());
            vdoc.put("browser", v.ua().browser());
            if (newVisitor) {
                vdoc.put("firstSeen", ts);
                newThisBatch.add(vid);
                inc(d, "newVisitors");
            }
            ops.add(new Write(visitorRef(vid), vdoc));

            String dk = day + "|" + vid;
            if (!knownDaily.contains(dk) && !existing.contains("d|" + dk) && newDailyThisBatch.add(dk)) {
                inc(d, "visitors");
                ops.add(new Write(dailyVisitorRef(day, vid), Map.of("first", ts)));
            }

            Map<String, Object> doc = new HashMap<>();
            doc.put("ts", ts);
            doc.put("expireAt", v.ts().plus(Duration.ofDays(retentionDays)));
            doc.put("path", v.path());
            doc.put("page", v.page());
            doc.put("kind", v.kind());
            doc.put("visitor", vid);
            doc.put("newVisitor", newVisitor);
            doc.put("country", v.place().country());
            doc.put("countryCode", v.place().countryCode());
            doc.put("region", v.place().region());
            doc.put("city", v.place().city());
            doc.put("browser", v.ua().browser());
            doc.put("os", v.ua().os());
            doc.put("device", v.ua().device());
            if (v.referrer() != null) doc.put("referrer", v.referrer());
            if (v.consumer() != null) doc.put("apiConsumer", v.consumer());
            ops.add(new Write("visits/" + FirestoreRest.autoId(), doc));
        }
        daily.forEach((day, inc) -> {
            if (!inc.isEmpty()) ops.add(new Write("daily/" + day, increments(inc)));
        });
        if (!totals.isEmpty()) ops.add(new Write("meta/totals", increments(totals)));

        for (int i = 0; i < ops.size(); i += 400) {
            db.commit(ops.subList(i, Math.min(ops.size(), i + 400)));
        }
        knownVisitors.addAll(newThisBatch);
        for (String k : existing) {
            if (k.startsWith("v|")) knownVisitors.add(k.substring(2)); else knownDaily.add(k.substring(2));
        }
        knownDaily.addAll(newDailyThisBatch);
    }

    // ---------------------------------------------------------------- reading

    public record DayStats(String day, long views, long visitors, long newVisitors, long botViews, long anonViews,
                           Map<String, Map<String, Long>> breakdowns) {
    }

    public List<DayStats> days(LocalDate from, LocalDate to) throws Exception {
        List<String> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            days.add(d.toString());
        }
        Map<String, Map<String, Object>> docs = db.get(days.stream().map(d -> "daily/" + d).toList());
        List<DayStats> out = new ArrayList<>();
        for (String day : days) {
            Map<String, Object> s = docs.getOrDefault("daily/" + day, Map.of());
            Map<String, Map<String, Long>> breakdowns = new HashMap<>();
            s.forEach((k, val) -> {
                if (val instanceof Map<?, ?> m) {
                    Map<String, Long> counts = new HashMap<>();
                    m.forEach((name, n) -> counts.put(String.valueOf(name), n instanceof Number x ? x.longValue() : 0L));
                    breakdowns.put(k, counts);
                }
            });
            out.add(new DayStats(day, num(s, "views"), num(s, "visitors"), num(s, "newVisitors"),
                    num(s, "botViews"), num(s, "anonViews"), breakdowns));
        }
        return out;
    }

    public Map<String, Long> totals() throws Exception {
        Map<String, Object> s = db.get(List.of("meta/totals")).getOrDefault("meta/totals", Map.of());
        Map<String, Long> m = new HashMap<>();
        for (String k : List.of("views", "botViews", "anonViews")) m.put(k, num(s, k));
        m.put("visitors", db.count("visitors"));
        return m;
    }

    public List<Map<String, Object>> recent(int limit) throws Exception {
        return db.query("visits", "ts", true, limit);      // "ts" comes back as an Instant
    }

    // ---------------------------------------------------------------- helpers

    static String day(Instant t) {
        return LocalDate.ofInstant(t, ZONE).toString();
    }

    private static String visitorRef(String id) {
        return "visitors/" + id;
    }

    private static String dailyVisitorRef(String day, String id) {
        return "daily/" + day + "/visitors/" + id;
    }

    private static long num(Map<String, Object> s, String field) {
        return s.get(field) instanceof Number n ? n.longValue() : 0;
    }

    /** Adds 1 to {@code m[field]} or {@code m[field][key]} as Firestore increments (merged on write). */
    @SuppressWarnings("unchecked")
    private static void inc(Map<String, Object> m, String field, String... key) {
        if (key.length == 0) {
            m.merge(field, 1L, (a, b) -> (Long) a + 1);
            return;
        }
        String k = key[0] == null || key[0].isBlank() ? "Unknown" : key[0].replaceAll("^__|__$", "_");
        ((Map<String, Object>) m.computeIfAbsent(field, x -> new HashMap<String, Object>()))
                .merge(k, 1L, (a, b) -> (Long) a + 1);
    }

    /** Counts (also in nested maps) as Firestore increments, so a merge adds to the stored values. */
    private static Map<String, Object> increments(Map<String, Object> counts) {
        Map<String, Object> out = new HashMap<>();
        counts.forEach((k, v) -> out.put(k, v instanceof Map<?, ?> m
                ? increments(castMap(m)) : new Inc(((Number) v).longValue())));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static Set<String> lruSet(int max) {
        return Collections.synchronizedSet(Collections.newSetFromMap(new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                return size() > max;
            }
        }));
    }
}
