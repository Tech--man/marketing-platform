# 网关镜像（预览形态编排使用；通过 ACTIVITY_PORT 等 env 指向 standalone）
# JAVA_OPTS 默认值仅对裸 docker run 生效，编排里由 compose 的 environment 覆盖
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/marketing-gateway-1.0.0-SNAPSHOT-exec.jar app.jar
EXPOSE 8090
ENV JAVA_OPTS="-Xmx192m -Xss512k -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
