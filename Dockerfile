# 多阶段构建。同一镜像以不同运行角色启动（ADR-0001）：
#   docker run ... payments-ledger --spring.profiles.active=api
#   docker run ... payments-ledger --spring.profiles.active=worker

# ---------- build ----------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

# 先只复制 wrapper 与 pom，利用层缓存避免每次改代码都重新解析依赖
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
# 跳过测试：镜像构建不承担验证职责，测试在 CI 中独立执行并留存证据
RUN ./mvnw -B -q clean package -DskipTests

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# 非 root 运行
RUN addgroup -S app && adduser -S -G app app
USER app

COPY --from=build --chown=app:app /build/target/*.jar /app/application.jar

# 运行角色必须显式传入，镜像不预设默认角色（RunRoleConfig 会快速失败）
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC"

EXPOSE 8080 8081

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/application.jar \"$@\"", "--"]
