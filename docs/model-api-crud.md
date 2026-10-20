# Model API 控制面与发布协调

实现已从内存 CRUD 扩展为可选 PostgreSQL 存储和独立 Go 协调器。完整设计、部署方式、验证命令和当前翻译能力边界见根仓库 `reconciler/README.md`。

Console 入口：AI 网关管理 → Model API 管理（`/ai/model-api`）。支持搜索、分页、新建、编辑、删除确认、中英文、高级 JSON 对象编辑、配置/已同步版本展示和 5 秒状态刷新。页面通过 capabilities 接口区分演示与 PostgreSQL 模式。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/v1/ai/model-apis/_capabilities` | 当前存储模式 |
| GET | `/v1/ai/model-apis` | `gwInstanceId`、`keyword`、`pageNum`、`pageSize` |
| POST | `/v1/ai/model-apis` | 创建完整配置；PG 初始版本 1、appliedVersion 0、PENDING |
| GET | `/v1/ai/model-apis/{name}` | 可选 gwInstanceId；详情含双版本、状态、最近错误和协调时间 |
| PUT | `/v1/ai/model-apis/{name}` | 完整快照更新，带 gwInstanceId 和 version；成功后自动协调 |
| DELETE | `/v1/ai/model-apis/{name}` | 带 version 和 gwInstanceId；PG 软删除，协调器异步清理 |

相同实例内名称唯一；apiName 不可变；并发更新/删除返回 409。用户写入不覆盖 appliedVersion、协调错误和检查时间。所有接口沿用 Console 会话鉴权。

`MODEL_API_STORAGE=postgres` 启用 JDBC。默认 memory 模式保留演示记录，服务重启后重置，不进行协调。

前端生产构建、后端编译、原 CRUD/HTTP 会话鉴权测试及真实 PostgreSQL 集成测试均有验证。全量 TypeScript 检查存在既有 ICE/ProviderForm 等错误，新增 Model API 文件独立检查无诊断。
