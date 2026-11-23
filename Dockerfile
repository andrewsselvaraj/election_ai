# Build stage: Red Hat UBI OpenJDK 21 image ships with Maven
FROM registry.access.redhat.com/ubi9/openjdk-21:latest AS build
USER root
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# IP -> city database for visitor statistics (DB-IP Lite, CC BY 4.0, updated monthly).
# Optional: if the download fails the app still runs and shows locations as "Unknown".
# (The image has no gunzip, so a one-line Java program unpacks it.)
RUN mkdir -p /build/geoip && cd /build/geoip && \
    echo 'public class G { public static void main(String[] a) throws Exception { try (var in = new java.util.zip.GZIPInputStream(new java.io.FileInputStream(a[0]), 65536); var out = new java.io.FileOutputStream(a[1])) { in.transferTo(out); } } }' > /tmp/G.java && \
    for m in $(date -u +%Y-%m) $(date -u -d "$(date -u +%Y-%m-15) -1 month" +%Y-%m); do \
      if curl -fsSL --retry 2 -o /tmp/geo.mmdb.gz "https://download.db-ip.com/free/dbip-city-lite-$m.mmdb.gz" \
         && java /tmp/G.java /tmp/geo.mmdb.gz dbip-city-lite.mmdb; then \
        echo "GeoIP database $m"; break; \
      fi; \
      rm -f dbip-city-lite.mmdb; \
    done; \
    rm -f /tmp/geo.mmdb.gz /tmp/G.java; ls -l /build/geoip

# Runtime stage: runs any jar in /deployments with container-aware JVM settings.
# Works with OpenShift's arbitrary non-root UID (restricted-v2 SCC).
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:latest
COPY --from=build /build/target/election-ai-*.jar /deployments/app.jar
COPY --from=build /build/geoip/ /opt/geoip/
EXPOSE 8080
USER 185
