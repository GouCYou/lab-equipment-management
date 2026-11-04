# 本机配置与运行

根目录 `run.sh`、`run.bat`、`setup-mac.command` 与参考项目保持相同交互。无参数打开菜单；启动、停止、状态、日志、环境详情和 macOS 安装修复位置一致，终端颜色也保留参考风格。网站主题独立采用蓝紫色。

## 配置文件

`.env` 用于部署与服务端配置，`.env.local` 用于本机数据库、端口、服务名。它们都忽略提交。参考项目的解析与优先级保留：进程环境、`.env`、本机默认值、`.env.local` 依次合并，后者覆盖同名项；初始化凭据只补未设置值。不要 source/eval 配置文件。

| 配置 | 含义 |
| --- | --- |
| DB_NAME / DB_USERNAME / DB_PASSWORD | 本项目数据库与账号；不会重置 MySQL 密码 |
| LOCAL_DB_HOST / LOCAL_DB_PORT | 本机 MySQL 地址 |
| LOCAL_REDIS_HOST / LOCAL_REDIS_PORT / REDIS_PASSWORD | Redis 地址与凭据 |
| LOCAL_FRONTEND_PORT / LOCAL_BACKEND_PORT | 默认 5188 / 18180 |
| LOCAL_AI_PORT / LOCAL_QDRANT_PORT / LOCAL_QDRANT_GRPC_PORT | 默认 18100 / 17333 / 17334 |
| LOCAL_AI_PYTHON | 创建 AI venv 的基础解释器绝对路径 |
| LOCAL_QDRANT_BIN | 已有的兼容 Qdrant 可执行文件 |
| LOCAL_LAN_IP | 多网卡环境手工指定局域网地址 |
| LOCAL_MYSQL_SERVICE / LOCAL_REDIS_SERVICE | Windows/Linux 服务名覆盖 |
| SEED_ADMIN_USERNAME / SEED_ADMIN_PASSWORD | 首次初始化管理员，已有账户保留 |
| SEED_DEMO_DATA | 明确设 true 才在空实验室库初始化演示数据 |
| SEED_DEMO_PASSWORD | 演示账户密码，默认 123456 |
| AI_MODE | real 或显式 demo，切换需停止后启动 |
| AI_INTERNAL_KEY | 内部鉴权及模型密钥加密根密钥；必须妥善备份 |

模型可以在管理后台热保存；也可设置 GENERATION_BASE_URL、GENERATION_MODEL、GENERATION_API_KEY 及独立的 EMBEDDING 三项。后台保存优先于环境中的模型值。基础地址填写到 `/v1` 等供应商要求的根路径，不带 `/chat/completions` 或 `/embeddings`。

## 环境安装

macOS 安装器沿用 Homebrew，支持 Apple Silicon/Intel，按需安装 Python3.12、Java21、Node24、MySQL8.4、Redis、Maven。已有兼容 MySQL8.0 复用，不自动升级。环境路径只作用于项目脚本，不改 shell 配置。镜像优先、失败回退官方，依赖指纹包含系统和架构。

Windows/Linux 支持服务检测、已注册数据库服务启动、依赖安装和项目启停。Windows 原生 Redis 可由已有兼容服务提供；不会凭空安装或注册系统服务。未进行这两个平台的实机端到端验证，跨平台分支已有模拟测试。

## 数据目录

日志、PID、依赖指纹、随机凭据、原始知识文件及图片、Qdrant 数据在 `.runtime/local`。集成验证在 `.runtime/test`，测试库 `lab_equipment_test`；不得替换为正式库。数据库按 UTC 保存，网站按北京时间展示。

打开菜单和 status 是只读；选择启动才会生成缺失配置、安装依赖、构建和创建指定数据库。停止保留 MySQL/Redis，失败只回收本轮新启进程。端口被他人占用直接报告，不按端口杀进程。

当前启动模式可通过 `bash run.sh status` 查看；真实模式缺模型不会输出伪造建议，demo 也不会隐藏标识。模型请求失败不影响已启动的人工借还、工单和统计功能。

2026-11-04 环境检测修正：macOS `check-env` 和安装检查复用项目已有的 Python 3.12 虚拟环境，也接受导出的 `LOCAL_AI_PYTHON`；没有全局 `python3.12` 同名命令时不再误报。

菜单可选择“查看各角色账户”（macOS 第 7 项，Windows/Linux 第 6 项），或运行 `bash run.sh accounts` / `run.bat accounts`。默认用户名：admin、student、labstaff、technician；登录密码统一为 123456。
