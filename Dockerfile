# Build stage: Red Hat UBI OpenJDK 21 image ships with Maven
FROM registry.access.redhat.com/ubi9/openjdk-21:latest AS build
USER root
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Runtime stage: runs any jar in /deployments with container-aware JVM settings.
# Works with OpenShift's arbitrary non-root UID (restricted-v2 SCC).
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:latest
COPY --from=build /build/target/election-ai-*.jar /deployments/app.jar
EXPOSE 8080
USER 185
