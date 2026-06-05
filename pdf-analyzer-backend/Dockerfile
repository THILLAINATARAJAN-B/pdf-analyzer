# ── Stage 1: Build ────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app

COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x ./mvnw \
    && MAVEN_OPTS="-Xmx512m -XX:MaxMetaspaceSize=192m" \
       ./mvnw dependency:go-offline -B

COPY src ./src
RUN MAVEN_OPTS="-Xmx512m -XX:MaxMetaspaceSize=192m" \
    ./mvnw package -DskipTests -B


# ── Stage 2: Runtime ──────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# cache-bust: 2026-06-05 — force clean Tesseract reinstall
# tesseract-ocr on Alpine 3.18+ already includes eng.traineddata
# tesseract-ocr-data-eng removed — not a separate package on Alpine 3.18+
RUN apk add --no-cache \
        tesseract-ocr \
        tesseract-ocr-data-osd \
    && tesseract --version \
    && ls -la /usr/share/tessdata/ \
    && test -f /usr/share/tessdata/eng.traineddata \
    && echo "✅ eng.traineddata verified" \
    || (echo "❌ eng.traineddata MISSING" && exit 1)

WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

ENV TESSERACT_DATA_PATH=/usr/share/tessdata

EXPOSE 8080

ENTRYPOINT ["java", \
  "-Xmx320m", \
  "-Xms64m", \
  "-XX:MaxMetaspaceSize=128m", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=65.0", \
  "-XX:+UseG1GC", \
  "-XX:G1HeapRegionSize=4m", \
  "-Djava.awt.headless=true", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "app.jar"]