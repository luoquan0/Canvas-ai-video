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
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * ComfyUI HTTP compatibility gateway for the original protected video-service WAR.
 * Requests travel only between two applications on the same Tomcat/loopback interface.
 */
@WebServlet(urlPatterns = {"/comfy/*", "/api/bridge/*"}, loadOnStartup = 1)
@MultipartConfig(maxFileSize = 64L * 1024 * 1024, maxRequestSize = 66L * 1024 * 1024)
public final class ComfyCloudBridgeServlet extends HttpServlet {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern NODE = Pattern.compile("[0-9]{1,32}");
    private static final Pattern OUTPUT_NAME = Pattern.compile("[a-f0-9-]{36}\\.(mp4|webm|mov)$");
    private static final int MAX_JSON = 3 * 1024 * 1024;
    private static final long MAX_VIDEO = 512L * 1024 * 1024;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    private static final ConcurrentHashMap<String, Task> TASKS = new ConcurrentHashMap<>();
    private static final Object STATE_LOCK = new Object();

    private static final class Task {
        String promptId, remoteId, status = "QUEUED", filename = "";
        Task() {}
        Task(String promptId, String remoteId) { this.promptId = promptId; this.remoteId = remoteId; }
    }

    static Path baseDir() {
        return Path.of(System.getProperty("catalina.base", "."), "conf").toAbsolutePath();
    }

    static Path configPath() { return baseDir().resolve("runninghub-video.json"); }
    static Path statePath() { return baseDir().resolve("runninghub-video-tasks.json"); }
    static Path outputDir() { return Path.of(System.getProperty("user.home"), "Canvas-ai-video", "cloud-video").toAbsolutePath(); }

    @Override public void init() {
        try {
            if (Files.isRegularFile(statePath())) {
                JsonNode saved = JSON.readTree(Files.readAllBytes(statePath()));
                if (saved.isArray()) for (JsonNode t : saved) {
                    String id = t.path("promptId").asText("");
                    String remote = t.path("remoteId").asText("");
                    if (id.matches("[a-f0-9-]{36}") && NODE.matcher(remote).matches()) {
                        Task task = new Task(id, remote);
                        task.status = t.path("status").asText("QUEUED");
                        task.filename = t.path("filename").asText("");
                        TASKS.put(id, task);
                    }
                }
            }
        } catch (Exception e) { getServletContext().log("RunningHub task state could not be restored", e); }
    }

