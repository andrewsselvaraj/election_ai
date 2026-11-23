package com.election.ai.analytics;

import java.util.Locale;
import java.util.regex.Pattern;

/** Browser, operating system and device type from a User-Agent header (coarse, no fingerprinting). */
public record UserAgentInfo(String browser, String os, String device, boolean bot) {

    private static final Pattern BOT = Pattern.compile(
            "bot|crawl|spider|slurp|curl|wget|python|httpclient|okhttp|java/|go-http|headless|lighthouse"
            + "|facebookexternalhit|whatsapp|telegram|preview|monitor|scanner|uptime|postman|insomnia|powershell");

    public static UserAgentInfo parse(String ua) {
        if (ua == null || ua.isBlank()) {
            return new UserAgentInfo("Unknown", "Unknown", "Unknown", true);
        }
        String u = ua.toLowerCase(Locale.ROOT);
        boolean bot = BOT.matcher(u).find();

        String browser;
        if (u.contains("edg/") || u.contains("edga/") || u.contains("edgios/")) browser = "Edge";
        else if (u.contains("opr/") || u.contains("opera")) browser = "Opera";
        else if (u.contains("samsungbrowser")) browser = "Samsung Internet";
        else if (u.contains("ucbrowser")) browser = "UC Browser";
        else if (u.contains("firefox") || u.contains("fxios")) browser = "Firefox";
        else if (u.contains("crios") || u.contains("chrome")) browser = "Chrome";
        else if (u.contains("safari")) browser = "Safari";
        else browser = "Other";

        String os;
        if (u.contains("android")) os = "Android";
        else if (u.contains("iphone") || u.contains("ipad") || u.contains("ipod")) os = "iOS";
        else if (u.contains("windows")) os = "Windows";
        else if (u.contains("cros")) os = "ChromeOS";
        else if (u.contains("mac os x") || u.contains("macintosh")) os = "macOS";
        else if (u.contains("linux")) os = "Linux";
        else os = "Other";

        String device;
        if (u.contains("ipad") || u.contains("tablet") || (u.contains("android") && !u.contains("mobile"))) device = "Tablet";
        else if (u.contains("mobi") || u.contains("iphone") || u.contains("android")) device = "Mobile";
        else device = "Desktop";

        return new UserAgentInfo(browser, os, device, bot);
    }
}
