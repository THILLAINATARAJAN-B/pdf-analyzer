FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw dependency:go-offline -B
COPY src ./src
RUN ./mvnw package -DskipTests -B

FROM eclipse-temurin:17-jre-alpine AS runtime

# Install Tesseract OCR + English language data
RUN apk add --no-cache \
    tesseract-ocr \
    tesseract-ocr-data-eng

WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

ENV TESSERACT_DATA_PATH=/usr/share/tesseract-ocr/5/tessdata

EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]