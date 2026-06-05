# ── Stage 1: Build ────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app

# Cache dependencies first
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x ./mvnw \
    && MAVEN_OPTS="-Xmx512m -XX:MaxMetaspaceSize=192m" \
       ./mvnw dependency:go-offline -B

# Build app
COPY src ./src
RUN MAVEN_OPTS="-Xmx512m -XX:MaxMetaspaceSize=192m" \
    ./mvnw package -DskipTests -B

# ── Stage 2: Runtime ──────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Install Tesseract — on Alpine, tesseract-ocr already bundles eng.traineddata
# Do NOT add tesseract-ocr-data-eng separately — causes build failure on Alpine 3.18+
RUN apk add --no-cache \
    tesseract-ocr \
    tesseract-ocr-data-osd \
    && tesseract --version \
    && ls /usr/share/tessdata/

WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Tesseract data path — Alpine installs here
ENV TESSERACT_DATA_PATH=/usr/share/tessdata

EXPOSE 8080

# Hard -Xmx cap is MANDATORY — Tess4J native memory lives outside heap
# Without Xmx, JVM + native Tesseract + Metaspace easily exceeds 512MB
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