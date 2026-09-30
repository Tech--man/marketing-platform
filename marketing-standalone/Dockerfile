# 预览形态聚合服务镜像（服务器 amd64 直接 docker compose build；
# Apple Silicon 本机构建的 arm64 镜像仅用于本机验证，不可上传 x86 服务器）
# JAVA_OPTS 默认值仅对裸 docker run 生效，编排里由 compose 的 environment 覆盖
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/marketing-standalone-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8085
ENV JAVA_OPTS="-Xmx320m -Xss512k -XX:MaxMetaspaceSize=160m -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
# W5.2（2026-09-30 第二轮复审）：非 root 运行——容器内 RCE 不直接是 root，
# 与编排的回环绑定/最小暴露同一收口方向。应用无卷写需求，只 chown 工作目录。
RUN useradd -r -u 10001 app
USER app

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
