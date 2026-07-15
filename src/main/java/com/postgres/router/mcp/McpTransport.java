package com.postgres.router.mcp;

/**
 * MCP 传输层接口。
 * 抽象 JSON-RPC 响应/错误的发送方式，支持 stdio 和 HTTP 两种传输。
 */
public interface McpTransport {

    /** 发送成功响应 */
    void sendResponse(Object id, Object result);

    /** 发送错误响应 */
    void sendError(Object id, int code, String message);

    /** 发送通知（无 id 的消息） */
    void sendNotification(String method, Object params);
}
