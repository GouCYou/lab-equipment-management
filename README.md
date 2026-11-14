# 博仪实验室设备管理系统

快速使用：[QUICK_START.md](QUICK_START.md)（各角色登录后做什么、点哪些按钮，以及 AI 示例）。

2026 年 11 月 4 日已统一项目名称并清理页面重复说明。[最新复查与未完成事项](docs/REVIEW_2026-11-04.md)区分实际通过、待验收与尚未实现的能力。

Vue 3 业务工作台、Spring Boot 业务后端、Python 单 Agent 与维修知识库。支持学生预约借用、上传照片直接归还、故障工单处理、模块红点提醒和统计分析。AI 可查询设备、总结借还与工单、分析利用率，并通过授权工具提交预约、归还本人设备及报修。

前端采用靛蓝与浅紫色，布局参考 `short-video-agent` 的导航、对话历史和工作区。一键脚本直接移植该项目的中文菜单、按键、状态、环境检查、依赖缓存和安全启停逻辑，调整了项目名、目录、端口及初始化凭据。

## 本地启动

```sh
bash run.sh
```

macOS 首次使用可以双击 `setup-mac.command`，或执行 `bash run.sh setup`。Windows 双击 `run.bat`。菜单支持方向键、数字定位、Enter 确认、Esc/Q/Ctrl+C 取消；打开菜单不自动启停服务。

```sh
bash run.sh check-env
bash run.sh start          # 调用已配置的模型，缺少配置时明确提示
bash run.sh status
bash run.sh logs backend
bash run.sh stop
```

停止项目保留 MySQL、Redis 和全部数据。脚本检测 PID、创建时间及命令身份，端口冲突不会杀其他项目进程。Windows 命令将 `bash run.sh` 换成 `run.bat`。

需要 Java 21、Node.js 22.12+/24、Maven、Python 3.11+（推荐 3.12）、MySQL 8.x、Redis。Qdrant 1.15.5 自动下载并校验 SHA-256，支持指定本地可执行文件。前端、Python、Java 的依赖分别缓存，只有输入变化才重新安装或构建。

| 服务 | 默认本机地址 |
| --- | --- |
| 前端 | http://127.0.0.1:5188 |
| 后端健康检查 | http://127.0.0.1:18180/api/health |
| Python 内部服务 | 127.0.0.1:18100 |
| Qdrant HTTP / gRPC | 127.0.0.1:17333 / 17334 |

本机脚本沿用参考项目的局域网访问行为，前端和业务后端监听局域网，AI 与 Qdrant 只监听本机。项目数据库为 `lab_equipment_local`，Redis 命名空间包含数据库名称，测试与业务会话隔离。端口可在 `.env.local` 修改。

## 账户与演示数据

毕设演示账户的登录密码统一为 `123456`：

| 角色 | 用户名 | 密码 |
| --- | --- | --- |
| 管理员 | admin | 123456 |
| 学生 | student | 123456 |
| 实验员 | labstaff | 123456 |
| 维修人员 | technician | 123456 |

启动菜单新增“查看各角色账户”，也可执行 `bash run.sh accounts`（Windows：`run.bat accounts`），服务未启动时也能查看。管理员凭据保存在 `.runtime/local/credentials.json`，演示凭据保存在 `.runtime/local/demo-accounts.json`。新增账户默认密码为 `123456`，密码校验允许 6 位。

`.env.local` 中 `SEED_DEMO_DATA=true` 时，空库初始化两个实验室、12 台设备及三个业务角色账户。V5 增量迁移将现有用户登录密码统一为 `123456` 并撤销旧会话；后续普通启停不会重复重置密码或清理业务数据。

当前工作区已准备演示数据，可使用上述凭据文件中的账户。演示设备和人员是本地样例。模型凭据没有从参考项目复制。

## AI 使用方式

- 学生询问“现在有什么设备可以借”，查看真实设备卡片，点击“申请借用”填写时间和用途；仍需实验员审核及发放。
- 学生询问“查看我还没归还的设备”，点击卡片上传照片，或在对话里发送照片并说明归还哪台设备。
- 普通文本模型只需支持工具调用。有本轮照片、明确归还话语和对应的本人借用记录即可归还，无需模型识图；多台借用且目标不明确时先选择设备。
- 实验员选择当前实验室，一键查询归还情况、追踪工单和总结利用率；所有查询仍受本人、实验室、当前指派权限约束。
- 故障工单详情可进入 AI 分析，结合当前工单、设备资料和知识检索生成建议及来源。

“系统管理 → 模型接入”只需配置一个聊天模型的基础地址、模型名称和密钥。密钥只写服务端，后台不原样回显，数据库中的模型密钥以 AES-GCM 加密；保管好项目原有 `AI_INTERNAL_KEY`，否则旧密钥无法解密。

AI 只调用已配置的聊天模型，不再提供演示回复或自动降级为预置回答。临时限流、网关失败和连接异常会最多自动重试 4 次，采用退避等待；密钥、权限与明确额度不足错误直接报告。配置缺失、限流和接口失败都会明确提示。旧对话中的预置回答保留并标为“历史预置回答”。知识库使用关键词检索，不需要向量模型；检索结果交给聊天模型生成带来源的回答。

