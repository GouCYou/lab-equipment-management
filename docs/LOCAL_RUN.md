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
| SEED_DEMO_DATA | 启动器默认开启，自动补齐缺失的演示数据；后端独立运行可明确设 false 关闭 |
| SEED_DEMO_PASSWORD | 演示账户密码，默认 123456 |
| AI_INTERNAL_KEY | 内部鉴权及模型密钥加密根密钥；必须妥善备份 |

模型可以在管理后台热保存；也可设置 GENERATION_BASE_URL、GENERATION_MODEL、GENERATION_API_KEY。后台保存优先于环境中的模型值。基础地址填写到 `/v1` 等供应商要求的根路径，不带 `/chat/completions`。

## 环境安装

macOS 安装器沿用 Homebrew，支持 Apple Silicon/Intel，按需安装 Python3.12、Java21、Node24、MySQL8.4、Redis、Maven。已有兼容 MySQL8.0 复用，不自动升级。环境路径只作用于项目脚本，不改 shell 配置。镜像优先、失败回退官方，依赖指纹包含系统和架构。

Windows x64 双击 `run.bat`，选择“安装 / 修复运行环境”，或直接选择“启动项目”自动补齐缺失环境；也可运行 `run.bat setup`。缺少 Python 时先通过 winget 安装 Python，再打开菜单。完整安装自动申请管理员权限，准备 Python、Java21、Node24、Maven、MySQL8.4 和 Memurai（Redis 兼容服务），随后安装 npm/pip 依赖、构建后端并下载 Qdrant。需要联网及 Microsoft App Installer（winget）。已有兼容环境与数据库服务复用；新 MySQL 数据位于 `%ProgramData%\BoyiRuntime\mysql-data`，不重置已有密码。安装失败会显示具体步骤，可再次运行继续。项目工具路径自动记忆，无需手动修改 PATH。JDK8/21 等多个版本共存时，自动检测并选用可用的最高版本 JDK（至少21），为项目设置 JAVA_HOME/PATH，不修改系统默认 Java。

Linux 保留已有系统环境和服务检测。Windows 安装流程已有模拟测试，尚未进行 Windows 实机端到端验证。

## 数据目录

Windows 运行工具全局安装：winget 使用系统安装范围；ZIP 工具放在 `%ProgramFiles%\BoyiRuntime`，下载缓存和共享环境记录放在 `%ProgramData%\BoyiRuntime`，Qdrant 程序放在 `%LOCALAPPDATA%\Programs\BoyiRuntime`。全局 PATH 自动登记，其他项目可复用；原来项目内的数据库服务和数据保留原位，不自动搬迁。npm 的 node_modules、Python venv 和构建产物仍属于项目。

日志、PID、依赖指纹、随机凭据、原始知识文件及图片、Qdrant 数据在 `.runtime/local`。集成验证在 `.runtime/test`，测试库 `lab_equipment_test`；不得替换为正式库。数据库按 UTC 保存，网站按北京时间展示。

打开菜单和 status 是只读；选择启动才会生成缺失配置、安装依赖、构建和创建指定数据库。停止保留 MySQL/Redis，失败只回收本轮新启进程。端口被他人占用直接报告，不按端口杀进程。

AI 只调用已配置的聊天模型；缺少配置时明确提示，不生成预置回复。模型请求失败不影响已启动的人工借还、工单和统计功能。

2026-11-04 环境检测修正：macOS `check-env` 和安装检查复用项目已有的 Python 3.12 虚拟环境，也接受导出的 `LOCAL_AI_PYTHON`；没有全局 `python3.12` 同名命令时不再误报。

菜单可选择“查看各角色账户”（macOS 第 7 项，Windows 第 7 项，Linux 第 6 项），或运行 `bash run.sh accounts` / `run.bat accounts`。默认用户名：admin、student、labstaff、technician；登录密码统一为 123456。

Windows 安装故障详情同时保存在 `.runtime/windows/setup.log` 和 `.runtime/windows/install-error.txt`；管理员窗口的输出会在结束后回传主菜单，不再只显示退出码。

2026-11-14：Windows 安装器按版本探测 Node/npm、Maven、MySQL 客户端和 Redis 客户端，缺失、不可用或版本不兼容时安装兼容工具并优先用于项目。Redis 版本输出兼容 redis-cli 和 Memurai 多种格式，避免安装成功后被误判。69 项启动器检查通过（含实际 PowerShell 解析及版本探测）；Windows 全流程仍需实机复验。

Windows Memurai 安装修复：先从 PATH、卸载注册表和服务路径查找可用客户端，兼容的已有安装直接复用。不再强制 winget 重装 Memurai；缺失时下载并校验官方 MSI，禁用安装阶段的防火墙/服务动作，再按需单独注册服务。详细 MSI 错误保存于 `%ProgramData%\BoyiRuntime\memurai-install.log`，主窗口显示原始 Windows Installer 错误码。

Windows 菜单启动时执行只读安装探测，不依赖完整安装成功后才写入的 environment.json：检查系统安装目录、MySQL 注册表和服务可执行文件目录，以及全局 Maven 目录、MAVEN_HOME/M2_HOME；把识别路径立即用于当前进程。Memurai 客户端可直接调用，无需先创建 redis-cli 别名。探测不要求管理员权限、不安装软件、不创建项目运行目录。

默认演示初始化与账户初始化独立：始终补齐四个默认角色账户（123456），启动器默认带上两间实验室、12 台设备、借还/工单样例及三篇已发布维修资料；按资产编号、单号及资料标题检查，不覆盖已有业务进度、不重复导入。已有实验室不会阻止补齐其他样例。
