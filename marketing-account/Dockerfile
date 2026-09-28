# marketing-account 镜像（FULL 扩容档：消费者账号 / 会话 / 身份事件）
# 构建前先 mvn package（fat jar 由 spring-boot repackage 产出，带 -exec 后缀）
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/marketing-account-1.0.0-SNAPSHOT-exec.jar app.jar
# 不 EXPOSE：账号服务只走网关的 /api/auth/**，容器形态不发布宿主端口
# 堆与后台同档：登录口是人点出来的低频操作，BCrypt 单次 50-100ms 只在注册/登录/改密上
ENV JAVA_OPTS="-Xmx256m -Xss512k -XX:MaxMetaspaceSize=192m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar $APP_ARGS"]
