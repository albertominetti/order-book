# Multi-stage Docker build for the order-book Spring Boot REST API.
#
# Stage 1 "build"   : Maven compiles the sources (including the Vue frontend) and produces the jar.
# Stage 2 "runtime" : a slim JRE-only image that runs that jar as a non-root user.
#                     The final image carries no Maven, no sources and no build cache.

# ---------------------------------------------------------------------------
# Stage 1: build
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /build

# pom.xml is copied on its own first: as long as it does not change this layer
# stays cached and the dependency download is not repeated on every code change.
COPY pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline

# The Java sources AND the frontend sources are needed: the frontend-maven-plugin builds the
# Vue SPA into target/classes/static/app during the Maven package phase, so `frontend/` must be
# present or `npm install` fails.
COPY src ./src
COPY frontend ./frontend
RUN mvn -B -DskipTests package

# ---------------------------------------------------------------------------
# Stage 2: runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre

WORKDIR /app

# Dedicated unprivileged account: the service never runs as root.
RUN groupadd --system app \
    && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app

COPY --from=build /build/target/order-book-*.jar /app/app.jar

USER app

EXPOSE 8080

# Tuned for small free instances: cap the heap at 75% of the container memory and
# use the Serial GC, which has a far smaller footprint than G1 on 512 MB boxes.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
