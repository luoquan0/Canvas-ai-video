package dev.canvas.runninghub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Standalone Tomcat extension. Does not access the original application's database or internal classes.
 * Secrets are request-scoped: never stored or returned to the browser.
 */
@WebServlet(urlPatterns = "/api/*")
@MultipartConfig(maxFileSize = 32L * 1024 * 1024, maxRequestSize = 34L * 1024 * 1024)
public final class RunningHubServlet extends HttpServlet {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final Pattern ID = Pattern.compile("[0-9]{1,32}");
    private static final Pattern FILE_EXTENSION = Pattern.compile("(?i)\\.(png|jpg|jpeg|webp|mp4|mov|wav|mp3|flac|avi|mkv|zip)$");

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        headers(response);
        if (!"/health".equals(request.getPathInfo())) {
            error(response, 404, "未知接口");
            return;
        }
        ObjectNode status = JSON.createObjectNode();
        status.put("ok", true);
        status.put("service", "Canvas RunningHub extension");
        status.put("version", "0.1.0");
        write(response, 200, status);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        headers(response);
        String action = request.getPathInfo();
        String key = request.getHeader("X-RunningHub-Key");
        if (key == null || key.isBlank() || key.length() > 256 || key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0) {
            error(response, 401, "请在页面填写有效 RunningHub API Key");
            return;
        }
        String region = request.getHeader("X-RunningHub-Region");
        if (region == null || region.isBlank()) region = "cn";
        if (!region.equals("cn") && !region.equals("global")) {
            error(response, 400, "区域仅支持 cn 或 global");
            return;
        }
        String base = switch (region) {
            case "global" -> "https://www.runninghub.ai";
            default -> "https://www.runninghub.cn";
        };
        // Restricted to explicit server-owned override for offline tests only, never a request parameter.
        String testBase = System.getenv("RUNNINGHUB_TEST_BASE_URL");
        if (testBase != null && !testBase.isBlank() && Boolean.getBoolean("runninghub.testMode")) {
            base = testBase;
        }
        try {
            if ("/upload".equals(action)) {
                upload(request, response, base, key);
                return;
            }
            JsonNode body = JSON.readTree(readLimited(request.getInputStream(), MAX_BODY_BYTES));
            if (body == null || !body.isObject()) {
                error(response, 400, "需要 JSON 对象");
                return;
            }
            switch (action == null ? "" : action) {
                case "/workflow" -> workflow(response, base, key, body);
                case "/submit" -> submit(response, base, key, body);
                case "/query" -> query(response, base, key, body);
                case "/cancel" -> cancel(response, base, key, body);
                case "/archive" -> archive(response, base, key, body);
                default -> error(response, 404, "未知接口");
            }
        } catch (IllegalArgumentException e) {
            error(response, 400, e.getMessage());
        } catch (Exception e) {
            // Do not echo exception text, remote headers, or potentially sensitive request bodies.
            getServletContext().log("RunningHub request failed: " + e.getClass().getSimpleName());
            error(response, 502, "请求失败，请检查网络、API Key、工作流设置或服务器日志");
        }
    }

    private void workflow(HttpServletResponse res, String base, String key, JsonNode body) throws Exception {
        String workflowId = requiredId(body, "workflowId");
        ObjectNode data = authBody(key);
        data.put("workflowId", workflowId);
        write(res, 200, remote(base, "/api/openapi/getJsonApiFormat", key, data));
    }

    private void submit(HttpServletResponse res, String base, String key, JsonNode body) throws Exception {
        String workflowId = requiredId(body, "workflowId");
        ObjectNode data = authBody(key);
        data.put("workflowId", workflowId);
        JsonNode nodes = body.get("nodeInfoList");
        if (nodes != null && !nodes.isNull()) {
            if (!nodes.isArray() || nodes.size() > 150) throw new IllegalArgumentException("nodeInfoList 格式不正确");
            ArrayNode list = data.putArray("nodeInfoList");
            for (JsonNode n : nodes) {
                String nodeId = requiredString(n, "nodeId", 60);
                String fieldName = requiredString(n, "fieldName", 160);
                if (nodeId.contains("/") || fieldName.contains("/") || fieldName.contains(".."))
                    throw new IllegalArgumentException("节点名称不合法");
                String value = requiredString(n, "fieldValue", 40_000);
                ObjectNode item = list.addObject();
                item.put("nodeId", nodeId);
                item.put("fieldName", fieldName);
                item.put("fieldValue", value);
            }
        }
        // This is a paid operation on the user's RunningHub account.
        JsonNode result = remote(base, "/task/openapi/create", key, data);
        write(res, 200, result);
    }

    private void query(HttpServletResponse res, String base, String key, JsonNode body) throws Exception {
        String taskId = requiredId(body, "taskId");
        write(res, 200, remote(base, "/openapi/v2/query", key, JSON.createObjectNode().put("taskId", taskId)));
    }

    private void cancel(HttpServletResponse res, String base, String key, JsonNode body) throws Exception {
        String taskId = requiredId(body, "taskId");
        ObjectNode data = authBody(key).put("taskId", taskId);
        write(res, 200, remote(base, "/task/openapi/cancel", key, data));
    }

    private void upload(HttpServletRequest req, HttpServletResponse res, String base, String key) throws Exception {
        if (req.getContentType() == null || !req.getContentType().toLowerCase(Locale.ROOT).startsWith("multipart/form-data"))
            throw new IllegalArgumentException("需要 multipart/form-data 上传");
        Part part = req.getPart("file");
        if (part == null || part.getSize() == 0 || part.getSize() > 32L * 1024 * 1024)
            throw new IllegalArgumentException("上传文件大小必须为 1B–32MB");
        String name = part.getSubmittedFileName();
        if (name == null || !FILE_EXTENSION.matcher(name).find()) throw new IllegalArgumentException("不支持的媒体文件格式");
        String safeName = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeName.length() > 100) safeName = safeName.substring(safeName.length() - 100);
        String boundary = "canvasRH" + Long.toHexString(System.nanoTime());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + safeName + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        try (InputStream in = part.getInputStream()) {
            in.transferTo(bytes);
        }
        bytes.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        JsonNode uploaded = remoteRaw(base, "/openapi/v2/media/upload/binary", key,
                "multipart/form-data; boundary=" + boundary, bytes.toByteArray());
        write(res, 200, uploaded);
    }

    private void archive(HttpServletResponse res, String base, String key, JsonNode body) throws Exception {
        String taskId = requiredId(body, "taskId");
        JsonNode result = remote(base, "/openapi/v2/query", key, JSON.createObjectNode().put("taskId", taskId));
        if (!"SUCCESS".equalsIgnoreCase(result.path("status").asText())) {
            throw new IllegalArgumentException("只有已完成的任务才能保存结果");
        }
        JsonNode results = result.path("results");
        if (!results.isArray() || results.isEmpty()) throw new IllegalArgumentException("任务暂无可保存文件");
        Path output = archiveRoot();
        Files.createDirectories(output);
        ArrayNode saved = JSON.createArrayNode();
        int count = Math.min(results.size(), 24);
        for (int i = 0; i < count; i++) {
            JsonNode record = results.get(i);
            String url = record.path("url").asText("");
            URI urlUri = URI.create(url);
            if (!allowedResultUri(urlUri)) throw new IllegalArgumentException("结果链接不在受信任的存储域名内");
            String ext = record.path("outputType").asText("").toLowerCase(Locale.ROOT);
            if (!ext.matches("png|jpg|jpeg|webp|gif|mp4|mov|webm|wav|mp3|zip")) ext = "bin";
            Path target = output.resolve(taskId + "-" + (i + 1) + "." + ext);
            // Idempotent archive: no repeated download if file already exists.
            if (!Files.isRegularFile(target) || Files.size(target) == 0) {
                HttpRequest download = HttpRequest.newBuilder(urlUri).GET()
                        .timeout(Duration.ofMinutes(5)).build();
                HttpResponse<InputStream> reply = HTTP.send(download, HttpResponse.BodyHandlers.ofInputStream());
                if (reply.statusCode() != 200) {
                    reply.body().close();
                    throw new IOException("下载结果失败，HTTP " + reply.statusCode());
                }
                Path temporary = Files.createTempFile(output, taskId + "-", ".tmp");
                try (InputStream data = reply.body();
                     var file = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[65536];
                    long total = 0;
                    int n;
                    while ((n = data.read(buffer)) != -1) {
                        total += n;
                        if (total > 512L * 1024 * 1024) throw new IOException("结果文件超出 512MB 限制");
                        file.write(buffer, 0, n);
                    }
                } catch (Exception ex) {
                    Files.deleteIfExists(temporary);
                    throw ex;
                }
                try {
                    Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            ObjectNode savedItem = saved.addObject();
            savedItem.put("file", target.getFileName().toString());
            savedItem.put("bytes", Files.size(target));
        }
        ObjectNode answer = JSON.createObjectNode();
        answer.put("directory", output.toAbsolutePath().toString());
        answer.set("saved", saved);
        write(res, 200, answer);
    }

    static boolean allowedResultUri(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1)
            return false;
        String h = uri.getHost();
        if (h == null) return false;
        h = h.toLowerCase(Locale.ROOT);
        return h.equals("runninghub.cn") || h.endsWith(".runninghub.cn")
                || h.equals("runninghub.ai") || h.endsWith(".runninghub.ai")
                || h.endsWith(".myqcloud.com") || h.endsWith(".aliyuncs.com");
    }

    static Path archiveRoot() {
        String config = System.getenv("RUNNINGHUB_ARCHIVE_DIR");
        if (config != null && !config.isBlank()) return Path.of(config).toAbsolutePath();
        return Path.of(System.getProperty("user.home"), "Canvas-ai-video", "runninghub-results").toAbsolutePath();
    }

    private static ObjectNode authBody(String key) {
        return JSON.createObjectNode().put("apiKey", key);
    }

    private static String requiredId(JsonNode node, String name) {
        String val = requiredString(node, name, 32);
        if (!ID.matcher(val).matches()) throw new IllegalArgumentException(name + " 必须为数字 ID");
        return val;
    }

    private static String requiredString(JsonNode node, String name, int maxLength) {
        JsonNode field = node == null ? null : node.get(name);
        if (field == null || field.isNull() || field.isContainerNode())
            throw new IllegalArgumentException(name + " 不能为空");
        String result = field.asText();
        if (result.isBlank() || result.length() > maxLength)
            throw new IllegalArgumentException(name + " 长度不合法");
        return result;
    }

    private static byte[] readLimited(InputStream in, int max) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (result.size() + n > max) throw new IllegalArgumentException("请求超过允许大小");
            result.write(buffer, 0, n);
        }
        return result.toByteArray();
    }

    private static JsonNode remote(String base, String endpoint, String key, JsonNode payload) throws Exception {
        return remoteRaw(base, endpoint, key, "application/json", JSON.writeValueAsBytes(payload));
    }

    private static JsonNode remoteRaw(String base, String endpoint, String key, String contentType, byte[] bytes) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + endpoint))
                .timeout(Duration.ofMinutes(3))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IllegalArgumentException("RunningHub 返回 HTTP " + response.statusCode());
        if (response.body().length > 4 * 1024 * 1024)
            throw new IllegalArgumentException("RunningHub 响应过大");
        JsonNode result = JSON.readTree(response.body());
        if (result == null || !result.isObject()) throw new IOException("RunningHub 返回了非 JSON 对象");
        if (result.has("code") && result.path("code").canConvertToInt()) {
            int code = result.path("code").asInt();
            if (code != 0 && code != 200) {
                String msg = result.path("message").asText(result.path("msg").asText("接口错误"));
                if (msg.length() > 300) msg = msg.substring(0, 300);
                throw new IllegalArgumentException("RunningHub 错误 " + code + ": " + msg.replace(key, "[REDACTED]"));
            }
        }
        return result;
    }

    private static void headers(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    private static void error(HttpServletResponse res, int status, String msg) throws IOException {
        write(res, status, JSON.createObjectNode().put("error", msg));
    }

    private static void write(HttpServletResponse res, int code, JsonNode payload) throws IOException {
        res.setStatus(code);
        res.setCharacterEncoding("UTF-8");
        res.setContentType("application/json;charset=UTF-8");
        JSON.writeValue(res.getWriter(), payload);
    }
}