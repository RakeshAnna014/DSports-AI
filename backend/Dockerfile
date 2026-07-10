# Build stage — compile and package all modules
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build

# Copy POM files first for dependency caching
COPY pom.xml .
COPY shared/pom.xml shared/pom.xml
COPY identity/pom.xml identity/pom.xml
COPY catalog/pom.xml catalog/pom.xml
COPY inventory/pom.xml inventory/pom.xml
COPY cart/pom.xml cart/pom.xml
COPY order/pom.xml order/pom.xml
COPY payment/pom.xml payment/pom.xml
COPY billing/pom.xml billing/pom.xml
COPY returns/pom.xml returns/pom.xml
COPY franchise/pom.xml franchise/pom.xml
COPY notification/pom.xml notification/pom.xml
COPY admin/pom.xml admin/pom.xml
COPY ai/pom.xml ai/pom.xml
COPY bootstrap/pom.xml bootstrap/pom.xml

# Download all dependencies (cached until any POM changes)
RUN mvn dependency:go-offline -pl bootstrap -am -q

# Copy all source files
COPY . .

# Build the application
RUN mvn package -DskipTests -pl bootstrap -am

# Run stage — minimal JRE image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create non-root user for security
RUN addgroup -S dsports && adduser -S dsports -G dsports

COPY --from=build /build/bootstrap/target/*.jar app.jar

USER dsports

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
