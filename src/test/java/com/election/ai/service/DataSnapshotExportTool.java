package com.election.ai.service;

import com.election.ai.model.Form20Sheet;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Refreshes the saved copy of the official data (src/main/resources/data/AC001.json.gz ...).
 * Must run from a network that can reach the Tamil Nadu sites (they only answer from India).
 *
 * <pre>
 * mvn test -Dtest=DataSnapshotExportTool -Dexport.out=src/main/resources/data \
 *     -Dexport.form20=target/all -Dexport.pslist=target/ps/all [-Dexport.only=1,2]
 * </pre>
 * export.form20 / export.pslist: folders of already-downloaded AC001.pdf ... (downloaded if missing).
 */
@EnabledIfSystemProperty(named = "export.out", matches = ".+")
class DataSnapshotExportTool {

    private static final String FORM20_PAGE = "https://www.elections.tn.gov.in/Form20_TNLA2026.aspx";
    private static final String PSLIST_PAGE = "https://www.elections.tn.gov.in/PSLIST_06042026.aspx";
    private static final String FORM7A_LIST = "https://erolls.tn.gov.in/acwithcandidate_tnla2026/AC_List.aspx";

    @Test
    void export() throws Exception {
        Path out = Path.of(System.getProperty("export.out"));
        File form20Dir = new File(System.getProperty("export.form20", "target/all"));
        File psDir = new File(System.getProperty("export.pslist", "target/ps/all"));
        String only = System.getProperty("export.only", "");

        JsonMapper json = JsonMapper.builder().build();
        DataSnapshotStore store = new DataSnapshotStore(json, "data/");
        ConstituencyService constituencies = new ConstituencyService(FORM20_PAGE, 60000);
        Form7AClient form7a = new Form7AClient(FORM7A_LIST, 60000);
        var psPage = Jsoup.connect(PSLIST_PAGE).userAgent("Mozilla/5.0 (election-ai)").timeout(60000).maxBodySize(0).get();

        StringBuilder log = new StringBuilder();
        for (var c : constituencies.snapshot().constituencies()) {
            int ac = c.number();
            if (!only.isEmpty() && !("," + only + ",").contains("," + ac + ",")) {
                continue;
            }
            Form7AClient.Result candidates = null;
            String form7aError = null;
            try {
                candidates = form7a.fetch(ac);
            } catch (Exception e) {
                form7aError = e.getMessage();
            }

            Form20Sheet sheet = null;
            String form20Error = null;
            try {
                byte[] pdf = pdf(new File(form20Dir, String.format("AC%03d.pdf", ac)), c.form20PdfUrl());
                sheet = Form20PdfParser.parse(pdf, candidates == null ? null : candidates.candidates().size());
            } catch (Form20PdfParser.NoTextException e) {
                form20Error = DataSnapshotStore.SCANNED;
            } catch (Exception e) {
                form20Error = e.getMessage();
            }

            PollingStationListParser.Result list = null;
            String listUrl = null;
            String listError = null;
            try {
                listUrl = PollingStationListClient.pdfUrl(psPage, ac);
                list = PollingStationListParser.parse(pdf(new File(psDir, String.format("AC%03d.pdf", ac)), listUrl));
            } catch (Form20PdfParser.NoTextException e) {
                listError = DataSnapshotStore.SCANNED;
            } catch (Exception e) {
                listError = e.getMessage();
            }

            var snapshot = new DataSnapshotStore.Snapshot(ac, Instant.now(), sheet, form20Error,
                    candidates, form7aError, list, listUrl, listError);
            store.write(out, snapshot);
            // read it back the way the app will
            try (var in = new java.util.zip.GZIPInputStream(Files.newInputStream(out.resolve(DataSnapshotStore.fileName(ac))))) {
                var back = json.readValue(in, DataSnapshotStore.Snapshot.class);
                if (sheet != null && back.form20().pollingStations().size() != sheet.pollingStations().size()) {
                    throw new IllegalStateException("round trip lost stations for AC " + ac);
                }
            }
            log.append(String.format("AC%03d form20=%s form7a=%s pslist=%s%n", ac,
                    sheet != null ? sheet.pollingStations().size() + " stations" : form20Error,
                    candidates != null ? candidates.candidates().size() + " candidates" : form7aError,
                    list != null ? list.stations().size() + " stations" : listError));
        }
        Files.writeString(out.resolve("export.log"), log);
    }

    private static byte[] pdf(File local, String url) throws Exception {
        if (local.isFile() && local.length() > 0) {
            return Files.readAllBytes(local.toPath());
        }
        return Jsoup.connect(url).userAgent("Mozilla/5.0 (election-ai)").timeout(120000)
                .ignoreContentType(true).maxBodySize(0).execute().bodyAsBytes();
    }
}
