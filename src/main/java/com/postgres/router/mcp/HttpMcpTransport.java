package com.postgres.router.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.postgres.router.mcp.JsonRpcMessage.Response;
import com.postgres.router.mcp.JsonRpcMessage.Error;
import com.postgres.router.mcp.JsonRpcMessage.ErrorData;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * HTTP 传输层。
 * 不写 stdio，把响应存入队列供 HTTP handler 轮询读取。
 */
public class HttpMcpTransport implements McpTransport {

    private static final Logger LOG = Logger.getLogger(HttpMcpTransport.class.getName());

    private final BlockingQueue<String> responseQueue = new ArrayBlockingQueue<>(1);

    @Override
    public void sendResponse(Object id, Object result) {
        try {
            String json = JsonRpcMessage.toJson(new Response(id, result));
            responseQueue.offer(json);
        } catch (JsonProcessingException e) {
            LOG.severe("序列化响应失败: " + e.getMessage());
        }
    }

    @Override
    public void sendError(Object id, int code, String message) {
        try {
            String json = JsonRpcMessage.toJson(new Error(id, code, message));
            responseQueue.offer(json);
        } catch (JsonProcessingException e) {
            LOG.severe("序列化错误响应失败: " + e.getMessage());
        }
    }

    @Override
    public void sendNotification(String method, Object params) {
        // HTTP 场景下通知不需要响应
    }

    /** 阻塞等待获取响应 JSON（最多等 30 秒）*/
    public String waitForResponse() {
        try {
            return responseQueue.poll(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
