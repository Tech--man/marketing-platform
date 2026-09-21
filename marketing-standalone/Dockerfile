# Lite 形态聚合服务镜像（服务器 amd64 直接 docker compose build；
# Apple Silicon 本机构建的 arm64 镜像仅用于本机验证，不可上传 x86 服务器）
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/marketing-standalone-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8085
ENV JAVA_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=256m -XX:+UseSerialGC"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
