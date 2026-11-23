package com.election.ai.analytics;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small Firestore client over its REST API (HTTPS + JSON), using only the JDK's HttpClient and
 * Jackson. It replaces the official SDK, which brings ~40 MB of gRPC/Netty/protobuf libraries.
 *
 * <p>Login: on Cloud Run an access token for the service's account comes from the metadata server
 * (cached until shortly before it expires). For local testing a token can be given instead
 * ({@code gcloud auth print-access-token}).
 *
 * <p>Values: Java String, Long/Integer, Double, Boolean, Instant, Map and null are converted to
 * and from Firestore's typed JSON ({@code stringValue}, {@code integerValue}, ...).
 */
final class FirestoreRest {

    /** In a {@link #commit} map: add {@code by} to the stored number (Firestore increment). */
    record Inc(long by) {
    }

    /** Set-with-merge of {@code data} into the document at {@code path}, e.g. "visitors/ab12". */
    record Write(String path, Map<String, Object> data) {
    }

    private static final String METADATA = "http://metadata.google.internal/computeMetadata/v1/";
    private static final String ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JsonMapper json;
    private final String projectId;
    private final String root;          // projects/{p}/databases/(default)/documents
    private final String base;          // https://firestore.googleapis.com/v1/{root}
    private final String fixedToken;
    private String token;
    private long tokenExpiry;

    FirestoreRest(JsonMapper json, String projectId, String fixedToken) throws IOException, InterruptedException {
        this.json = json;
        this.fixedToken = fixedToken == null || fixedToken.isBlank() ? null : fixedToken.trim();
        this.projectId = projectId == null || projectId.isBlank() ? metadata("project/project-id").trim() : projectId.trim();
        this.root = "projects/" + this.projectId + "/databases/(default)/documents";
        this.base = "https://firestore.googleapis.com/v1/" + root;
        token();                        // fail now, not on the first page view, if there is no login
    }

    String projectId() {
        return projectId;
    }

    /** A random 20-character document id, like the SDK's {@code collection.document()}. */
    static String autoId() {
        StringBuilder b = new StringBuilder(20);
        for (int i = 0; i < 20; i++) b.append(ID_CHARS.charAt(RANDOM.nextInt(ID_CHARS.length())));
        return b.toString();
    }

    // ---------------------------------------------------------------- writing

