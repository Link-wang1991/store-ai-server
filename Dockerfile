# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先只复制 pom 以下载依赖，利用 Docker 层缓存：仅改代码时可跳过依赖下载
COPY pom.xml ./
# 注意：dependency:go-offline 不能覆盖全部插件依赖，失败不影响后续 package（会联网补齐）
RUN mvn -B -q dependency:go-offline || true

COPY src ./src
# 用 maven.test.skip 而不是 skipTests：镜像构建环境是干净的 Maven，
# 仅 skipTests 仍会编译测试代码，测试代码一旦有编译问题整个镜像就构建失败。
RUN mvn -B clean package -Dmaven.test.skip=true -q \
    && cp $(ls target/*.jar | grep -v original | head -1) /build/app.jar

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre

# 时区必须与数据库 serverTimezone 一致，避免时间字段错乱
ENV TZ=Asia/Shanghai
# 容器友好：MaxRAMPercentage 让 JVM 感知 cgroup 内存限制（而不是读宿主机内存）
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"

WORKDIR /app

# 以固定 uid/gid 1000 的非 root 用户运行（K8s 侧用 securityContext.fsGroup=1000 对齐，
# 否则 local-path 持久卷默认属主为 root，会导致上传文件写入失败）；预建上传目录作为 PVC 挂载点
RUN groupadd -g 1000 app && useradd -u 1000 -g 1000 -r -m app \
    && mkdir -p /app/uploads/meeting-audio /app/uploads/knowledge \
    && chown -R app:app /app

COPY --from=build /build/app.jar /app/app.jar

USER app
EXPOSE 8080

# 项目未引入 actuator，K8s 存活/就绪探针使用 TCP 端口探测（见 k8s/*.yaml）
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
