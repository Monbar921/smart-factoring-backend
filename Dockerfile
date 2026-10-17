# app/target/app.jar собирает `mvn package` (spring-boot-maven-plugin, слои по умолчанию)

FROM eclipse-temurin:25-jre-alpine AS extract
WORKDIR /build
COPY app/target/app.jar app.jar
# раскладывает jar по каталогам слоёв: в каждом lib/ с jar'ами, в application ещё и app.jar с Class-Path на lib/
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
USER appuser

# от редко меняющихся к часто меняющимся, чтобы при обычной сборке пересобирался только последний слой
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
