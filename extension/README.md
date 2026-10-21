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
3. 启动 Tomcat，打开 `http://localhost:18080/runninghub/`（如果原 Tomcat 端口不同，请相应修改）；健康检查 `/runninghub/api/health`。
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

## 将原版分镜视频真正切换至 RunningHub 云端（实验性兼容代理）

最新版本包含 `ComfyCloudBridgeServlet`，在同一个 Tomcat 环境中模拟 ComfyUI HTTP 接口，原程序仍负责分镜任务管理和视频保存。此方案**不修改受保护的原版 WAR**。

1. 下载并安装最新版 `Canvas-RunningHub-Integration`（包含更新的 `runninghub.war`），重启原版 `start.bat`。
2. 在原网页「系统设置 → 视频生成接口 → RunningHub 云端工作流 → 展开配置」中，找到 **第 6 步：接管原版分镜视频生成**。
3. 使用你自己的工作流 ID 和 API Key，填写目标工作流的 **真实节点映射**，勾选启用并保存。映射必须至少有一个字段，错误映射会在发起付费任务前拒绝。
4. 将原版视频配置中 **本地 ComfyUI 的 API地址** 改为 `http://127.0.0.1:18080/runninghub/comfy` 并保存；保持「默认视频生成服务」选中 ComfyUI（这是兼容协议标识；后台实际会调用 RunningHub）。单独的 **图片生成接口** 地址保持原来的本地 ComfyUI，不要改它。
5. 点击原版分镜的「MSR 视频生成」，观察 RunningHub 任务与原系统结果回写。生成可能消耗 RunningHub 额度。

映射示例（**示例 ID 不可照抄，请替换你真实的 RunningHub 工作流节点 ID**）：
```json
[
  {"targetNodeId":"6","targetField":"text","source":"prompt"},
  {"targetNodeId":"10","targetField":"image","source":"image1"},
  {"targetNodeId":"11","targetField":"duration","source":"duration"}
]
```

原版 MSR V2 的 `PromptRelayEncode` 具有 `global_prompt` 和 `local_prompts`；兼容代理支持映射到 `globalPrompt` / `localPrompts` 或合并为 `prompt`。参考图来源 `image1`～`image4`；对于复杂节点，可使用 `{"source":"node","sourceNodeId":"180","sourceField":"local_prompts"}` 定位原版节点。没有的字段映射会拒绝请求，避免直接用错误参数触发云端扣费。

运行时密钥存入 **Tomcat 的 `conf/runninghub-video.json`**（不会进入 GitHub），任务状态存入 `conf/runninghub-video-tasks.json`；云端视频缓存保存在用户目录 `Canvas-ai-video/cloud-video`。请将 Tomcat 限制在本机或可信内网，并保护配置文件访问权限。

**回退**：将视频生成接口的 ComfyUI 地址改回 `http://127.0.0.1:8188` 并保存。无需替换原版 WAR。若不用云端模式，可在第 6 步取消启用。

### 验证说明

自动测试仅覆盖映射、缺失输入拒绝、密钥与本机访问边界以及 WAR 打包。真实 RunningHub 云端生成需要用户账号密钥、工作流 ID、准确节点映射和付费额度；在未完成实际生成和原版分镜视频回写前，不能宣称全链路验证通过。
