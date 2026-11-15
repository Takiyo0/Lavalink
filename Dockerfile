FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /src

COPY . .

RUN chmod +x gradlew && ./gradlew :Lavalink-Server:bootJar --no-daemon

FROM eclipse-temurin:18-jre-jammy

# Run as non-root user
RUN groupadd -g 322 lavalink && \
    useradd -r -u 322 -g lavalink lavalink

WORKDIR /opt/Lavalink

RUN chown -R lavalink:lavalink /opt/Lavalink

USER lavalink

COPY --from=builder /src/LavalinkServer/build/libs/Lavalink.jar Lavalink.jar

EXPOSE 2333

ENTRYPOINT ["java", "-jar", "Lavalink.jar"]
