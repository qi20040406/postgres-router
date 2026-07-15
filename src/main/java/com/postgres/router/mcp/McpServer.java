package com.postgres.router.mcp;

import com.postgres.router.config.RouterConfig;
import com.postgres.router.jdbc.QueryExecutor;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * MCP 服务器主循环。
 * 处理 MCP 协议握手、tools/list、tools/call 等核心流程。
 */
public class McpServer {

    private static final Logger LOG = Logger.getLogger(McpServer.class.getName());

    private static final String MCP_VERSION = "2025-06-18";
    private static final String SERVER_NAME = "postgres-router";
    private static final String SERVER_VERSION = "1.0.0";

    private final McpTransport transport;
    private final ToolHandler toolHandler;
    private volatile boolean initialized = false;

    /** 活跃 session：id → SessionInfo */
    private final ConcurrentHashMap<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    public record SessionInfo(String clientName, String clientVersion, String remoteAddr,
                              Instant connectedAt, Instant lastActivity) {}

    public Map<String, SessionInfo> getSessions() {
        return Collections.unmodifiableMap(new HashMap<>(sessions));
    }

    public McpServer(McpTransport transport, QueryExecutor queryExecutor) {
        this.transport = transport;
        this.toolHandler = new ToolHandler(queryExecutor);
    }

    /** 启动服务器（仅 stdio 模式需要） */
    public void start() {
        if (transport instanceof StdioTransport st) {
            st.onRequest(this::handleRequest);
            st.start();
        } else {
            LOG.warning("start() 仅支持 StdioTransport");
        }
    }

    /** 处理单个 JSON-RPC 请求（使用默认 transport）*/
    @SuppressWarnings("unchecked")
    public void handleRequest(JsonRpcMessage.Request req) {
        dispatchRequest(req, this.transport);
    }

    /** 处理单个 JSON-RPC 请求（指定 transport，HTTP 模式使用）*/
    @SuppressWarnings("unchecked")
    public void processRequest(JsonRpcMessage.Request req, McpTransport responseTransport) {
        dispatchRequest(req, responseTransport);
    }

    @SuppressWarnings("unchecked")
    private void dispatchRequest(JsonRpcMessage.Request req, McpTransport t) {
        Object id = req.id;
        String method = req.method;

        if (id == null) {
            handleNotification(method, req.params);
            return;
        }

        try {
            switch (method) {
                case "initialize" -> handleInitialize(id, (Map<String, Object>) req.params, t);
                case "initialized" -> handleInitialized(id, t);
                case "tools/list"   -> handleToolsList(id, t);
                case "tools/call"   -> handleToolsCall(id, (Map<String, Object>) req.params, t);
                case "ping"         -> handlePing(id, t);
                case "notifications/initialized" -> handleInitialized(id, t);
                default -> t.sendError(id, -32601, "Method not found: " + method);
            }
        } catch (Exception e) {
            LOG.severe("处理请求异常: " + method + " - " + e.getMessage());
            t.sendError(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    /** 处理通知（无 id 的消息） */
    private void handleNotification(String method, Object params) {
        switch (method) {
            case "notifications/initialized" -> initialized = true;
            case "notifications/cancelled" -> LOG.fine("收到取消通知");
            default -> LOG.fine("忽略未知通知: " + method);
        }
    }

    /** MCP Initialize 握手 */
    private void handleInitialize(Object id, Map<String, Object> params, McpTransport t) {
        Map<String, Object> clientInfo = params != null ? (Map<String, Object>) params.get("clientInfo") : null;
        String clientName = clientInfo != null ? (String) clientInfo.get("name") : "unknown";
        String clientVersion = clientInfo != null ? (String) clientInfo.get("version") : "";
        LOG.info("收到 initialize 请求 (client: " + clientInfo + ")");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", MCP_VERSION);
        result.put("capabilities", Map.of(
                "tools", Map.of("listChanged", true)
        ));
        result.put("serverInfo", Map.of(
                "name", SERVER_NAME,
                "version", SERVER_VERSION
        ));

        t.sendResponse(id, result);

        // 记录 session
        String sessionKey = id != null ? id.toString() : UUID.randomUUID().toString();
        sessions.put(sessionKey, new SessionInfo(
                clientName, clientVersion, "",
                Instant.now(), Instant.now()
        ));
    }

    /** MCP Initialized 通知确认 */
    private void handleInitialized(Object id, McpTransport t) {
        initialized = true;
        LOG.info("客户端初始化完成");
        if (id != null) {
            t.sendResponse(id, Map.of());
        }
    }

    /** tools/list */
    private void handleToolsList(Object id, McpTransport t) {
        Map<String, Object> result = Map.of("tools", toolHandler.listTools());
        t.sendResponse(id, result);
    }

    /** tools/call */
    @SuppressWarnings("unchecked")
    private void handleToolsCall(Object id, Map<String, Object> params, McpTransport t) {
        String toolName = params != null ? (String) params.get("name") : null;
        Map<String, Object> arguments = params != null ? (Map<String, Object>) params.get("arguments") : null;

        if (toolName == null) {
            t.sendError(id, -32602, "Missing tool name");
            return;
        }

        try {
            List<Map<String, Object>> content = toolHandler.callTool(toolName, arguments);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", content);
            result.put("isError", false);
            t.sendResponse(id, result);
        } catch (IllegalArgumentException e) {
            t.sendError(id, -32602, e.getMessage());
        } catch (Exception e) {
            LOG.severe("工具调用失败: " + toolName + " - " + e.getMessage());
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("content", List.of(Map.of(
                    "type", "text",
                    "text", "Error: " + e.getMessage()
            )));
            errorResult.put("isError", true);
            t.sendResponse(id, errorResult);
        }
    }

    /** Ping 回显 */
    private void handlePing(Object id, McpTransport t) {
        t.sendResponse(id, Map.of());
    }
}
