package com.election.ai.service;

import com.election.ai.model.PollingStation;
import com.election.ai.model.PollingStationInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dev aid: mvn test -Dtest=PsListSweepTool -Dps.dir=target/ps/all [-Dps.form20=target/all] [-Dps.dump=AC001]
 * parses every polling station list, checks it against the Form 20 serials, writes ps-sweep.txt.
 */
@EnabledIfSystemProperty(named = "ps.dir", matches = ".+")
class PsListSweepTool {
    @Test
    void sweep() throws Exception {
        File dir = new File(System.getProperty("ps.dir"));
        String form20 = System.getProperty("ps.form20", "");
        String dump = System.getProperty("ps.dump", "");
        File[] pdfs = dir.listFiles((d, n) -> n.matches("AC[0-9]+[.]pdf"));
        Arrays.sort(pdfs);
        StringBuilder out = new StringBuilder();
        StringBuilder full = new StringBuilder();
        for (File f : pdfs) {
            String name = f.getName().replace(".pdf", "");
            if (!dump.isEmpty() && !dump.contains(name)) {
                continue;
            }
            try {
                PollingStationListParser.Result r = PollingStationListParser.parse(Files.readAllBytes(f.toPath()));
                List<PollingStationInfo> s = r.stations();
                long noArea = s.stream().filter(p -> p.areas().isEmpty()).count();
                long noLoc = s.stream().filter(p -> p.location().isEmpty()).count();
                long noItem = s.stream().filter(p -> !PollingStationListParser.startsWithItem(p.areas())).count();
                long dupes = s.size() - s.stream().map(PollingStationInfo::psNo).distinct().count();
                String match = "";
                File f20 = new File(form20, f.getName());
                if (!form20.isEmpty() && f20.exists()) {
                    try {
                        Set<String> ps = s.stream().map(PollingStationInfo::psNo).collect(Collectors.toSet());
                        List<String> serials = Form20PdfParser.parse(Files.readAllBytes(f20.toPath())).pollingStations()
                                .stream().map(PollingStation::serialNo).distinct().toList();
                        List<String> missing = serials.stream().filter(x -> !ps.contains(x)).toList();
                        match = " f20=" + serials.size() + " unmatched=" + missing.size()
                                + (missing.isEmpty() ? "" : " " + missing.stream().limit(8).toList());
                    } catch (Exception e) {
                        match = " f20=ERR";
                    }
                }
                out.append(String.format("%s ac=%s n=%d last=%s noArea=%d noLoc=%d noItem=%d dup=%d%s%n",
                        name, r.acNumber(), s.size(), s.isEmpty() ? "-" : s.get(s.size() - 1).psNo(),
                        noArea, noLoc, noItem, dupes, match));
                if (!dump.isEmpty()) {
                    for (PollingStationInfo p : s) {
                        full.append(name).append(' ').append(p.psNo()).append(" | ").append(p.location()).append(" | ")
                                .append(p.areas()).append(" | ").append(p.type()).append('\n');
                    }
                }
            } catch (Exception e) {
                out.append(name).append(" FAIL ").append(e).append('\n');
            }
        }
        Files.writeString(new File(dir, "ps-sweep.txt").toPath(), out);
        if (!dump.isEmpty()) {
            Files.writeString(new File(dir, "ps-dump.txt").toPath(), full);
        }
    }
}
