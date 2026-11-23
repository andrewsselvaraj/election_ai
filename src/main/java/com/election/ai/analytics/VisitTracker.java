package com.election.ai.analytics;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Records each successful page view for the statistics page. Privacy:
 * <ul>
 *   <li>the IP address is never stored; it is only used, in memory, to look up an approximate
 *       location and to make a visitor id = HMAC-SHA256(secret, IP + User-Agent), cut to 16 hex chars,
 *       which cannot be turned back into the IP without the secret;</li>
 *   <li>browsers sending Do-Not-Track or Global Privacy Control are only counted, with no details;</li>
 *   <li>static files, the statistics page itself and failed requests are not recorded.</li>
 * </ul>
 */
@Component
public class VisitTracker extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(VisitTracker.class);
    private static final Pattern AC_PATH = Pattern.compile("^/(?:api/)?(?:constituency|voice/areas)/(\\d{1,3})(?:\\.csv|/.*)?$");

    private final AnalyticsStore store;
    private final GeoLocator geo;
    private final byte[] secret;

    public VisitTracker(AnalyticsStore store, GeoLocator geo, @Value("${analytics.salt:}") String salt) {
        this.store = store;
        this.geo = geo;
        if (salt.isBlank()) {
            log.warn("analytics.salt (ANALYTICS_SALT) is not set: visitor ids change on every restart");
            byte[] r = new byte[32];
            new SecureRandom().nextBytes(r);
            this.secret = r;
        } else {
            this.secret = salt.getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        return !store.enabled() || !"GET".equals(req.getMethod())
                || p.startsWith("/stats") || p.startsWith("/js/") || p.startsWith("/error")
                || (p.contains(".") && !p.endsWith(".csv"));     // favicon.ico, robots.txt, other files
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(req, res);
        try {
            if (res.getStatus() < 400) {
                store.record(visit(req));
                res.flushBuffer();          // the visitor gets the page first...
                store.flushIfDue();         // ...then queued views are saved while this request still has CPU
            }
        } catch (RuntimeException e) {
            log.debug("Visit not recorded: {}", e.toString());
        }
    }

    private AnalyticsStore.Visit visit(HttpServletRequest req) {
        String path = req.getRequestURI();
        String ua = req.getHeader("User-Agent");
        UserAgentInfo info = UserAgentInfo.parse(ua);
        boolean anonymous = "1".equals(req.getHeader("DNT")) || "1".equals(req.getHeader("Sec-GPC"));

        Matcher m = AC_PATH.matcher(path);
        String ac = m.matches() ? m.group(1) : null;
        String page = pageName(path);
        String kind = path.startsWith("/api/") ? "api" : path.endsWith(".csv") ? "download"
                : path.startsWith("/voice/areas/") ? "voice-search" : "page";
        String consumer = path.startsWith("/api/") ? req.getHeader("X-Consumer-Username") : null;

        if (anonymous) {
            return new AnalyticsStore.Visit(Instant.now(), path, page, ac, kind, null,
                    GeoLocator.Place.UNKNOWN, info, null, null, true);
        }
        String ip = clientIp(req);
        return new AnalyticsStore.Visit(Instant.now(), path, page, ac, kind, visitorId(ip, ua),
                geo.locate(ip), info, externalReferrer(req), consumer, false);
    }

    /** Groups URLs into pages: "/constituency/12/areas" -> "Vote Analysis (polling areas)". */
    static String pageName(String path) {
        if (path.equals("/")) return "Home (constituency list)";
        if (path.equals("/voice")) return "Voice Search";
        if (path.startsWith("/voice/areas/")) return "Voice Search (constituency chosen)";
        if (path.startsWith("/api/")) return "API " + path.replaceAll("/\\d+", "/{ac}");
        if (path.endsWith(".csv")) return "CSV download";
        if (path.matches("/constituency/\\d+/areas")) return "Vote Analysis (polling areas)";
        if (path.matches("/constituency/\\d+")) return "Form 20 result";
        return "Other";
    }

    /** The visitor's IP: Kong puts it in X-Real-IP (the app itself is only reachable through Kong). */
    static String clientIp(HttpServletRequest req) {
        String ip = req.getHeader("X-Real-IP");
        if (ip == null || ip.isBlank()) {
            String xff = req.getHeader("X-Forwarded-For");
            ip = xff == null || xff.isBlank() ? req.getRemoteAddr() : xff.split(",")[0];
        }
        return ip.trim();
    }

    private String visitorId(String ip, String ua) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] h = mac.doFinal((ip + "|" + (ua == null ? "" : ua)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Host of the referring site, or null when there is none or it is this site. */
    private static String externalReferrer(HttpServletRequest req) {
        String ref = req.getHeader("Referer");
        if (ref == null || ref.isBlank()) return null;
        try {
            String host = URI.create(ref.trim()).getHost();
            if (host == null) return null;
            host = host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            String self = req.getHeader("X-Forwarded-Host");
            if (host.endsWith(".run.app") || (self != null && host.equalsIgnoreCase(self.split(",")[0].trim()))) {
                return null;
            }
            return host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
