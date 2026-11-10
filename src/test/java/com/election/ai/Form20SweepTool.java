package com.election.ai;

import com.election.ai.model.Form20Sheet;
import com.election.ai.model.PollingStation;
import com.election.ai.service.Form20PdfParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Dev aid: mvn test -Dtest=Form20SweepTool -Dsweep.dir=target/all parses every PDF and prints a summary line. */
@EnabledIfSystemProperty(named = "sweep.dir", matches = ".+")
class Form20SweepTool {
    @Test
    void sweep() throws Exception {
        File[] pdfs = new File(System.getProperty("sweep.dir")).listFiles((d, n) -> n.matches("AC[0-9]+[.]pdf"));
        Arrays.sort(pdfs);
        StringBuilder out = new StringBuilder();
        for (File f : pdfs) {
            long t0 = System.currentTimeMillis();
            String line;
            try {
                Form20Sheet s = Form20PdfParser.parse(Files.readAllBytes(f.toPath()));
                long bad = s.pollingStations().stream().filter(p -> !p.votes().consistent()).count();
                long evmSum = s.pollingStations().stream().mapToLong(p -> p.votes().totalValid()).sum();
                long dupes = s.pollingStations().size() - s.pollingStations().stream().map(PollingStation::serialNo).distinct().count();
                line = String.format("%s n=%d ps=%d bad=%d dup=%d unread=%d evm=%s(sum %s) postal=%s polled=%s electors=%s title=%s names=%s %dms",
                        f.getName(), s.candidateCount(), s.pollingStations().size(), bad, dupes, s.unreadableRows().size(),
                        s.evmTotal() == null ? "-" : s.evmTotal().totalValid(), evmSum,
                        s.postalTotal() == null ? "-" : s.postalTotal().totalValid(),
                        s.grandTotal() == null ? "-" : s.grandTotal().total(),
                        s.totalElectors(), s.acTitle(),
                        s.candidateNames().stream().limit(3).collect(Collectors.joining("/")),
                        System.currentTimeMillis() - t0);
            } catch (Exception e) {
                line = f.getName() + " FAIL " + e;
            }
            out.append(line).append('\n');
        }
        Files.writeString(new File(System.getProperty("sweep.dir"), "sweep.txt").toPath(), out);
    }
}
