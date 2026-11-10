package com.election.ai.service;

import com.election.ai.model.Form20Sheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A saved copy of what was read from the official sites for each constituency: the parsed Form 20
 * sheet, the Form 7A candidate list and the polling station list. The 2026 results are final, so
 * this copy is used instead of the live sites, which only answer requests from India (the app
 * may be hosted elsewhere). Files: {@code classpath:data/AC001.json.gz}, one per constituency.
 */
@Component
public class DataSnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(DataSnapshotStore.class);

    /**
     * What was read for one constituency. A source that could not be read has its error instead
     * ({@link #SCANNED} when the PDF is a scanned image with no text).
     */
    public record Snapshot(int acNumber, Instant capturedAt,
                           Form20Sheet form20, String form20Error,
                           Form7AClient.Result form7a, String form7aError,
                           PollingStationListParser.Result pollingStations, String pollingStationsUrl,
                           String pollingStationsError) {
    }

    public static final String SCANNED = "scanned";

    private final JsonMapper json;
    private final String location;
    private final Map<Integer, Optional<Snapshot>> cache = new ConcurrentHashMap<>();

    public DataSnapshotStore(JsonMapper json, @Value("${data.snapshot-location:data/}") String location) {
        this.json = json;
        this.location = location;
    }

    public Optional<Snapshot> find(int acNumber) {
        return cache.computeIfAbsent(acNumber, this::load);
    }

    private Optional<Snapshot> load(int acNumber) {
        ClassPathResource res = new ClassPathResource(location + fileName(acNumber));
        if (!res.exists()) {
            return Optional.empty();
        }
        try (InputStream in = new GZIPInputStream(res.getInputStream())) {
            return Optional.of(json.readValue(in, Snapshot.class));
        } catch (IOException | RuntimeException e) {
            log.warn("Saved data for AC {} could not be read: {}", acNumber, e.toString());
            return Optional.empty();
        }
    }

    /** Writes a snapshot file (used by the export tool that refreshes the saved data). */
    public void write(Path dir, Snapshot snapshot) throws IOException {
        Files.createDirectories(dir);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(dir.resolve(fileName(snapshot.acNumber()))))) {
            json.writeValue(out, snapshot);
        }
    }

    static String fileName(int acNumber) {
        return String.format("AC%03d.json.gz", acNumber);
    }
}
