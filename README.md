# Canvas-ai-video · RunningHub Extension

为 [cwp-cwp/ai-video](https://github.com/cwp-cwp/ai-video) 的 Windows Tomcat 便携版提供**独立部署**的 RunningHub 工作流操作台。开发源码是自行编写的扩展代码，**不是原项目的完整 Java/Vue 源码**。

## 状态

此仓库保存 RunningHub 扩展源码和构建脚本；原版 `video-service.war` 和前端 `ROOT` 均不在本仓库中。原版程序的打包、许可证和更新由上游负责。

## 实现范围

- 输入 API Key、选择 RunningHub 中国站/国际站（API Key 不写入文件或 GitHub）
- 查询工作流 JSON 并选择修改的节点输入
- 上传参考图片/视频/音频，提交工作流任务
- 异步查询生成结果、任务取消、保存生成结果到本机
- Windows JDK 17 + Tomcat 10 的独立 WAR 构建和部署

## 构建方式

详细参见 [extension/README.md](extension/README.md)。本仓库不包含 RunningHub API Key、原版运行包、数据库和用户素材。

## 进度

`feature/runninghub-integration` 用于开发与测试，确认后再合并到 `main`。**此扩展以独立页面 `/runninghub/` 使用；未修改原程序的生成按钮或内部资产数据库。**

## 参考资料

- [RunningHub 官方 API 文档](https://www.runninghub.cn/runninghub-api-doc-cn/)
- [上游便携版 Release](https://github.com/cwp-cwp/ai-video/releases/tag/v1.0.9)
