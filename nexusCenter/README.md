# nexusCenter

NEXUS API 中转站项目的源码与部署归档目录。

- 后端源码副本：`nexusCenter\backend`
- 前端源码副本：`nexusCenter\frontend`
- 当前构建工作副本：`D:\04 AI项目合集\AI中转站\后端项目`、`D:\04 AI项目合集\AI中转站\前端项目\nexus-api-ui`
- 正式服务器：`8.218.238.141:/opt/nexusCenter`
- 正式域名：`https://nexusapi.center`
- 生产 Compose 项目：`nexus-center-prod`
- PostgreSQL、Redis、Nginx 为线上公共服务，各只运行一个实例；应用通过 `nexus-shared` 公共网络接入。

本目录不保存生产密钥、API Key 或数据库密码。