    @Override protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        headers(res);
        if (!loopback(req)) { fail(res, 403, "This bridge is available from localhost only"); return; }
        String path = req.getPathInfo();
        try {
            if (req.getServletPath().startsWith("/api/bridge")) {
                if ("/config".equals(path) || "/status".equals(path)) { json(res, 200, publicConfig()); return; }
                fail(res, 404, "Unknown bridge settings path"); return;
            }
            if ("/system_stats".equals(path) || "/".equals(path) || path == null) {
                if (!configured(loadConfig())) { fail(res, 503, "RunningHub cloud video is not configured"); return; }
                ObjectNode out = JSON.createObjectNode(); out.putObject("system").put("comfyui_version", "runninghub-cloud-bridge");
                out.putArray("devices"); json(res, 200, out); return;
            }
            if ("/queue".equals(path)) { queue(res); return; }
            if ("/history".equals(path)) { historyAll(res); return; }
            if (path.startsWith("/history/")) { history(res, path.substring(9)); return; }
            if ("/view".equals(path)) { view(req, res); return; }
            fail(res, 404, "Unsupported ComfyUI endpoint: " + path);
        } catch (IllegalArgumentException e) { fail(res, 422, e.getMessage()); }
        catch (Exception e) { getServletContext().log("RunningHub bridge GET failed", e); fail(res, 502, "Cloud video status request failed"); }
    }

    @Override protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        headers(res);
        if (!loopback(req)) { fail(res, 403, "This bridge is available from localhost only"); return; }
        try {
            if (req.getServletPath().startsWith("/api/bridge")) {
                if (!"/config".equals(req.getPathInfo())) { fail(res, 404, "Unknown bridge settings path"); return; }
                if (!trustedOrigin(req)) { fail(res, 403, "Invalid Origin for local settings"); return; }
                saveConfig(req, res); return;
            }
            String path = req.getPathInfo();
            if ("/prompt".equals(path)) { prompt(req, res); return; }
            if ("/upload/image".equals(path) || "/upload/video".equals(path) || "/upload/audio".equals(path)) {
                upload(req, res, path); return;
            }
            if ("/interrupt".equals(path)) { json(res, 200, JSON.createObjectNode()); return; }
            fail(res, 404, "Unsupported ComfyUI endpoint");
        } catch (IllegalArgumentException e) { fail(res, 422, e.getMessage()); }
        catch (Exception e) { getServletContext().log("RunningHub bridge POST failed", e); fail(res, 502, "Cloud video request failed; see Tomcat logs"); }
    }

    private static boolean loopback(HttpServletRequest req) {
        String addr = req.getRemoteAddr();
        if (addr == null) return false;
        try { return InetAddress.getByName(addr).isLoopbackAddress(); }
        catch (Exception e) { return false; }
    }

    private static boolean trustedOrigin(HttpServletRequest req) {
        String origin = req.getHeader("Origin");
        if (origin == null) return true; // Local scripts, PowerShell, and same-origin CLI clients
        try {
            URI uri = URI.create(origin);
            String hostname = uri.getHost();
            return hostname != null && !uri.getScheme().isEmpty() && "http".equalsIgnoreCase(uri.getScheme())
                    && (hostname.equalsIgnoreCase("localhost") || hostname.equals("127.0.0.1") || hostname.equals("[::1]"))
                    && uri.getPort() == 18080;
        } catch (Exception e) { return false; }
    }

    static ObjectNode validatedConfig(JsonNode incoming, ObjectNode existing) {
        if (!incoming.isObject()) throw new IllegalArgumentException("JSON object required");
        String region = incoming.path("region").asText("cn");
        if (!region.equals("cn") && !region.equals("global")) throw new IllegalArgumentException("region must be cn or global");
        String workflow = incoming.path("workflowId").asText("");
        if (!NODE.matcher(workflow).matches()) throw new IllegalArgumentException("workflowId must be a numeric ID");
        JsonNode raw = incoming.path("bindings");
        if (!raw.isArray() || raw.isEmpty() || raw.size() > 40) throw new IllegalArgumentException("Add at least one binding");
        ArrayNode bindings = JSON.createArrayNode();
        for (JsonNode b : raw) {
            String toId = b.path("targetNodeId").asText("");
            String field = b.path("targetField").asText("");
            String source = b.path("source").asText("");
            if (!NODE.matcher(toId).matches() || !field.matches("[a-zA-Z0-9_]{1,100}"))
                throw new IllegalArgumentException("Invalid target node or field");
            if (!source.matches("prompt|globalPrompt|localPrompts|image[1-4]|duration|seed|width|height|fps|node|constant"))
                throw new IllegalArgumentException("Unsupported value source: " + source);
            if (source.equals("node") && (!NODE.matcher(b.path("sourceNodeId").asText("")).matches()
                    || !b.path("sourceField").asText("").matches("[a-zA-Z0-9_]{1,100}")))
                throw new IllegalArgumentException("sourceNodeId and sourceField required for node mapping");
            if (source.equals("constant") && !b.has("value"))
                throw new IllegalArgumentException("value is required for constant");
            bindings.add(b.deepCopy());
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("region", region);
        out.put("workflowId", workflow);
        out.put("enabled", incoming.path("enabled").asBoolean(false));
        String key = incoming.path("apiKey").asText("");
        if (key.isBlank()) key = existing.path("apiKey").asText("");
        if (key.length() > 256 || key.contains("\r") || key.contains("\n"))
            throw new IllegalArgumentException("Invalid API Key");
        out.put("apiKey", key);
        out.set("bindings", bindings);
        return out;
    }

    private static ObjectNode loadConfig() throws IOException {
        if (!Files.isRegularFile(configPath())) return JSON.createObjectNode();
        JsonNode read = JSON.readTree(Files.readAllBytes(configPath()));
        if (read == null || !read.isObject()) throw new IOException("Invalid RunningHub configuration");
        return (ObjectNode) read;
    }

    private static boolean configured(ObjectNode cfg) {
        return cfg.path("enabled").asBoolean(false)
                && !cfg.path("apiKey").asText("").isBlank()
                && NODE.matcher(cfg.path("workflowId").asText("")).matches()
                && cfg.path("bindings").isArray()
                && cfg.path("bindings").size() > 0;
    }

    private static ObjectNode publicConfig() throws IOException {
        ObjectNode config = loadConfig().deepCopy();
        boolean hasKey = !config.path("apiKey").asText("").isBlank();
        config.remove("apiKey");
        config.put("apiKeyConfigured", hasKey);
        config.put("ready", configured(loadConfig()));
        config.put("videoProxyUrl", "http://127.0.0.1:18080/runninghub/comfy");
        return config;
    }

    private void saveConfig(HttpServletRequest req, HttpServletResponse res) throws IOException {
        JsonNode reqJson = JSON.readTree(limited(req.getInputStream(), MAX_JSON));
        ObjectNode config = validatedConfig(reqJson, loadConfig());
        Files.createDirectories(baseDir());
        Path temp = Files.createTempFile(baseDir(), "runninghub-video-", ".tmp");
        try {
            Files.write(temp, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(config),
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.setPosixFilePermissions(temp, EnumSet.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) { /* Windows ACL is inherited from conf/. */ }
            try { Files.move(temp, configPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, configPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp); }
        json(res, 200, publicConfig());
    }

    private void prompt(HttpServletRequest req, HttpServletResponse res) throws Exception {
        ObjectNode cfg = loadConfig();
        if (!configured(cfg)) throw new IllegalArgumentException("Enable RunningHub cloud video and save mappings first");
        JsonNode body = JSON.readTree(limited(req.getInputStream(), MAX_JSON));
        JsonNode upstreamPrompt = body.path("prompt");
        if (!upstreamPrompt.isObject() || upstreamPrompt.isEmpty())
            throw new IllegalArgumentException("ComfyUI prompt object is required");
        ArrayNode nodeInfoList = createBindings(upstreamPrompt, cfg.path("bindings"));
        ObjectNode request = JSON.createObjectNode();
        request.put("apiKey", cfg.path("apiKey").asText());
        request.put("workflowId", cfg.path("workflowId").asText());
        request.set("nodeInfoList", nodeInfoList);
        JsonNode reply = post(cfg, "/task/openapi/create", request);
        String taskId = reply.path("data").path("taskId").asText(reply.path("taskId").asText(""));
        if (!NODE.matcher(taskId).matches()) throw new IOException("No taskId from RunningHub");
        String promptId = UUID.randomUUID().toString();
        Task task = new Task(promptId, taskId);
        TASKS.put(promptId, task);
        persist();
        ObjectNode result = JSON.createObjectNode();
        result.put("prompt_id", promptId);
        result.put("number", 1);
        result.putObject("node_errors");
        json(res, 200, result);
    }

    static ArrayNode createBindings(JsonNode prompt, JsonNode bindings) {
        if (!prompt.isObject() || !bindings.isArray()) throw new IllegalArgumentException("Invalid workflow or bindings");
        ArrayNode list = JSON.createArrayNode();
        for (JsonNode binding : bindings) {
            String source = binding.path("source").asText();
            JsonNode value = bindingValue(prompt, binding, source);
            if (value == null || value.isMissingNode() || value.isNull() || (value.isTextual() && value.asText().isBlank()))
                throw new IllegalArgumentException("Cannot resolve " + source + " for " +
                        binding.path("targetNodeId").asText() + "." + binding.path("targetField").asText());
            ObjectNode mapped = list.addObject();
            mapped.put("nodeId", binding.path("targetNodeId").asText());
            mapped.put("fieldName", binding.path("targetField").asText());
            mapped.set("fieldValue", value.deepCopy());
        }
        if (list.isEmpty()) throw new IllegalArgumentException("No mapped inputs; refusing a paid task");
        return list;
    }

    private static JsonNode bindingValue(JsonNode prompt, JsonNode b, String source) {
        if ("constant".equals(source)) return b.get("value");
        if ("node".equals(source))
            return prompt.path(b.path("sourceNodeId").asText()).path("inputs").path(b.path("sourceField").asText());
        List<JsonNode> inputs = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        prompt.fieldNames().forEachRemaining(keys::add);
        keys.sort(Comparator.comparingLong(k -> {
            try { return Long.parseLong(k); } catch (Exception e) { return Long.MAX_VALUE; }
        }));
        for (String key : keys) {
            JsonNode obj = prompt.path(key);
            JsonNode fields = obj.path("inputs");
            if (fields.isObject()) {
                ObjectNode copy = ((ObjectNode)fields).deepCopy();
                copy.put("_class_type", obj.path("class_type").asText(""));
                inputs.add(copy);
            }
        }
        if ("globalPrompt".equals(source) || "localPrompts".equals(source)) {
            String field = source.equals("globalPrompt") ? "global_prompt" : "local_prompts";
            for (JsonNode node : inputs) {
                JsonNode val = node.path(field);
                if (val.isTextual() && !val.asText().isBlank()) return val;
            }
            return null;
        }
        if ("prompt".equals(source)) {
            for (JsonNode node : inputs) {
                JsonNode global = node.path("global_prompt");
                JsonNode local = node.path("local_prompts");
                if (global.isTextual() && local.isTextual()) {
                    return com.fasterxml.jackson.databind.node.TextNode.valueOf(
                            global.asText() + "\n" + local.asText());
                }
            }
            JsonNode best = null;
            int bestLen = -1;
            for (JsonNode node : inputs) {
                String clazz = node.path("_class_type").asText("").toLowerCase(Locale.ROOT);
                if (clazz.contains("negative")) continue;
                for (String name : List.of("text", "prompt", "positive_prompt", "positive", "user_prompt", "string")) {
                    JsonNode val = node.path(name);
                    if (val.isTextual() && val.asText().length() > bestLen) {
                        best = val; bestLen = val.asText().length();
                    }
                }
            }
            return best;
        }
        if (source.startsWith("image")) {
            int want = Integer.parseInt(source.substring(5));
            List<JsonNode> images = new ArrayList<>();
            for (JsonNode node : inputs) {
                String cls = node.path("_class_type").asText("").toLowerCase(Locale.ROOT);
                for (String name : List.of("image", "image1", "image2", "image3", "image4", "filename")) {
                    JsonNode val = node.path(name);
                    if (!val.isTextual()) continue;
                    String file = val.asText();
                    if ((cls.contains("image") || cls.contains("load") || cls.contains("ref"))
                            && file.matches("(?i).+\\.(png|jpeg|jpg|webp|gif|bmp)$"))
                        images.add(val);
                }
            }
            return want <= images.size() ? images.get(want - 1) : null;
        }
        List<String> names = switch (source) {
            case "duration" -> List.of("duration", "duration_seconds", "seconds", "length");
            case "seed" -> List.of("seed", "noise_seed");
            case "width" -> List.of("width");
            case "height" -> List.of("height");
            case "fps" -> List.of("fps", "frame_rate");
            default -> List.of();
        };
        for (JsonNode node : inputs) for (String name : names) {
            JsonNode val = node.path(name);
            if (val.isNumber() || (val.isTextual() && val.asText().matches("[0-9]+(\\.[0-9]+)?"))) return val;
        }
        return null;
    }

    private void upload(HttpServletRequest req, HttpServletResponse res, String path) throws Exception {
        ObjectNode cfg = loadConfig();
        if (!configured(cfg)) throw new IllegalArgumentException("RunningHub cloud video not configured");
        String type = req.getContentType();
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("multipart/form-data"))
            throw new IllegalArgumentException("ComfyUI upload must use multipart/form-data");
        String field = path.endsWith("/image") ? "image" : path.endsWith("/video") ? "video" : "audio";
        Part part = req.getPart(field);
        if (part == null) part = req.getPart("file");
        if (part == null || part.getSize() == 0 || part.getSize() > 64L * 1024 * 1024)
            throw new IllegalArgumentException("Upload missing or larger than 64 MB");
        String originalName = part.getSubmittedFileName();
        if (originalName == null || !originalName.toLowerCase(Locale.ROOT).matches(".+\\.(png|jpg|jpeg|webp|gif|bmp|mp4|webm|mov|wav|mp3|flac|zip)$"))
            throw new IllegalArgumentException("Unsupported input media");
        String filename = originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (filename.length() > 90) filename = filename.substring(filename.length() - 90);
        String boundary = "cloudbridge-" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename +
                "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        try (InputStream in = part.getInputStream()) { in.transferTo(data); }
        data.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        JsonNode upload = remote(cfg, "/openapi/v2/media/upload/binary",
                "multipart/form-data; boundary=" + boundary, data.toByteArray());
        String remoteName = upload.path("data").path("fileName").asText(
                upload.path("data").path("filename").asText(""));
        if (remoteName.isBlank()) throw new IOException("RunningHub upload did not return fileName / filename");
        ObjectNode result = JSON.createObjectNode();
        result.put("name", remoteName); result.put("subfolder", ""); result.put("type", "input");
        json(res, 200, result);
    }

    private void queue(HttpServletResponse res) throws Exception {
        // The original backend may poll /queue before it ever polls /history.
        for (Task task : TASKS.values()) {
            if (!"SUCCESS".equals(task.status) && !"FAILED".equals(task.status)) historyRecord(task);
        }
        ObjectNode data = JSON.createObjectNode();
        ArrayNode running = data.putArray("queue_running");
        for (Task t : TASKS.values()) if ("QUEUED".equals(t.status) || "RUNNING".equals(t.status)) {
            ArrayNode row = running.addArray(); row.add(0); row.add(t.promptId); row.addObject();
        }
        data.putArray("queue_pending");
        json(res, 200, data);
    }

    private void historyAll(HttpServletResponse res) throws Exception {
        ObjectNode data = JSON.createObjectNode();
        int count = 0;
        for (Task t : TASKS.values()) {
            if (++count > 50) break;
            JsonNode history = historyRecord(t);
            if (history != null) data.set(t.promptId, history);
        }
        json(res, 200, data);
    }

    private void history(HttpServletResponse res, String id) throws Exception {
        ObjectNode data = JSON.createObjectNode();
        Task t = TASKS.get(id);
        if (t != null) {
            JsonNode record = historyRecord(t);
            if (record != null) data.set(id, record);
        }
        json(res, 200, data);
    }

    private JsonNode historyRecord(Task task) throws Exception {
        synchronized (task) {
            if (!"SUCCESS".equals(task.status) && !"FAILED".equals(task.status)) {
                JsonNode query = post(loadConfig(), "/openapi/v2/query",
                        JSON.createObjectNode().put("taskId", task.remoteId));
                String status = query.path("status").asText("").toUpperCase(Locale.ROOT);
                if ("SUCCESS".equals(status)) {
                    JsonNode results = query.path("results");
                    JsonNode selected = null;
                    if (results.isArray()) for (JsonNode result : results) {
                        String fmt = result.path("outputType").asText("").toLowerCase(Locale.ROOT);
                        String link = result.path("url").asText("").toLowerCase(Locale.ROOT);
                        if (fmt.matches("mp4|webm|mov") || link.matches("(?s).*\\.(mp4|webm|mov)(\\?.*)?$")) {
                            selected = result; break;
                        }
                    }
                    if (selected == null) throw new IOException("RunningHub returned SUCCESS without a video file");
                    task.filename = downloadResult(task.promptId, selected.path("url").asText(""),
                            selected.path("outputType").asText(""));
                    task.status = "SUCCESS";
                } else if (status.matches("FAILED|ERROR|FAIL|CANCELED|CANCELLED")) {
                    task.status = "FAILED";
                } else task.status = "RUNNING";
                persist();
            }
            if (!"SUCCESS".equals(task.status) && !"FAILED".equals(task.status)) return null;
            ObjectNode record = JSON.createObjectNode();
            ObjectNode status = record.putObject("status");
            status.put("completed", true);
            status.put("status_str", "SUCCESS".equals(task.status) ? "success" : "error");
            status.putArray("messages");
            ObjectNode outputs = record.putObject("outputs");
            if ("SUCCESS".equals(task.status)) {
                ObjectNode output = outputs.putObject("1");
                ArrayNode gifs = output.putArray("gifs");
                ObjectNode file = gifs.addObject();
                file.put("filename", task.filename);
                file.put("subfolder", "");
                file.put("type", "output");
                output.set("videos", gifs.deepCopy());
            }
            return record;
        }
    }

    private static String downloadResult(String promptId, String url, String hint) throws Exception {
        URI uri = URI.create(url);
        if (!RunningHubServlet.allowedResultUri(uri))
            throw new IllegalArgumentException("RunningHub returned an unsupported download host");
        String ext = hint.toLowerCase(Locale.ROOT);
        if (!ext.matches("mp4|webm|mov")) {
            String path = uri.getPath().toLowerCase(Locale.ROOT);
            ext = path.endsWith(".mov") ? "mov" : path.endsWith(".webm") ? "webm" : "mp4";
        }
        Files.createDirectories(outputDir());
        String filename = promptId + "." + ext;
        Path target = outputDir().resolve(filename);
        if (Files.isRegularFile(target) && Files.size(target) > 0) return filename;
        HttpResponse<InputStream> response = HTTP.send(HttpRequest.newBuilder(uri).GET()
                .timeout(Duration.ofMinutes(8)).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close(); throw new IOException("Video download returned " + response.statusCode());
        }
        Path tmp = Files.createTempFile(outputDir(), "cloud-", ".partial");
        try (InputStream in = response.body();
             var out = Files.newOutputStream(tmp, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[65536]; long count = 0; int n;
            while ((n = in.read(buf)) != -1) {
                count += n;
                if (count > MAX_VIDEO) throw new IOException("Video result exceeded 512 MB");
                out.write(buf, 0, n);
            }
        } catch (Exception e) { Files.deleteIfExists(tmp); throw e; }
        try { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(tmp); }
        return filename;
    }

    private static void view(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String filename = req.getParameter("filename");
        if (filename == null || !OUTPUT_NAME.matcher(filename).matches()) {
            fail(res, 400, "Invalid cloud video filename"); return;
        }
        boolean known = TASKS.values().stream().anyMatch(t -> filename.equals(t.filename));
        if (!known) { fail(res, 404, "Unknown cloud video"); return; }
        Path file = outputDir().resolve(filename);
        if (!Files.isRegularFile(file)) { fail(res, 404, "Cloud video file not present"); return; }
        String mime = filename.endsWith(".webm") ? "video/webm" : filename.endsWith(".mov") ? "video/quicktime" : "video/mp4";
        res.setContentType(mime);
        res.setHeader("Content-Disposition", "inline; filename=\"" + filename + "\"");
        res.setContentLengthLong(Files.size(file));
        try (var in = Files.newInputStream(file)) { in.transferTo(res.getOutputStream()); }
    }

    private static JsonNode post(ObjectNode cfg, String endpoint, JsonNode payload) throws Exception {
        return remote(cfg, endpoint, "application/json", JSON.writeValueAsBytes(payload));
    }

    private static JsonNode remote(ObjectNode cfg, String endpoint, String contentType, byte[] payload) throws Exception {
        if (!configured(cfg)) throw new IllegalArgumentException("RunningHub video integration is disabled");
        String base = cfg.path("region").asText().equals("global") ? "https://www.runninghub.ai" : "https://www.runninghub.cn";
        String key = cfg.path("apiKey").asText();
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + endpoint))
                .timeout(Duration.ofMinutes(3))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
        HttpResponse<byte[]> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) throw new IOException("RunningHub HTTP " + resp.statusCode());
        if (resp.body().length > MAX_JSON) throw new IOException("RunningHub response too large");
        JsonNode json = JSON.readTree(resp.body());
        if (json == null || !json.isObject()) throw new IOException("Invalid RunningHub JSON response");
        if (json.has("code") && json.get("code").canConvertToInt() && json.get("code").asInt() != 0
                && json.get("code").asInt() != 200) {
            String msg = json.path("msg").asText(json.path("message").asText("API error")).replace(key, "[hidden]");
            if (msg.length() > 180) msg = msg.substring(0, 180);
            throw new IOException("RunningHub " + json.get("code").asText() + ": " + msg);
        }
        return json;
    }

    private static void persist() throws IOException {
        synchronized (STATE_LOCK) {
            Files.createDirectories(baseDir());
            ArrayNode state = JSON.createArrayNode();
            for (Task t : TASKS.values()) {
                ObjectNode o = state.addObject();
                o.put("promptId", t.promptId); o.put("remoteId", t.remoteId);
                o.put("status", t.status); o.put("filename", t.filename);
            }
            Path tmp = Files.createTempFile(baseDir(), "cloud-tasks-", ".tmp");
            try {
                Files.write(tmp, JSON.writeValueAsBytes(state), StandardOpenOption.TRUNCATE_EXISTING);
                try { Files.move(tmp, statePath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, statePath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(tmp); }
        }
    }

    private static byte[] limited(InputStream stream, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] data = new byte[8192]; int n;
        while ((n = stream.read(data)) != -1) {
            if (out.size() + n > max) throw new IllegalArgumentException("JSON payload too large");
            out.write(data, 0, n);
        }
        return out.toByteArray();
    }

    private static void headers(HttpServletResponse res) {
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("X-Content-Type-Options", "nosniff");
    }

    private static void json(HttpServletResponse res, int status, JsonNode value) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.setCharacterEncoding("UTF-8");
        JSON.writeValue(res.getWriter(), value);
    }

    private static void fail(HttpServletResponse res, int status, String msg) throws IOException {
        json(res, status, JSON.createObjectNode().put("error", msg));
    }
}