    /** Applies all writes atomically (at most 500). */
    void commit(List<Write> writes) throws IOException, InterruptedException {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Write w : writes) {
            Map<String, Object> fields = new LinkedHashMap<>();
            List<String> mask = new ArrayList<>();
            List<Map<String, Object>> transforms = new ArrayList<>();
            flatten(w.data(), "", fields, mask, transforms);
            Map<String, Object> write = new LinkedHashMap<>();
            write.put("update", Map.of("name", root + "/" + w.path(), "fields", fields));
            write.put("updateMask", Map.of("fieldPaths", mask));        // merge: only these fields change
            if (!transforms.isEmpty()) write.put("updateTransforms", transforms);
            list.add(write);
        }
        post(":commit", Map.of("writes", list));
    }

    private static void flatten(Map<String, Object> in, String prefix, Map<String, Object> fields,
                                List<String> mask, List<Map<String, Object>> transforms) {
        in.forEach((k, v) -> {
            String path = prefix + segment(k);
            if (v instanceof Inc inc) {
                transforms.add(Map.of("fieldPath", path, "increment", Map.of("integerValue", Long.toString(inc.by()))));
            } else if (v instanceof Map<?, ?> m) {
                Map<String, Object> child = new LinkedHashMap<>();
                flatten(castMap(m), path + ".", child, mask, transforms);
                if (!child.isEmpty()) fields.put(k, Map.of("mapValue", Map.of("fields", child)));
            } else {
                fields.put(k, encode(v));
                mask.add(path);
            }
        });
    }

    /** A field name as a field-path segment: names other than [A-Za-z_][A-Za-z0-9_]* go in backticks. */
    private static String segment(String name) {
        return name.matches("[A-Za-z_][A-Za-z_0-9]*") ? name
                : "`" + name.replace("\\", "\\\\").replace("`", "\\`") + "`";
    }

    private static Map<String, Object> encode(Object v) {
        if (v == null) return Collections.singletonMap("nullValue", null);
        if (v instanceof String s) return Map.of("stringValue", s);
        if (v instanceof Boolean b) return Map.of("booleanValue", b);
        if (v instanceof Integer || v instanceof Long) return Map.of("integerValue", v.toString());
        if (v instanceof Number n) return Map.of("doubleValue", n.doubleValue());
        if (v instanceof Instant t) return Map.of("timestampValue", t.toString());
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> f = new LinkedHashMap<>();
            m.forEach((k, x) -> f.put(String.valueOf(k), encode(x)));
            return Map.of("mapValue", Map.of("fields", f));
        }
        throw new IllegalArgumentException("Cannot store " + v.getClass().getSimpleName() + " in Firestore");
    }

    // ---------------------------------------------------------------- reading

    /** The documents at these paths that exist: path -> fields. Missing documents are left out. */
    Map<String, Map<String, Object>> get(List<String> paths) throws IOException, InterruptedException {
        Map<String, Map<String, Object>> out = new HashMap<>();
        if (paths.isEmpty()) return out;
        List<String> names = paths.stream().map(p -> root + "/" + p).toList();
        for (Object o : parseList(post(":batchGet", Map.of("documents", names)))) {
            if (castMap(o).get("found") instanceof Map<?, ?> doc) {
                out.put(relative(doc), fields(doc));
            }
        }
        return out;
    }

    /** The fields of the first {@code limit} documents of a collection, ordered by one field. */
    List<Map<String, Object>> query(String collection, String orderBy, boolean descending, int limit)
            throws IOException, InterruptedException {
        Map<String, Object> q = Map.of(
                "from", List.of(Map.of("collectionId", collection)),
                "orderBy", List.of(Map.of("field", Map.of("fieldPath", orderBy),
                        "direction", descending ? "DESCENDING" : "ASCENDING")),
                "limit", limit);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : parseList(post(":runQuery", Map.of("structuredQuery", q)))) {
            if (castMap(o).get("document") instanceof Map<?, ?> doc) out.add(fields(doc));
        }
        return out;
    }

    /** Number of documents in a collection (server-side count; not read one by one). */
    long count(String collection) throws IOException, InterruptedException {
        Map<String, Object> q = Map.of(
                "structuredQuery", Map.of("from", List.of(Map.of("collectionId", collection))),
                "aggregations", List.of(Map.of("alias", "n", "count", Map.of())));
        for (Object o : parseList(post(":runAggregationQuery", Map.of("structuredAggregationQuery", q)))) {
            if (castMap(o).get("result") instanceof Map<?, ?> r
                    && r.get("aggregateFields") instanceof Map<?, ?> f
                    && f.get("n") instanceof Map<?, ?> n) {
                Object v = decode(castMap(n));
                return v instanceof Number num ? num.longValue() : 0;
            }
        }
        return 0;
    }

    private String relative(Map<?, ?> doc) {
        String name = String.valueOf(doc.get("name"));
        return name.startsWith(root + "/") ? name.substring(root.length() + 1) : name;
    }

    private static Map<String, Object> fields(Map<?, ?> doc) {
        Map<String, Object> out = new HashMap<>();
        if (doc.get("fields") instanceof Map<?, ?> f) {
            f.forEach((k, v) -> out.put(String.valueOf(k), decode(castMap(v))));
        }
        return out;
    }

    private static Object decode(Map<String, Object> v) {
        if (v.containsKey("stringValue")) return v.get("stringValue");
        if (v.containsKey("integerValue")) return Long.parseLong(String.valueOf(v.get("integerValue")));
        if (v.containsKey("doubleValue")) return ((Number) v.get("doubleValue")).doubleValue();
        if (v.containsKey("booleanValue")) return v.get("booleanValue");
        if (v.containsKey("timestampValue")) return Instant.parse(String.valueOf(v.get("timestampValue")));
        if (v.get("mapValue") instanceof Map<?, ?> m) return fields(m);
        if (v.get("arrayValue") instanceof Map<?, ?> a) {
            List<Object> list = new ArrayList<>();
            if (a.get("values") instanceof List<?> values) values.forEach(x -> list.add(decode(castMap(x))));
            return list;
        }
        return null;                    // nullValue, and types this app does not use
    }

    // ---------------------------------------------------------------- HTTP

    private String post(String action, Object body) throws IOException, InterruptedException {
        String payload = json.writeValueAsString(body);
        for (int attempt = 1; ; attempt++) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + action))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();
            if (code == 200) return res.body();
            if (code == 401 && attempt == 1 && fixedToken == null) {
                synchronized (this) { token = null; }   // expired early: get a new one and try again
                continue;
            }
            // 429 = too many requests, 503 = unavailable: the request was not applied, so retrying is safe
            if ((code == 429 || code == 503) && attempt < 3) {
                Thread.sleep(400L * attempt);
                continue;
            }
            String text = res.body() == null ? "" : res.body().replaceAll("\\s+", " ");
            throw new IOException("Firestore " + action + " failed: HTTP " + code + " "
                    + text.substring(0, Math.min(300, text.length())));
        }
    }

    private synchronized String token() throws IOException, InterruptedException {
        if (fixedToken != null) return fixedToken;
        if (token == null || System.currentTimeMillis() > tokenExpiry) {
            Map<String, Object> t = castMap(json.readValue(metadata("instance/service-accounts/default/token"), Map.class));
            long seconds = t.get("expires_in") instanceof Number n ? n.longValue() : 300;
            token = String.valueOf(t.get("access_token"));
            tokenExpiry = System.currentTimeMillis() + Math.max(30, seconds - 300) * 1000;   // renew 5 min early
        }
        return token;
    }

    /** Cloud Run's metadata server (only reachable from inside Google Cloud). */
    private String metadata(String path) throws IOException, InterruptedException {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(METADATA + path))
                        .timeout(Duration.ofSeconds(3))
                        .header("Metadata-Flavor", "Google")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException("Metadata server " + path + ": HTTP " + res.statusCode());
        return res.body();
    }

    private List<?> parseList(String body) {
        return json.readValue(body, List.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object m) {
        return (Map<String, Object>) m;
    }
}
