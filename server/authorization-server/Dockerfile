FROM eclipse-temurin:17-jdk AS build
WORKDIR /build
COPY gradlew ./
COPY gradle/wrapper/ gradle/wrapper/
COPY authorization-server/build.gradle.kts authorization-server/settings.gradle.kts authorization-server/
COPY authorization-server/src/main/ authorization-server/src/main/
COPY site/ site/
RUN ./gradlew -p authorization-server --no-daemon bootJar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/authorization-server/build/libs/autunn-play-authorization-1.0.0.jar /app/server.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
