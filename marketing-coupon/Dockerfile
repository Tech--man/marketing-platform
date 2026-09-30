# marketing-coupon 镜像（FULL 扩容档：一容器一实例，可 --scale 多副本 + nacos 服务发现）
# 构建前先 mvn package（fat jar 由 spring-boot repackage 产出，带 -exec 后缀）
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/marketing-coupon-1.0.0-SNAPSHOT-exec.jar app.jar
EXPOSE 8082
# 堆与元空间按服务给定；GC 用 G1（SerialGC 的 Full GC 停全部线程，不适用于常态服役）
# APP_ARGS 用于注入 --spring.profiles.active=nacos 等程序参数
ENV JAVA_OPTS="-Xmx512m -Xss512k -XX:MaxMetaspaceSize=256m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
# W5.2（2026-09-30 第二轮复审）：非 root 运行——容器内 RCE 不直接是 root，
# 与编排的回环绑定/最小暴露同一收口方向。应用无卷写需求，只 chown 工作目录。
RUN useradd -r -u 10001 app
USER app
# 实测踩坑（2026-09-30 全栈冒烟）：容器 JVM 默认 UTC，而 DB 链路是
# serverTimezone=Asia/Shanghai——凡是 "DB 时间列 < LocalDateTime.now()" 的判定
# （到期 FINISH Job、超时取消扫描）两边差 8 小时全部落空。TZ 与 DB 时区对齐。
ENV TZ=Asia/Shanghai

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar $APP_ARGS"]
