package com.election.ai.analytics;

import com.maxmind.db.CHMCache;
import com.maxmind.geoip2.DatabaseReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.InetAddress;
import java.util.regex.Pattern;

/**
 * Approximate visitor location (city, region, country) from an IP address, looked up in the
 * DB-IP Lite city database baked into the image (see Dockerfile). Nothing is sent to any outside
 * service. Without the database file every location is "Unknown".
 */
@Component
public class GeoLocator {

    private static final Logger log = LoggerFactory.getLogger(GeoLocator.class);
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]*:[0-9a-fA-F:.]*");

    public record Place(String countryCode, String country, String region, String city) {
        static final Place UNKNOWN = new Place("", "Unknown", "", "");

        /** "Chennai, Tamil Nadu, India" without empty parts. */
        public String label() {
            StringBuilder sb = new StringBuilder();
            for (String s : new String[] {city, region, country}) {
                if (s != null && !s.isBlank() && sb.indexOf(s) < 0) {
                    sb.append(sb.isEmpty() ? "" : ", ").append(s);
                }
            }
            return sb.isEmpty() ? "Unknown" : sb.toString();
        }
    }

    private final DatabaseReader reader;

    public GeoLocator(@Value("${analytics.geoip-db:/opt/geoip/dbip-city-lite.mmdb}") String path) {
        DatabaseReader r = null;
        File f = new File(path);
        if (f.isFile()) {
            try {
                r = new DatabaseReader.Builder(f).withCache(new CHMCache()).build();
                log.info("IP location database loaded: {} ({})", path, r.getMetadata().getDatabaseType());
            } catch (Exception e) {
                log.warn("IP location database {} could not be opened: {}", path, e.toString());
            }
        } else {
            log.info("No IP location database at {}; visitor locations will be Unknown", path);
        }
        this.reader = r;
    }

    public Place locate(String ip) {
        // only literal addresses: getByName on anything else would do a DNS lookup
        if (reader == null || ip == null || !(IPV4.matcher(ip).matches() || IPV6.matcher(ip).matches())) {
            return Place.UNKNOWN;
        }
        try {
            InetAddress a = InetAddress.getByName(ip);
            if (a.isSiteLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()) {
                return Place.UNKNOWN;
            }
            return reader.tryCity(a)
                    .map(c -> new Place(nz(c.getCountry().getIsoCode()), nz(c.getCountry().getName()),
                            nz(c.getMostSpecificSubdivision().getName()), nz(c.getCity().getName())))
                    .orElse(Place.UNKNOWN);
        } catch (Exception e) {
            return Place.UNKNOWN;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
