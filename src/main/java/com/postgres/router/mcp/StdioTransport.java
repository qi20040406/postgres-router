package com.postgres.router.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.postgres.router.mcp.JsonRpcMessage.Request;
import com.postgres.router.mcp.JsonRpcMessage.Response;
import com.postgres.router.mcp.JsonRpcMessage.Error;
import com.postgres.router.mcp.JsonRpcMessage.ErrorData;

import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * stdio 传输层。
 * 从 System.in 读取 JSON-RPC 消息，向 System.out 写入 JSON-RPC 消息。
 */
public class StdioTransport implements AutoCloseable, McpTransport {

    private static final Logger LOG = Logger.getLogger(StdioTransport.class.getName());

    private final BufferedReader reader;
    private final PrintWriter writer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stdio-reader");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Consumer<Request> onRequest;

    public StdioTransport() {
        this.reader = new BufferedReader(new InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
        this.writer = new PrintWriter(new OutputStreamWriter(System.out, java.nio.charset.StandardCharsets.UTF_8), true);
    }

    /** 设置请求回调 */
    public void onRequest(Consumer<Request> handler) {
        this.onRequest = handler;
    }

    /** 启动读取循环 */
    public void start() {
        executor.submit(() -> {
            while (running.get()) {
                try {
                    String line = reader.readLine();
                    if (line == null) {
                        LOG.info("stdin 已关闭，退出读取循环");
                        break;
                    }
                    if (line.isBlank()) continue;

                    // 解析为 Request
                    Request req = JsonRpcMessage.parseRequest(line);
                    if (onRequest != null && req != null) {
                        onRequest.accept(req);
                    }
                } catch (com.fasterxml.jackson.core.JsonParseException e) {
                    LOG.warning("无效的 JSON-RPC 消息: " + e.getMessage());
                } catch (IOException e) {
                    if (running.get()) {
                        LOG.log(Level.WARNING, "stdin 读取异常", e);
                    }
                    break;
                }
            }
        });
    }

    @Override
    public String getRemoteAddr() {
        return "@stdio";
    }

    /** 发送成功响应 */
    public void sendResponse(Object id, Object result) {
        try {
            String json = JsonRpcMessage.toJson(new Response(id, result));
            writer.println(json);
        } catch (JsonProcessingException e) {
            LOG.log(Level.SEVERE, "序列化响应失败", e);
        }
    }

    /** 发送错误响应 */
    public void sendError(Object id, int code, String message) {
        try {
            String json = JsonRpcMessage.toJson(new Error(id, code, message));
            writer.println(json);
        } catch (JsonProcessingException e) {
            LOG.log(Level.SEVERE, "序列化错误响应失败", e);
        }
    }

    /** 发送通知（无 id 的消息） */
    public void sendNotification(String method, Object params) {
        try {
            var notification = new java.util.HashMap<String, Object>();
            notification.put("jsonrpc", "2.0");
            notification.put("method", method);
            if (params != null) notification.put("params", params);
            String json = JsonRpcMessage.toJson(notification);
            writer.println(json);
        } catch (JsonProcessingException e) {
            LOG.log(Level.SEVERE, "序列化通知失败", e);
        }
    }

    @Override
    public void close() {
        running.set(false);
        executor.shutdownNow();
        try { reader.close(); } catch (IOException ignored) {}
        writer.close();
    }
}
