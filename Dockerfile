# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 配置阿里云 Maven 镜像源（提升国内阿里云服务器构建下载速度）
RUN mkdir -p /root/.m2 && printf '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"\n\
xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"\n\
xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">\n\
  <mirrors>\n\
    <mirror>\n\
      <id>aliyunmaven</id>\n\
      <mirrorOf>central</mirrorOf>\n\
      <name>aliyun maven</name>\n\
      <url>https://maven.aliyun.com/repository/public</url>\n\
    </mirror>\n\
  </mirrors>\n\
</settings>\n' > /root/.m2/settings.xml

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

# 确保 uid/gid 1000 用户存在（部分 Ubuntu 基础镜像自带 ubuntu:1000 用户，无需重复创建）
RUN if ! getent group 1000 >/dev/null 2>&1; then groupadd -g 1000 app; fi \
    && if ! getent passwd 1000 >/dev/null 2>&1; then useradd -u 1000 -g 1000 -r -m app; fi \
    && mkdir -p /app/uploads/meeting-audio /app/uploads/knowledge \
    && chown -R 1000:1000 /app

COPY --from=build /build/app.jar /app/app.jar

USER 1000:1000
EXPOSE 8080

# 项目未引入 actuator，K8s 存活/就绪探针使用 TCP 端口探测（见 k8s/*.yaml）
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
