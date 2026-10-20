# RunningHub 扩展模块（Java 17 / Tomcat 10.1）

此目录是**独立、可构建的扩展应用**，并非 `cwp-cwp/ai-video` 原始 Java/Vue 源码。它单独部署为 `runninghub.war`，不会修改原版 `video-service.war` 或原系统的前端按钮、数据库和素材管理业务。

## GitHub 自动编译

推送至 `feature/runninghub-integration` 或 `main` 时，GitHub Actions 自动运行：

```sh
mvn -B -ntp -f extension/pom.xml clean verify
```

流水线使用 **Java 17 / Maven / Ubuntu GitHub-hosted runner**，无需在 Windows 本机配置 Maven。成功后在 **Actions → Build RunningHub Extension WAR → 对应运行 → Artifacts → runninghub-war** 下载 `runninghub.war`。

## 部署

1. 停止原 Tomcat，备份配置和 `webapps`。
2. 将下载的 `runninghub.war` 放入原 Tomcat 10.1 的 `webapps`，保持原 `ROOT` 和 `video-service.war` 不动。
3. 启动 Tomcat，打开 `http://localhost:8080/runninghub/`（如果原 Tomcat 端口不同，请相应修改）；健康检查 `/runninghub/api/health`。
4. 在页面填入自己的 RunningHub API Key 与工作流 ID，查看节点配置并上传素材。提交任务会消耗余额，默认需要再次勾选确认。
5. 查询任务成功后可通过 `保存成功结果` 存入运行 Tomcat 的机器。环境变量 `RUNNINGHUB_ARCHIVE_DIR` 可自定义目录，否则写入当前账户主目录下的 `Canvas-ai-video/runninghub-results`。

**安全注意事项：** 仅在本机或受信任的内部网络使用此管理界面。不要开放到公网，也不要把 API Key 写入 GitHub、截图或仓库文件；运行本模块的 Tomcat 建议只监听 127.0.0.1。扩展目前没有用户登录认证。UI 中 API Key 不写入浏览器存储。结果下载只允许 HTTPS 下少数供应商域名，第三方存储域名可能需要评估后加入白名单。

## API

- `GET /runninghub/api/health` - 无密钥健康检查
- `POST /runninghub/api/workflow` - `{workflowId}` 查询工作流
- `POST /runninghub/api/upload` - multipart `file` 上传素材
- `POST /runninghub/api/submit` - `{workflowId,nodeInfoList}` 提交工作流（可能收费）
- `POST /runninghub/api/query` - `{taskId}` 查询任务
- `POST /runninghub/api/cancel` - `{taskId}` 取消任务
- `POST /runninghub/api/archive` - `{taskId}` 下载并保存完成的文件

POST 都需要头部 `X-RunningHub-Key` 与可选 `X-RunningHub-Region: cn|global`。模块仅对 RunningHub 预设域名发送该密钥，不允许浏览器提供服务端目标地址。前端与 API 同源。

## 范围与测试状态

- 本项目包含 Java 扩展 API、简单独立管理页面及基本单元测试。
- **尚未**接入原系统的生成按钮、数据库和素材库。
- GitHub Actions 可验证编译和 Mock 单元测试；**真实 RunningHub 生成必须在使用者账户下测试**，不能用 CI 中的假密钥模拟成功，也不应让公共 PR 获得真实密钥。
- 官方接口返回码和字段可能因地区版本不同而不同。中国站上传示例为 `data.fileName`，国际站示例为 `data.filename`，使用时按实际返回填写。