## 业务操作

| 角色 | 可用操作 |
| --- | --- |
| 学生 | 查设备、提交/取消预约、查询本人记录、上传照片归还、报修和公开知识问答 |
| 实验员 | 管理负责实验室设备、审批、发放、归还后检查、故障核实分派、维修验收、知识发布和统计 |
| 维修人员 | 受理分配工单、记录维修、提交验收、查询必要资料和使用 AI |
| 管理员 | 账户与多角色、实验室范围、类别、开放时间、模型配置、全局业务与操作审计 |

学生提交有效归还照片后即完成归还，实验员可事后检查。故障核实后影响使用则禁止借出，并使未发放的已批准预约失效。维修验收只解除当前工单的限制；其他故障和手工停用继续生效。一个设备最多一条未归还借用，审批与发放均在 MySQL 事务内重新检查冲突。

## 验证

```sh
cd lab-equipment-frontend
npm ci
npm test
npm run build

cd ../lab-equipment-ai
.venv/bin/python -m pytest -q

cd ..
python3 -m unittest scripts.test_local_runner scripts.test_environment_dashboard scripts.test_macos_env
python3 scripts/check_backend.py
python3 scripts/integration.py
```

后端集成测试和 HTTP 验收只连接 `lab_equipment_test`。运行测试需要 `.env.local` 中数据库账户有该测试库权限；`scripts/integration.py` 会启动独立端口的测试进程，结束只回收自己创建的进程。它会创建隔离测试记录，不应对正式数据库使用。没有使用 H2 代替 MySQL 并发验证。

AI 对照实验需要真实模式、聊天模型和已发布知识库：

```sh
lab-equipment-ai/.venv/bin/python scripts/evaluate_ai.py --limit 30
```

脚本会调用已配置供应商，保存直接回答与 Agent/RAG 回答的原文、来源、用量和时延。人工评分项留空等待评价，不预设效果提升。

## 备份和部署

```sh
bash run.sh stop
python3 scripts/backup.py
bash run.sh start
```

备份包含数据库、归还照片和知识原件，以及校验清单。恢复是独立显式命令，目标仅允许本项目命名的数据库，必须重复指定确认名称：

```sh
python3 scripts/restore.py /绝对路径/备份.tar.gz \
  --database lab_equipment_restore \
  --confirm-database lab_equipment_restore \
  --files-dir .runtime/restore/knowledge
```

恢复前须停止项目，并预先创建目标库、授予账户权限。恢复会覆盖目标库中的同名表；恢复后撤销该目标库的旧会话，保留原文件目录，知识向量需重建。不要把测试数据库确认名用于业务库。

Docker 部署：

```sh
bash scripts/setup-env.sh
docker compose up -d --build
```

容器前端默认 http://localhost:8089 。仅前端发布端口，其余服务在私有容器网络。生产应配置 HTTPS，密钥、数据库卷和知识卷需要独立备份。详见 [部署说明](deploy/README.md)。

## 目录与文档

```text
lab-equipment-frontend/  Vue、业务页面、对话卡片与统计
lab-equipment-backend/   认证、权限、事务、设备借还、工单、消息和知识元数据
lab-equipment-ai/        单 Agent、模型适配、文件解析、RAG 与评测
scripts/                参考项目启停脚本、集成验证、备份恢复
deploy/                 部署说明
docs/                   当前规范、接口和实际验证记录
.runtime/               本机凭据、日志、文件和向量数据，全部忽略提交
```

- [当前需求与业务规则](docs/CURRENT_SPEC.md)
- [服务与权限架构](docs/ARCHITECTURE.md)
- [接口契约](docs/API_CONTRACT.md)
- [数据库实现](docs/DATABASE_DESIGN.md)
- [配置与运行](docs/LOCAL_RUN.md)
- [实际验证与限制](docs/TESTING.md)
- [最新系统复查与未完成事项](docs/REVIEW_2026-11-04.md)
- [开发约束](AGENTS.md)

![博仪登录页](docs/ui-final-2026-11-04/login-half.png)

## 公开知识资料

已整理并发布三条公开文档摘要，涵盖树莓派启动、供电和示波器探头检查，正文附原始来源。新环境可执行 `python3 scripts/import_public_knowledge.py` 导入；脚本按标题去重，不重复创建。材料位于 `docs/knowledge/public-notes.json`。

最新交互调整：桌面登录页左右各50%；账户和设备菜单、图片弹层、确认框采用自写主题组件。模块更新按账户保存在服务端，5秒检查一次，点击查看清除当前更新。独立消息中心已按当前功能范围移除，业务模块保留红点更新提示。多模态模型接收照片；明确拒绝图片输入的文本模型改用照片凭证和文字办理，429不触发图文回退或demo。详见 [本轮验收记录](docs/FINAL_REVIEW_2026-11-04.md)。
