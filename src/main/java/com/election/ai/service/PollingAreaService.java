package com.election.ai.service;

import com.election.ai.model.AreaStation;
import com.election.ai.model.ConstituencyResult;
import com.election.ai.model.PollingAreaResult;
import com.election.ai.model.PollingStation;
import com.election.ai.model.PollingStationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Joins a constituency's Form 20 votes (per polling station) with the official list of polling
 * stations (building and polling areas per station), matched by polling station number, so a
 * voter's street or village leads to the votes cast at their polling station.
 */
@Service
public class PollingAreaService {

    private static final Logger log = LoggerFactory.getLogger(PollingAreaService.class);

    private static final String SCANNED_LIST = "The polling station list PDF for this constituency is a scanned image, "
            + "so its polling areas cannot be read; search works by polling station number only.";

    private final ConstituencyResultService results;
    private final PollingStationListClient lists;
    private final DataSnapshotStore snapshots;

    public PollingAreaService(ConstituencyResultService results, PollingStationListClient lists,
                              DataSnapshotStore snapshots) {
        this.results = results;
        this.lists = lists;
        this.snapshots = snapshots;
    }

    /**
     * Form 20 problems (download, scanned PDF) are thrown; a missing station list only warns.
     * The saved copy of the station list is used unless {@code refresh} asks for the live one.
     */
    public PollingAreaResult result(int acNumber, boolean refresh) throws IOException {
        ConstituencyResult form20 = results.result(acNumber, refresh);
        Optional<DataSnapshotStore.Snapshot> saved = snapshots.find(acNumber);
        if (saved.isPresent() && !refresh) {
            return fromSnapshot(acNumber, form20, saved.get());
        }
        PollingStationListClient.Fetched list = null;
        String listError = null;
        try {
            list = lists.fetch(acNumber, refresh);
        } catch (Form20PdfParser.NoTextException e) {
            listError = SCANNED_LIST;
        } catch (IOException | RuntimeException e) {
            if (saved.isPresent()) {
                log.warn("Polling station list unreachable for AC {} ({}); using saved data", acNumber, e.toString());
                return fromSnapshot(acNumber, form20, saved.get());
            }
            log.warn("Polling station list failed for AC {}: {}", acNumber, e.toString());
            listError = "The polling station list could not be read (" + e.getMessage()
                    + "); search works by polling station number only.";
        }
        return join(acNumber, form20, list, listError);
    }

    private static PollingAreaResult fromSnapshot(int acNumber, ConstituencyResult form20, DataSnapshotStore.Snapshot s) {
        if (s.pollingStations() != null) {
            return join(acNumber, form20, new PollingStationListClient.Fetched(s.pollingStations(), s.pollingStationsUrl()), null);
        }
        String error = DataSnapshotStore.SCANNED.equals(s.pollingStationsError()) ? SCANNED_LIST
                : "The polling station list could not be read (" + s.pollingStationsError()
                  + "); search works by polling station number only.";
        return join(acNumber, form20, null, error);
    }

    static PollingAreaResult join(int acNumber, ConstituencyResult form20, PollingStationListClient.Fetched list,
                                  String listError) {
        List<String> warnings = new ArrayList<>();
        if (listError != null) {
            warnings.add(listError);
        }
        Map<String, PollingStationInfo> byNumber = new HashMap<>();
        if (list != null) {
            Integer listAc = list.list().acNumber();
            if (listAc != null && listAc != acNumber) {
                // the site has linked another constituency's list (seen for AC 234 -> AC 229)
                warnings.add("The official site links the polling station list of constituency " + listAc
                        + " for this constituency, so polling areas are not shown.");
                list = null;
            } else {
                for (PollingStationInfo s : list.list().stations()) {
                    byNumber.putIfAbsent(key(s.psNo()), s);
                }
            }
        }

        List<AreaStation> stations = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        int matched = 0;
        for (PollingStation p : form20.pollingStations()) {
            PollingStationInfo info = byNumber.get(key(p.serialNo()));
            if (info != null) {
                matched++;
            } else if (list != null) {
                unmatched.add(p.serialNo());
            }
            stations.add(new AreaStation(p.serialNo(), info, p.votes()));
        }
        if (!unmatched.isEmpty()) {
            warnings.add(unmatched.size() + " polling station(s) are not in the polling station list, so their areas are not shown: "
                    + String.join(", ", unmatched.subList(0, Math.min(15, unmatched.size())))
                    + (unmatched.size() > 15 ? " ..." : ""));
        }
        if (list != null) {
            long noAreas = stations.stream().filter(s -> s.info() != null && s.info().areas().isBlank()).count();
            if (noAreas > 0) {
                warnings.add(noAreas + " polling station(s) have no polling areas read from the list; "
                        + "check the original PDF for those.");
            }
        }
        return new PollingAreaResult(form20, stations, matched, list == null ? null : list.pdfUrl(), warnings);
    }

    /** "12", "012", "12 A" and "12-a" all match. */
    static String key(String psNo) {
        String k = psNo.toUpperCase(Locale.ROOT).replaceAll("[^0-9A-Z]", "");
        return k.replaceFirst("^0+(?=\\d)", "");
    }
}
