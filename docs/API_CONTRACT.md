# 接口契约

公共前缀 `/api`，JSON 使用 camelCase。成功返回对象或数组；错误为 `{code,message}` 和对应 HTTP 状态。列表当前返回数组，未引入服务端分页；适合当前毕业设计规模。日期 ISO 8601 带 UTC 时区，前端按北京时间显示。

登录后使用 `Authorization: Bearer <token>`。写请求不使用 Cookie，因此不需要依赖 Cookie 的 CSRF 策略；跨站 CORS 默认不开放。重要创建和借还操作要求 `Idempotency-Key`，同用户、操作、键、内容返回同一结果，同键异体返回 409。

## 账户与公共元数据

| 方法和路径 | 参数或结果 |
| --- | --- |
| `POST /auth/login` | `{username,password}` → `{token,user}` |
| `POST /auth/logout` | 撤销当前 token |
| `GET /auth/me` | id、username、name、roles、labIds、enabled |
| `PUT /auth/profile` | `{name}`，不能修改角色和范围 |
| `PUT /auth/password` | `{oldPassword,newPassword}`，至少 8 位，旧会话失效 |
| `GET /health` | 服务和数据库状态；不暴露凭据 |
| `GET /laboratories`、`GET /categories` | 设备筛选元数据 |
| `GET /technicians` | 实验员/管理员分派时查询有效维修人员 |
| `GET /dashboard` | 当前角色的设备、借用、预约、工单与最近记录 |

## 设备与借还

| 方法和路径 | 参数或行为 |
| --- | --- |
| `GET /equipment?search=&labId=&categoryId=&availableOnly=` | 设备档案、运行状态、实物占用、当前可借状态 |
| `GET /equipment/{id}` | 档案、禁借原因、已批准预约时段、状态事件 |
| `GET /equipment/{id}/availability?startsAt=&endsAt=` | 指定时段预检；提交与审批仍重新检查 |
| `POST /equipment`、`PUT /equipment/{id}` | assetNo、name、model、categoryId、labId、purchasedOn、description |
| `POST /equipment/{id}/toggle` | `{enabled,reason}`，限定负责实验室 |
| `GET /reservations` | 本人或负责范围内的记录 |
| `POST /reservations` | `{equipmentId,startsAt,endsAt,purpose}`；必须幂等键 |
| `POST /reservations/{id}/approve` | 实验员审批，不能审批本人申请 |
| `POST /reservations/{id}/reject` | `{reason}` |
| `POST /reservations/{id}/cancel` | 本人或有权实验员取消未发放预约 |
| `POST /reservations/{id}/issue` | 实验员发放，必须幂等键、合法时段、无禁借和未归还占用 |
| `GET /loans?activeOnly=`、`GET /loans/{id}` | 当前授权范围的借用和事件 |
| `POST /loans/{id}/return` | `{photoIds,note,damaged?,faultDescription?}`；只能本人，必须幂等键；成功即归还 |
| `POST /loans/{id}/check` | 实验员归还后检查，不是归还的前置条件 |

## 工单、图片和通知

`POST /tickets` 接收 equipmentId、title、description、photoIds、severity，可选 loanId；需要幂等键。`GET /tickets` 和 `GET /tickets/{id}` 按报修人、实验室或当前指派返回，详情包含时间线。

动作接口为 `POST /tickets/{id}/{action}`：

| action | 参数 |
| --- | --- |
| verify | `{affectsUse,note?}`，实验员核实 |
| reject | `{note}`，驳回待核实工单 |
| cancel | 本人撤回待核实工单 |
| assign | `{assigneeId,note?}`，分派有效维修人员 |
| accept / start-repair | 受理、开始维修 |
| note | `{note}`，当前维修人员补充过程 |
| return-assignment | `{note}`，无法处理时退回分派 |
| submit-acceptance | `{resolution,laborMinutes}`，累计维修工时 |
| acceptance | `{passed,note}`，非执行者的实验员验收或退回 |

`POST /files` 使用 multipart `file`，20MB 上限，图片为 JPG/PNG，文档为 PDF/DOCX/TXT/Markdown。返回 id、name、mimeType、sizeBytes。`GET /files/{id}` 使用相同 Bearer 鉴权；照片只对所有者及授权关联记录可见，页面通过 Blob 展示，不把 token 放 URL。

`GET /notifications` 查询本人通知；`POST /notifications/{id}/read` 标记已读，`POST /notifications/read-all` 标记本人全部已读。逾期按天、到期前提醒按对象与日期去重。

