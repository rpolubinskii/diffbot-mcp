FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x ./gradlew

COPY src ./src
RUN ./gradlew --no-daemon bootJar \
    && find build/libs -maxdepth 1 -type f -name "*.jar" ! -name "*-plain.jar" -exec cp {} /workspace/app.jar \;

FROM eclipse-temurin:25-jre

WORKDIR /app

COPY --from=build /workspace/app.jar ./app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
