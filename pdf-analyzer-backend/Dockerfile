# ── Stage 1: Build ────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS build

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
FROM eclipse-temurin:17-jre-alpine AS runtime

# Java 17 Alpine has compatible ICU libs for tesseract-ocr-data-eng
# Do NOT upgrade to Java 21 Alpine — ICU version mismatch breaks Tesseract install
RUN apk add --no-cache \
        tesseract-ocr \
        tesseract-ocr-data-eng \
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

# THE ACTUAL FIX FOR OOM — hard Xmx cap was missing from original
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