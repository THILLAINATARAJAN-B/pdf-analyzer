FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x ./mvnw && ./mvnw dependency:go-offline -B
COPY src ./src
RUN ./mvnw package -DskipTests -B

FROM eclipse-temurin:17-jre-alpine AS runtime

# Tesseract on Alpine installs to /usr/share/tessdata — NOT the Ubuntu path
RUN apk add --no-cache \
    tesseract-ocr \
    tesseract-ocr-data-eng \
    && tesseract --version \
    && ls /usr/share/tessdata/eng.traineddata

WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Correct Alpine tessdata path
ENV TESSERACT_DATA_PATH=/usr/share/tessdata

EXPOSE 8080
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.awt.headless=true", \
  "-jar", "app.jar"]