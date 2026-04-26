FROM maven:3.9-eclipse-temurin-17 as builder
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /build/target/javadb.jar .
EXPOSE 9000
ENTRYPOINT ["java", "-jar", "javadb.jar"]
