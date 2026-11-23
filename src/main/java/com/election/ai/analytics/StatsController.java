package com.election.ai.analytics;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Visitor statistics at /stats, behind a username and password (HTTP Basic, checked here in the
 * app so it holds whatever route the request came by). Disabled (404) when no password is set.
 */
@Controller
public class StatsController {

    private static final Logger log = LoggerFactory.getLogger(StatsController.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM, HH:mm").withZone(AnalyticsStore.ZONE);
    private static final long CACHE_MILLIS = 30_000;

    private final AnalyticsStore store;
    private final GeoLocator geo;
    private final JsonMapper json;
    private final String user;
    private final byte[] password;
    private final Map<Integer, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(long at, Map<String, Object> model) {
    }

    public StatsController(AnalyticsStore store, GeoLocator geo, JsonMapper json,
                           @Value("${analytics.stats-user:admin}") String user,
                           @Value("${analytics.stats-password:}") String password) {
        this.store = store;
        this.geo = geo;
        this.json = json;
        this.user = user;
        this.password = password.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/stats")
    public String stats(@RequestParam(name = "days", defaultValue = "30") int days,
                        HttpServletRequest req, HttpServletResponse res, Model model) {
        if (password.length == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("X-Robots-Tag", "noindex, nofollow");
        if (!authorized(req.getHeader("Authorization"))) {
            res.setHeader("WWW-Authenticate", "Basic realm=\"Visitor statistics\", charset=\"UTF-8\"");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        days = days <= 7 ? 7 : days <= 30 ? 30 : days <= 90 ? 90 : 365;
        model.addAttribute("days", days);
        model.addAttribute("you", geo.locate(VisitTracker.clientIp(req)).label());
        if (!store.enabled()) {
            model.addAttribute("error", "Statistics storage (Firestore) is not available, so nothing is being recorded.");
            return "stats";
        }
        try {
            store.flush();                  // include page views still waiting to be saved
            Cached c = cache.get(days);
            if (c == null || System.currentTimeMillis() - c.at() > CACHE_MILLIS) {
                c = new Cached(System.currentTimeMillis(), build(days));
                cache.put(days, c);
            }
            model.addAllAttributes(c.model());
        } catch (Exception e) {
            log.warn("Statistics could not be read: {}", e.toString());
            model.addAttribute("error", "Statistics could not be read right now: " + e.getMessage());
        }
        return "stats";
    }

    private boolean authorized(String header) {
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
            int i = decoded.indexOf(':');
            if (i < 0) return false;
            boolean userOk = MessageDigest.isEqual(decoded.substring(0, i).getBytes(StandardCharsets.UTF_8),
                    user.getBytes(StandardCharsets.UTF_8));
            boolean passOk = MessageDigest.isEqual(decoded.substring(i + 1).getBytes(StandardCharsets.UTF_8), password);
            return userOk & passOk;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private Map<String, Object> build(int days) throws Exception {
        LocalDate today = LocalDate.now(AnalyticsStore.ZONE);
        List<AnalyticsStore.DayStats> list = store.days(today.minusDays(days - 1L), today);

        long views = 0, visitors = 0, newVisitors = 0, bots = 0, anon = 0;
        Map<String, Map<String, Long>> sums = new HashMap<>();
        List<Map<String, Object>> chart = new ArrayList<>();
        for (AnalyticsStore.DayStats d : list) {
            views += d.views(); visitors += d.visitors(); newVisitors += d.newVisitors();
            bots += d.botViews(); anon += d.anonViews();
            d.breakdowns().forEach((k, m) -> m.forEach((name, n) ->
                    sums.computeIfAbsent(k, x -> new HashMap<>()).merge(name, n, Long::sum)));
            chart.add(Map.of("day", d.day(), "views", d.views(), "visitors", d.visitors()));
        }
        AnalyticsStore.DayStats t = list.get(list.size() - 1);
        Map<String, Long> totals = store.totals();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("todayViews", t.views());
        m.put("todayVisitors", t.visitors());
        m.put("rangeViews", views);
        m.put("rangeVisitors", visitors);      // sum of daily unique visitors
        m.put("rangeNew", newVisitors);
        m.put("rangeBots", bots);
        m.put("rangeAnon", anon);
        m.put("allViews", totals.get("views"));
        m.put("allVisitors", totals.get("visitors"));
        m.put("chartJson", json.writeValueAsString(chart));

        Map<String, List<Row>> tables = new LinkedHashMap<>();
        tables.put("Countries", top(sums.get("countries"), 15));
        tables.put("Cities", top(sums.get("cities"), 20));
        tables.put("Pages", top(sums.get("pages"), 15));
        tables.put("Constituencies (AC no.)", top(sums.get("constituencies"), 15));
        tables.put("Devices", top(sums.get("devices"), 10));
        tables.put("Browsers", top(sums.get("browsers"), 10));
        tables.put("Operating systems", top(sums.get("os"), 10));
        tables.put("Referring sites", top(sums.get("referrers"), 15));
        tables.put("API clients", top(sums.get("apiConsumers"), 10));
        m.put("tables", tables);

        List<Map<String, Object>> recent = new ArrayList<>();
        for (Map<String, Object> v : store.recent(50)) {
            Map<String, Object> r = new HashMap<>(v);
            r.put("time", v.get("ts") instanceof Instant i ? TIME.format(i) : "");
            r.put("place", new GeoLocator.Place("", str(v.get("country")), str(v.get("region")), str(v.get("city"))).label());
            recent.add(r);
        }
        m.put("recent", recent);
        return m;
    }

    public record Row(String name, long count, double pct) {
    }

    private static List<Row> top(Map<String, Long> counts, int n) {
        if (counts == null || counts.isEmpty()) return List.of();
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(n)
                .map(e -> new Row(e.getKey(), e.getValue(), total == 0 ? 0 : 100.0 * e.getValue() / total))
                .toList();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
