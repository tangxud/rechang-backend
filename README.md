# Rechang Backend — 热场票务平台后端

Spring Boot 3.2.5 / Java 17 单体应用，承载 C 端小程序的全部 HTTP 与 WebSocket 服务。

## 模块

| 模块 | 说明 |
|------|------|
| `rechang-common` | 统一响应体（Result/ResultCode）、全局异常处理、JWT、工具类 |
| `rechang-api` | 可运行应用：controller / service / mapper(MyBatis-Plus) / entity / websocket |

## 本地启动

```bash
# 1. 启动中间件（MySQL 8 / Redis 6 / ES / RabbitMQ），需与工作区文档仓库同父目录
docker compose -f ../rechang/docker-compose.dev.yml up -d

# 2. 运行（默认 dev profile，密钥用内置默认值）
mvn spring-boot:run -pl rechang-api

# 3. 测试（纯 Mockito 单测，无需容器）
mvn test
```

多环境配置说明见工作区仓库 `rechang/docs/design/environment_config.md`。

## 设计依据

数据库 29 张表、API 契约 118 个接口的设计文档位于工作区仓库 `rechang/docs/`。
