# API 文档

交付文档 §8 给出统一响应、错误码和 43 个核心接口。[openapi.json](openapi.json) 是 S0 草案，含 61 个操作；用 `python scripts/generate_openapi.py` 从追踪表和 [业务契约](S0_BUSINESS_CONTRACT.md) 重新生成。草案中的大多数路径尚未实现，不能将文档操作数当作已交付接口数。

目前仅 `local` profile 提供 `POST /api/dev/token` 与 `GET /api/demo/vehicles/{id}` 作为 JWT 和资源归属校验样例；默认 profile 不暴露演示接口。生产认证、支付和其他业务 API 将按后续阶段实现。
