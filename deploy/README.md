# 部署与数据保留

在具备 Docker Engine 和 Compose 的服务器上，从项目根目录执行：

```sh
bash scripts/setup-env.sh
docker compose up -d --build
docker compose ps
```

初始化脚本只在 `.env` 不存在时创建随机数据库、Redis、内部服务和管理员凭据。修改 `.env` 中模型配置，或登录管理端保存。已有配置不覆盖。前端默认端口 8089，可配置 WEB_PORT。

Compose 包含 Nginx、Java、Python、MySQL8.0.46、Redis7.4、Qdrant1.15.5。Node24 和 Java21 用于构建，AI Python3.12。仅 Nginx 发布端口；AI、Qdrant、数据库不映射宿主端口。生产入口应配置可信证书和 HTTPS。

MySQL、Redis、Qdrant、知识文件使用独立命名卷。`docker compose stop` 或 `down` 保留命名卷；不要使用 `down -v` 清除业务数据。Java 运行账户 uid10001，知识卷需可写；Python 不挂载业务文件，通过 Java 传递受控文档内容，不需要业务数据库账号。

## 容器备份

本机 `scripts/backup.py`、`restore.py` 面向原生进程和本机 MySQL，不自动探测容器命名卷。容器部署按以下方式停写并备份：

1. 停止 frontend、backend、ai，保持 MySQL 运行。
2. 在数据库容器使用 mysqldump 导出数据库，凭据通过容器环境读取，输出写入服务器私有备份目录。
3. 使用 Docker 卷备份方式保存 knowledge-data，保存文件校验清单；另行备份 `.env` 和原 `AI_INTERNAL_KEY`。
4. 备份完成后启动业务服务。恢复到独立数据库和文件卷验证后再切换生产，重建 Qdrant 知识索引。

不要只备份向量库而遗漏资料原文、归还照片和 MySQL。模型密钥在数据库中加密，原加密根密钥缺失需要重新配置。

## 验证范围

本机已完成独立 MySQL/Redis/Qdrant 的宿主运行和 HTTP 联调。Compose 配置通过校验，当前机器 Docker daemon 不可连接，因此尚未实际构建和运行六个容器。该状态不等于容器部署验收通过。准确记录见 `docs/TESTING.md`。
