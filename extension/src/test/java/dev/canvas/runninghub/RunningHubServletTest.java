package dev.canvas.runninghub;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunningHubServletTest {
    @Test
    void allowsRunningHubMediaHostsOnly() {
        assertTrue(RunningHubServlet.allowedResultUri(URI.create(
                "https://rh-images-1252422369.cos.ap-beijing.myqcloud.com/images/example.png")));
        assertTrue(RunningHubServlet.allowedResultUri(URI.create("https://files.runninghub.cn/example.png")));
        assertFalse(RunningHubServlet.allowedResultUri(URI.create("http://files.runninghub.cn/example.png")));
        assertFalse(RunningHubServlet.allowedResultUri(URI.create("https://evil-runninghub.cn/example.png")));
        assertFalse(RunningHubServlet.allowedResultUri(URI.create("https://127.0.0.1/secret")));
        assertFalse(RunningHubServlet.allowedResultUri(URI.create("https://user@files.runninghub.cn/example.png")));
        assertFalse(RunningHubServlet.allowedResultUri(URI.create("https://files.runninghub.cn:443/example.png")));
    }

    @Test
    void healthEndpointReturnsJson() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        StringWriter data = new StringWriter();
        when(req.getPathInfo()).thenReturn("/health");
        when(res.getWriter()).thenReturn(new PrintWriter(data));
        new RunningHubServlet().doGet(req, res);
        verify(res).setStatus(200);
        verify(res).setHeader("Cache-Control", "no-store");
        assertTrue(data.toString().contains("\"ok\":true"));
    }

    @Test
    void rejectsMissingApiKeyWithoutSendingNetworkRequest() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        StringWriter data = new StringWriter();
        when(req.getPathInfo()).thenReturn("/submit");
        when(res.getWriter()).thenReturn(new PrintWriter(data));
        new RunningHubServlet().doPost(req, res);
        verify(res).setStatus(401);
        assertTrue(data.toString().contains("error"));
    }

    @Test
    void rejectsUnexpectedRegion() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        StringWriter data = new StringWriter();
        when(req.getHeader("X-RunningHub-Key")).thenReturn("fake-key-for-local-test");
        when(req.getHeader("X-RunningHub-Region")).thenReturn("other-site");
        when(res.getWriter()).thenReturn(new PrintWriter(data));
        new RunningHubServlet().doPost(req, res);
        verify(res).setStatus(400);
    }
}
