# marketing-admin 镜像（FULL 扩容档：后台账号/会话/审计与运维入口）
# 构建前先 mvn package（fat jar 由 spring-boot repackage 产出，带 -exec 后缀）
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/marketing-admin-1.0.0-SNAPSHOT-exec.jar app.jar
# 不 EXPOSE：后台只走网关，容器形态不发布宿主端口
# 堆给 256m 足够 —— 后台 QPS 是人点出来的，没有热路径；BCrypt 单次 50-100ms 只在登录口
ENV JAVA_OPTS="-Xmx256m -Xss512k -XX:MaxMetaspaceSize=192m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar $APP_ARGS"]
