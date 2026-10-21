package dev.canvas.runninghub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComfyCloudBridgeServletTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode baseConfig() throws Exception {
        ObjectNode config = (ObjectNode) MAPPER.readTree("""
              {"region":"cn","workflowId":"12345678","apiKey":"TEST_ONLY_NOT_REAL","enabled":true,
               "bindings":[
                 {"targetNodeId":"301","targetField":"text","source":"prompt"},
                 {"targetNodeId":"302","targetField":"image","source":"image1"},
                 {"targetNodeId":"303","targetField":"duration","source":"duration"}
               ]}
            """);
        return ComfyCloudBridgeServlet.validatedConfig(config, MAPPER.createObjectNode());
    }

    @Test void mapsComfyInputsToRunningHubNodeOverrides() throws Exception {
        ObjectNode config = baseConfig();
        JsonNode comfy = MAPPER.readTree("""
            {
              "185":{"class_type":"CLIPTextEncode","inputs":{"text":"negative, blur"}},
              "180":{"class_type":"PromptRelayEncode","inputs":{
                  "global_prompt":"角色是古装女子","local_prompts":"镜头缓慢推进"}},
              "29":{"class_type":"LoadImage","inputs":{"image":"openapi/character.png"}},
              "50":{"class_type":"FrameInfo","inputs":{"duration":5}}
            }
            """);
        ArrayNode mapped = ComfyCloudBridgeServlet.createBindings(comfy, config.path("bindings"));
        assertEquals(3,mapped.size());
        assertEquals("301",mapped.get(0).path("nodeId").asText());
        assertEquals("角色是古装女子\n镜头缓慢推进",mapped.get(0).path("fieldValue").asText());
        assertEquals("openapi/character.png",mapped.get(1).path("fieldValue").asText());
        assertEquals(5,mapped.get(2).path("fieldValue").asInt());
    }

    @Test void rejectsMissingImageBeforePaidSubmit() throws Exception {
        JsonNode comfy = MAPPER.readTree("""
            {"180":{"class_type":"PromptRelayEncode","inputs":{"global_prompt":"一人走过街道","local_prompts":"雨夜"}}}
            """);
        ObjectNode config = baseConfig();
        assertThrows(IllegalArgumentException.class,
            () -> ComfyCloudBridgeServlet.createBindings(comfy, config.path("bindings")));
    }

    @Test void rejectsUnmappedOrInvalidConfiguration() throws Exception {
        ObjectNode cfg=baseConfig();
        cfg.withArray("bindings").removeAll();
        assertThrows(IllegalArgumentException.class,
            () -> ComfyCloudBridgeServlet.validatedConfig(cfg, MAPPER.createObjectNode()));
        cfg.withArray("bindings").addObject().put("targetNodeId","1").put("targetField","x").put("source","bad");
        assertThrows(IllegalArgumentException.class,
            () -> ComfyCloudBridgeServlet.validatedConfig(cfg, MAPPER.createObjectNode()));
    }

    @Test void remoteRequestIsForbidden() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter sink = new StringWriter();
        when(request.getRemoteAddr()).thenReturn("192.0.2.99");
        when(response.getWriter()).thenReturn(new PrintWriter(sink));
        new ComfyCloudBridgeServlet().doPost(request,response);
        verify(response).setStatus(403);
    }

    @Test void preservesSecretWhenEditingOtherFields() throws Exception {
        ObjectNode existing=baseConfig();
        ObjectNode update=existing.deepCopy();
        update.put("apiKey","");
        ObjectNode merged=ComfyCloudBridgeServlet.validatedConfig(update,existing);
        assertEquals("TEST_ONLY_NOT_REAL",merged.get("apiKey").asText());
    }
}