## 知识与统计

| 方法和路径 | 行为 |
| --- | --- |
| `GET /knowledge`、`GET /knowledge/{id}` | 发布可见资料；管理者可以查看自己范围内草稿 |
| `POST /knowledge` | `{title,text?,fileId?,model,category,labId?,visibility}`，创建文档与初始版本 |
| `POST /knowledge/{id}/versions` | 保存新版本，正文不可原位覆盖 |
| `POST /knowledge/{id}/index` | `{versionId}`，提交异步解析与向量任务 |
| `POST /knowledge/{id}/publish` | `{versionId}`，READY 后审核发布 |
| `POST /knowledge/{id}/toggle` | `{enabled}`，停用立即影响新检索 |
| `POST /knowledge/search` | `{query,model?,category?}`，返回授权片段、来源、版本、定位、score |
| `GET /statistics?days=30&labId=` | 管理范围内分类、状态、借用趋势、故障与工单分布、利用率和设备明细 |

## 对话与 AI

`POST /chat` 要求 Idempotency-Key，接收 `{conversationId?,text,photoIds?,context?}`，返回 `{runId,conversationId,status}`。context 支持当前 labId、ticketId、returnLoanId；服务端生成权限快照，不能由 context 授权。仅管理员评测可指定 evaluationMode 为 DIRECT/AGENT_RAG。

`GET /ai-runs/{id}` 返回状态、模式、错误、工具记录、供应商用量（未提供则为空）。`GET /conversations` 查询本人历史，`GET /conversations/{id}` 返回本人消息与执行摘要，`DELETE /conversations/{id}` 删除非执行中的本人对话，业务记录保留。

消息卡片 type 为 equipment、loan、reservation、ticket 或 statistics，data 为真实工具查询结果。引用包含 chunkId、documentId、versionId、title、versionNo、locator、excerpt。卡片操作调用普通借还接口，不能由模型任意指定最终状态。

## 管理与内部接口

管理员接口包括 `/admin/users` 创建/列表、`PUT /admin/users/{id}` 更新角色/范围/启停/密码；`/admin/laboratories` 创建及更新；`POST /admin/categories`；`GET /admin/roles`；`GET /admin/audit-logs`。

当前没有节假日和临时闭馆日历的维护接口。业务列表尚未提供通用服务端分页；会话返回最近 100 条、通知 150 条、审计 300 条，暂无更早历史的翻页接口。这些缺口记录于 `REVIEW_2026-11-04.md`。

`GET/PUT /admin/ai-settings` 管理 GENERATION 和 EMBEDDING 的 BASE_URL、MODEL、API_KEY。读取仅返回密钥配置布尔值，空密钥保留原值。`POST /admin/ai-test` 测试已保存的对话模型，属于实际供应商调用。

Python 私有端点为 `/internal/agent`、`/internal/knowledge/index`、`/internal/knowledge/search`、`/internal/test-model`，均校验 X-Internal-Key。Java `/internal/tools/{tool}` 再要求 X-Run-Id 和 X-Run-Grant，校验执行状态、有效时间、工具预算及当前数据权限；不对外提供通用 SQL、文件路径或权限覆写。

演示账户约定（2026-11-04）：默认角色登录密码为 `123456`，账户创建、密码重置和修改密码最少 6 位。V5 迁移一次性统一已有账户密码并增加 auth_version。

最新简化：模型接入仅保留 GENERATION_BASE_URL、GENERATION_MODEL、GENERATION_API_KEY，停止向浏览器提供或接收 Embedding 配置。资料解析后按关键词检索，不调用向量模型；照片归还不依赖视觉模型。

## 模块更新红点

`GET /module-updates` 返回当前用户授权模块的 `{fingerprint,unread}`，首次查询建立基线。设备、预约、借还、工单、知识、消息、对话、工作台，以及有权限的统计和管理模块分别记录。`POST /module-updates/{module}/read` 接收 `{fingerprint}`，仅清除该账户已查看的这次更新；期间的新变化仍保持未读。前端每5秒刷新，进入模块或再次点击当前模块会更新查看状态。

模型支持图片时发送本轮照片；供应商明确以400/415/422表示不支持图片时，只移除图像内容并保留同一真实模型及工具调用，短时缓存其文本能力。认证错误、429、网络失败不触发该兼容路径。模型连接测试最多生成64个token；429不自动重试，并区分免费活动全局限制、每日额度与频率限制。
