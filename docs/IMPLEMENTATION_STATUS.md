# RunningHub 扩展状态

## 已落地的代码

- `extension/pom.xml`：Java 17 / Tomcat 10.1 / Jackson / JUnit / Mockito Maven 构建。
- `extension/src/main/java/`：工作流、素材上传、提交、查询、取消和结果归档的独立 REST Servlet。
- `extension/src/main/webapp/index.html`：独立操作页面。
- `extension/src/test/java/`：安全域名白名单、健康接口、无密钥拒绝与参数验证基础测试。
- `.github/workflows/build-runninghub.yml`：自动执行 `mvn clean verify` 并保存 `runninghub.war` 构建产物。

## 验收标准

1. GitHub Actions 编译、单元测试、WAR 制品上传成功。
2. 真实 Tomcat 10.1 部署后访问 `/runninghub/` 与 `/runninghub/api/health`。
3. 用户本地配置 RunningHub Key、Workflow ID 后完成一条真实任务并验证生成文件可保存。

**注意：** 第 1 项验证不会自动证明第 2/3 项；没有实际运行记录前，不得声称线上实战成功。原版 `video-service.war` 和 `ROOT` 仍未修改。
