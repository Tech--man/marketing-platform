# marketing-coupon 镜像（FULL 扩容档：一容器一实例，可 --scale 多副本 + nacos 服务发现）
# 构建前先 mvn package（fat jar 由 spring-boot repackage 产出，带 -exec 后缀）
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/marketing-coupon-1.0.0-SNAPSHOT-exec.jar app.jar
EXPOSE 8082
# 堆与元空间按服务给定；GC 用 G1（SerialGC 的 Full GC 停全部线程，不适用于常态服役）
# APP_ARGS 用于注入 --spring.profiles.active=nacos 等程序参数
ENV JAVA_OPTS="-Xmx512m -Xss512k -XX:MaxMetaspaceSize=256m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar $APP_ARGS"]
