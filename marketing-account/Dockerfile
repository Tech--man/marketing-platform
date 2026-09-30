# marketing-account 镜像（FULL 扩容档：消费者账号 / 会话 / 身份事件）
# 构建前先 mvn package（fat jar 由 spring-boot repackage 产出，带 -exec 后缀）
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/marketing-account-1.0.0-SNAPSHOT-exec.jar app.jar
# 不 EXPOSE：账号服务只走网关的 /api/auth/**，容器形态不发布宿主端口
# 堆与后台同档：登录口是人点出来的低频操作，BCrypt 单次 50-100ms 只在注册/登录/改密上
ENV JAVA_OPTS="-Xmx256m -Xss512k -XX:MaxMetaspaceSize=192m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
# W5.2（2026-09-30 第二轮复审）：非 root 运行——容器内 RCE 不直接是 root，
# 与编排的回环绑定/最小暴露同一收口方向。应用无卷写需求，只 chown 工作目录。
RUN useradd -r -u 10001 app
USER app
# 实测踩坑（2026-09-30 全栈冒烟）：容器 JVM 默认 UTC，而 DB 链路是
# serverTimezone=Asia/Shanghai——凡是 "DB 时间列 < LocalDateTime.now()" 的判定
# （到期 FINISH Job、超时取消扫描）两边差 8 小时全部落空。TZ 与 DB 时区对齐。
ENV TZ=Asia/Shanghai

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar $APP_ARGS"]
