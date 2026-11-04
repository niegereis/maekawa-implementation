# Etapa 1: build com Maven (cache de dependencias separado do codigo)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# Etapa 2: runtime enxuto. Uma imagem, tres papeis (ROLE=node|client|observer).
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/target/maekawa.jar /app/maekawa.jar
# Quinze JVMs precisam caber no Ampere Always Free (2 OCPU / 12 GiB) e no kind local:
# heap pequeno, GC serial, sem C2. A imagem e linux/amd64 (kind) e linux/arm64 (A1).
ENV JAVA_OPTS="-XX:+UseSerialGC -Xmx96m -Xss512k -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"
ENV ROLE=node
EXPOSE 7000 7100
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/maekawa.jar"]
