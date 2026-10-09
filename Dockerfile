FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENV PAYBRIDGE_GATEWAY=simulated
ENV PAYSTACK_SECRET_KEY=sk_test_demo
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]