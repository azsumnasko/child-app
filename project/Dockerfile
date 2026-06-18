# --- Build stage ---
FROM gradle:8.10-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle :server:shadowJar --no-daemon -x test

# --- Runtime stage ---
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/server/build/libs/server-*-all.jar /app/server.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
