package com.election.ai.web;

import com.election.ai.model.CandidateResult;
import com.election.ai.model.Constituency;
import com.election.ai.model.ConstituencyResult;
import com.election.ai.model.District;
import com.election.ai.model.PollingStation;
import com.election.ai.service.ConstituencyResultService;
import com.election.ai.service.ConstituencyService;
import com.election.ai.service.Form20PdfParser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Controller
public class ConstituencyController {

    private static final DateTimeFormatter LOADED_AT_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss z").withZone(ZoneId.of("Asia/Kolkata"));

    private final ConstituencyService service;
    private final ConstituencyResultService results;

    public ConstituencyController(ConstituencyService service, ConstituencyResultService results) {
        this.service = service;
        this.results = results;
    }

    @GetMapping("/")
    public String index(@RequestParam(name = "q", required = false) String q, Model model) {
        ConstituencyService.Snapshot snapshot = service.snapshot();
        List<District> districts = service.districts(q);
        model.addAttribute("districts", districts);
        model.addAttribute("shown", districts.stream().mapToInt(d -> d.constituencies().size()).sum());
        model.addAttribute("total", snapshot.constituencies().size());
        model.addAttribute("districtCount",
                snapshot.constituencies().stream().map(Constituency::districtNo).distinct().count());
        model.addAttribute("source", snapshot.source());
        model.addAttribute("loadedAt", LOADED_AT_FORMAT.format(snapshot.loadedAt()));
        model.addAttribute("q", q == null ? "" : q);
        return "constituencies";
    }

    @PostMapping("/refresh")
    public String refresh() {
        service.refresh();
        return "redirect:/";
    }

    @GetMapping("/api/constituencies")
    @ResponseBody
    public List<Constituency> api() {
        return service.snapshot().constituencies();
    }

    /** Opened from the "Form 20" link: polling-station votes with candidate and party. */
    @GetMapping("/constituency/{ac}")
    public String constituency(@PathVariable int ac,
                               @RequestParam(name = "refresh", defaultValue = "false") boolean refresh,
                               Model model) {
        Constituency c = service.find(ac).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("c", c);
        try {
            ConstituencyResult r = results.result(ac, refresh);
            model.addAttribute("r", r);
            model.addAttribute("loadedAt", LOADED_AT_FORMAT.format(r.loadedAt()));
        } catch (Form20PdfParser.NoTextException e) {
            model.addAttribute("error", "This constituency's Form 20 PDF is a scanned image with no text, "
                    + "so its numbers cannot be read automatically. Open the original PDF to view it.");
        } catch (IOException | RuntimeException e) {
            model.addAttribute("error", "Could not load the Form 20 PDF: " + e.getMessage());
        }
        return "constituency";
    }

    @GetMapping("/api/constituency/{ac}")
    @ResponseBody
    public ConstituencyResult constituencyApi(@PathVariable int ac) throws IOException {
        service.find(ac).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return results.result(ac, false);
    }

    /** API and CSV callers get a readable reason instead of a 500 for scanned PDFs. */
    @ExceptionHandler(Form20PdfParser.NoTextException.class)
    public ResponseEntity<Map<String, String>> scannedPdf(Form20PdfParser.NoTextException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
    }

    /** One row per polling station and candidate: serial no., candidate, party, votes. */
    @GetMapping(value = "/constituency/{ac}.csv", produces = "text/csv")
    public ResponseEntity<String> constituencyCsv(@PathVariable int ac) throws IOException {
        service.find(ac).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        ConstituencyResult r = results.result(ac, false);
        StringBuilder csv = new StringBuilder("ac_no,constituency,polling_station_serial_no,candidate_serial,candidate_name,party,votes\n");
        for (PollingStation p : r.pollingStations()) {
            for (CandidateResult cand : r.candidates()) {
                csv.append(ac).append(',')
                        .append(csvCell(r.constituency().displayName())).append(',')
                        .append(csvCell(p.serialNo())).append(',')
                        .append(cand.serial()).append(',')
                        .append(csvCell(cand.name())).append(',')
                        .append(csvCell(cand.party())).append(',')
                        .append(p.votes().candidateVotes().get(cand.serial() - 1)).append('\n');
            }
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"Form20_AC" + String.format("%03d", ac) + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.toString());
    }

    private static String csvCell(String s) {
        if (s == null) {
            return "";
        }
        return s.matches(".*[\",\\n].*") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
