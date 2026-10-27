# The NetTriage service (REST, MCP at /mcp, Kafka consumer, EdgeFleet follower).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY triage-core triage-core
COPY bench bench
COPY agent agent
COPY service service
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B package -DskipTests -pl service -am && cp service/target/service-0.1.0.jar /app.jar

FROM eclipse-temurin:21-jre
COPY --from=build /app.jar /app/app.jar
WORKDIR /app
EXPOSE 8095
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
