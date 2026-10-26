# CI(build-push)가 ./gradlew :services:booking:bootJar 로 만든 build/libs/app.jar 를 복사한다 (멀티 스테이지 불필요)
# 규칙: docs/service-runtime-contract.md 4장 — 비루트(UID 10001, K8s runAsNonRoot), 쓰기 경로는 /tmp(emptyDir)만
FROM eclipse-temurin:21-jre
RUN useradd -r -u 10001 -d /app -s /usr/sbin/nologin app
WORKDIR /app
COPY build/libs/app.jar app.jar
# Flyway 훅 Job의 initContainer가 이 폴더를 emptyDir로 복사해 간다 (주석은 줄 끝에 쓰면 COPY 인자가 된다)
COPY src/main/resources/db/migration /app/db/migration
USER 10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"
ENTRYPOINT ["java", "-jar", "app.jar"]
