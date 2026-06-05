FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x ./mvnw && ./mvnw dependency:go-offline -B
COPY src ./src
RUN ./mvnw package -DskipTests -B


FROM eclipse-temurin:17-jre-alpine AS runtime

RUN apk add --no-cache \
    tesseract-ocr \
    tesseract-ocr-data-eng \
    tesseract-ocr-data-osd \
    && tesseract --version \
    && ls /usr/share/tessdata/eng.traineddata

WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

ENV TESSERACT_DATA_PATH=/usr/share/tessdata

EXPOSE 8080

ENTRYPOINT ["java", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]