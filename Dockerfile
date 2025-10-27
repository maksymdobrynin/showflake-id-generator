# ---------- Build stage ----------
FROM eclipse-temurin:21-jdk-jammy as builder
WORKDIR /app

COPY build.gradle.kts settings.gradle.kts gradlew ./
COPY gradle gradle

RUN ./gradlew dependencies || return 0

COPY src src

RUN ./gradlew clean build -x test

# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN useradd --system --create-home --uid 1001 appuser

USER appuser

COPY --chown=appuser:appuser --from=builder /app/build/libs/*-SNAPSHOT.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
