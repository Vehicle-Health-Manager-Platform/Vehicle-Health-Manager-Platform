# 微信云托管部署镜像。构建上下文为**仓库根目录**。
#
# 云托管限制：仅单个监听端口、不支持 Docker Compose、不支持容器内持久化存储，
# 因此 MySQL/Redis/RabbitMQ/对象存储必须使用容器外服务。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY backend/pom.xml ./
COPY backend/src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S -G app app
COPY --from=build /workspace/target/platform-backend-0.1.0-SNAPSHOT.jar /app/app.jar
USER app
# 微信云托管默认监听 80 端口；若在「服务设置」改端口，需与此处保持一致。
ENV SERVER_PORT=80
EXPOSE 80
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
