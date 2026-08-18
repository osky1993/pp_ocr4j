# pp-ocr4j CPU 版镜像
# 用途：宿主机 glibc 过旧（如 CentOS 7，glibc 2.17）无法直接跑 onnxruntime ≥1.17
#      （要求 glibc ≥2.28）时，用容器自带的新 glibc 运行。
# 构建：scripts/package-docker.sh（会先确保 fat jar 已构建，并固定 --platform linux/amd64）
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# 模型与生产配置烘焙进镜像；部署时也可用 -v 挂载覆盖 /app/models、/app/config
COPY models/ppocr-v6 models/ppocr-v6
COPY deploy/application-prod.yml config/application-prod.yml
COPY target/pp-ocr4j-*.jar app.jar

ENV JAVA_OPTS="-Xms512m -Xmx2g -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Shanghai"
EXPOSE 8080

ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -jar app.jar --spring.profiles.active=prod"]
