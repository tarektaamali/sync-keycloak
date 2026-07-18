FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn -q package -DskipTests
FROM eclipse-temurin:21-jre
COPY --from=build /app/target/user-sync-0.1.0.jar /app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
