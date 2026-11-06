# syntax=docker/dockerfile:1
# 백엔드 앱 공용 이미지. 빌드할 모듈을 MODULE로 고른다 (coupon-api, coupon-worker, simulator).
#   docker build -f backend/Dockerfile --build-arg MODULE=coupon-api backend

FROM eclipse-temurin:21-jdk AS build
ARG MODULE
WORKDIR /src
# 의존성 다운로드를 소스와 분리해서, 소스만 바뀌면 이 층은 캐시를 쓴다
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY common/build.gradle common/
COPY coupon-api/build.gradle coupon-api/
COPY coupon-worker/build.gradle coupon-worker/
COPY simulator/build.gradle simulator/
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q :${MODULE}:dependencies > /dev/null
COPY . .
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q :${MODULE}:bootJar \
    && cp ${MODULE}/build/libs/*.jar /app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app.jar app.jar
# 컨테이너 메모리 제한에 맞춰 힙을 잡는다 (제한의 75%)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
