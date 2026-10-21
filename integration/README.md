# 嵌入原网页的视频设置页

这个安装包面向原作者 v1.0.9 的 Windows Tomcat 10.1 便携版。安装后，**原网页** `系统设置 → 视频生成接口` 会出现 `RunningHub 云端工作流` 卡片。展开后可在同一网页配置 API Key、工作流、上传参考素材、提交/查询任务、保存结果。

## 使用说明

1. 关闭 `start.bat` 启动的服务（双击原项目 `stop.bat`）；备份原程序。
2. 下载 GitHub Actions **Canvas-RunningHub-Integration** 构建产物（ZIP），解压，目录内有 `runninghub.war`、`runninghub-settings.js`、`install.ps1`。
3. 打开 PowerShell，运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File ".\install.ps1" -TomcatPath "C:\your-path\apache-tomcat-10.1.59"
```

4. 启动原版 `start.bat`，打开 `http://localhost:18080/`；进入 `系统设置 → 视频生成接口`，在列表下面找到 `RunningHub 云端工作流`，点击 `展开配置`。
5. 如未显示，请 Ctrl+F5 强制刷新。若页面提示「未安装 runninghub.war」，检查 `webapps\runninghub.war` 和 Tomcat 启动日志。

**恢复原版**：停止 Tomcat，使用 `webapps\ROOT\index.html.before-runninghub.bak` 恢复 `webapps\ROOT\index.html`，并移除 `webapps\ROOT\assets\runninghub-settings.js`；如果不再使用独立扩展，可删除 `webapps\runninghub.war` 与解压目录 `webapps\runninghub`。请在备份确认后操作。


## PowerShell 5.1 编码问题（2026-10-21 修复）

旧版安装包的 `install.ps1` 包含 UTF-8 中文提示，但没有 BOM。Windows PowerShell 5.1 在某些系统区域设置下将它当作 ANSI 读取，会出现“必须在 / 运算符后面提供一个值表达式”“字符串缺少终止符”等语法错误。**这不是 Tomcat 路径错误。**

请从 **最新的 GitHub Actions 成功构建**重新下载 `Canvas-RunningHub-Integration` 包，解压覆盖旧文件，然后执行原安装命令。新版 `install.ps1` 使用纯 ASCII，可在 Windows PowerShell 5.1 下解析。构建现在还包括实际运行 `powershell.exe` 的 Windows 安装与重复安装测试。

脚本执行时先确认依赖文件存在，再复制 WAR 和前端资源，最后注入 HTML 脚本标签。它不会修改原来的 `video-service.war` 或原版编译后的 JS；每次安装会保留首次保存的 `index.html.before-runninghub.bak`。

## 边界

该集成是将独立操作台嵌入原版设置页，**不是**在原版数据库新增 RunningHub provider，也不会让原版视频生成按钮自动切换至 RunningHub。鉴于上游只有编译后二进制，无完整后端源码，接管其内部生成任务仍需要针对视频生成服务、素材库接口做专项适配和独立验证。

扩展操作页当前没有独立登录认证，请仅在本机或受信任网络使用；不要对公网开放，不要在 GitHub 上传 API Key。
