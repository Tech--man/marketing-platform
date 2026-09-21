# 网关镜像（Full/Lite 通用；Lite 编排中通过 ACTIVITY_PORT 等 env 指向 standalone）
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/marketing-gateway-1.0.0-SNAPSHOT-exec.jar app.jar
EXPOSE 8090
ENV JAVA_OPTS="-Xmx512m -XX:MaxMetaspaceSize=192m"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
