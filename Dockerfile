# Valem web server — REST + WebSocket + management UI + the /mcp endpoint, in one image.
#
#   docker build -t valem .
#   docker run --rm -p 8080:8080 valem
#   → http://localhost:8080
#
# Storage is in-memory by default. To persist, mount a volume and point Valem at it:
#   docker run --rm -p 8080:8080 -v valem-data:/data valem --valem.persistence-dir=/data
#
# Build args:
#   SKIP_FRONTEND=true   skip the Node build of the management UI (faster, REST-only image)

# ── build ────────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
ARG SKIP_FRONTEND=false

WORKDIR /src

# Valem's two JSON foundations are not on Maven Central yet, so they are built into the
# builder's local repository first. Pinned by clone depth only — both track their main branch.
RUN apt-get update && apt-get install -y --no-install-recommends git \
 && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 https://github.com/vlad-public-code/org.json-kula.tracked-json.git \
 && mvn -q -B -f org.json-kula.tracked-json/pom.xml install -DskipTests \
 && git clone --depth 1 https://github.com/vlad-public-code/org.json-kula.jsonata-jvm-compiler.git \
 && mvn -q -B -f org.json-kula.jsonata-jvm-compiler/pom.xml install -DskipTests

COPY . /src/valem
WORKDIR /src/valem

# -am builds valem-web's module dependencies (core, service, view, api, and the memory +
# filesystem persistence adapters it bundles by default).
RUN mvn -q -B package -DskipTests -Dskip.frontend=${SKIP_FRONTEND} -pl valem-web -am \
 && cp valem-web/target/valem-web-*.jar /valem-web.jar

# ── run ──────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="Valem" \
      org.opencontainers.image.description="Deterministic reactive computation runtime for AI-generated structured data models" \
      org.opencontainers.image.source="https://github.com/vlad-public-code/org.json-kula.valem" \
      org.opencontainers.image.licenses="Apache-2.0"

# Don't run the server as root.
RUN useradd --system --uid 10001 --create-home valem
USER valem
WORKDIR /home/valem

COPY --from=build /valem-web.jar ./valem-web.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/home/valem/valem-web.jar"]
