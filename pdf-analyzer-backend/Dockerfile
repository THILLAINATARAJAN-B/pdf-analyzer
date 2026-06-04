# ─── Stage 1: Build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN apk add --no-cache maven && \
    mvn clean package -DskipTests --no-transfer-progress

# ─── Stage 2: Run ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Create non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /app/target/*.jar app.jar

RUN chown appuser:appgroup app.jar

USER appuser

EXPOSE 8080

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]