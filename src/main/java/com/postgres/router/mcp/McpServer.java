package com.postgres.router.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.postgres.router.jdbc.QueryExecutor;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * MCP 服务器主循环。
 * 处理 MCP 协议握手、tools/list、tools/call 等核心流程。
 *
 * <p>重构要点：
 * <ul>
 *   <li>SessionInfo 改为可变类，按 clientName 键控（HTTP 模式可正确追踪）</li>
 *   <li>remoteAddr 通过 transport.getRemoteAddr() 获取，不再依赖事后更新</li>
 *   <li>每次请求自动记录结构化日志到 ServiceLogStore</li>
 *   <li>session 超过 120 秒无活动自动清理</li>
 * </ul>
 */
public class McpServer {

    private static final Logger LOG = Logger.getLogger(McpServer.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MCP_VERSION = "2025-06-18";
    private static final String SERVER_NAME = "postgres-router";
    private static final String SERVER_VERSION = "1.0.0";

    /** 会话超时：超过此时间无活动的 session 在 getSessions() 中自动清理 */
    private static final long SESSION_TIMEOUT_SECONDS = 120;

    private final McpTransport transport;
    private final ToolHandler toolHandler;
    private volatile boolean initialized = false;
    private final ServiceLogStore logStore;

    /** 活跃 session：clientName → SessionInfo（可变） */
    private final ConcurrentHashMap<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    // ======================== SessionInfo ========================

    /** 会话追踪可变类。按 clientName 键控，支持更新 lastActivity 和 remoteAddr。 */
    public static class SessionInfo {
        private final String clientName;
        private final String clientVersion;
        private String remoteAddr;
        private final Instant connectedAt;
        private Instant lastActivity;

        public SessionInfo(String clientName, String clientVersion, String remoteAddr,
                           Instant connectedAt, Instant lastActivity) {
            this.clientName = clientName;
            this.clientVersion = clientVersion;
            this.remoteAddr = remoteAddr;
            this.connectedAt = connectedAt;
            this.lastActivity = lastActivity;
        }

        public String clientName() { return clientName; }
        public String clientVersion() { return clientVersion; }
        public String remoteAddr() { return remoteAddr; }
        public Instant connectedAt() { return connectedAt; }
        public Instant lastActivity() { return lastActivity; }

        void setRemoteAddr(String addr) { this.remoteAddr = addr; }
        void setLastActivity(Instant time) { this.lastActivity = time; }

        @Override
        public String toString() {
            return "SessionInfo{" + clientName + "@" + remoteAddr + "}";
        }
    }

    // ======================== Constructors ========================

    public McpServer(McpTransport transport, QueryExecutor queryExecutor) {
        this(transport, queryExecutor, new ServiceLogStore());
    }

    public McpServer(McpTransport transport, QueryExecutor queryExecutor,
                     ServiceLogStore logStore) {
        this.transport = transport;
        this.toolHandler = new ToolHandler(queryExecutor);
        this.logStore = logStore;
    }

    // ======================== Accessors ========================

    public Map<String, SessionInfo> getSessions() {
        cleanupExpiredSessions();
        return Collections.unmodifiableMap(new HashMap<>(sessions));
    }

    public ServiceLogStore getLogStore() {
        return logStore;
    }

    // ======================== Lifecycle ========================

    /** 启动服务器（仅 stdio 模式需要） */
    public void start() {
        if (transport instanceof StdioTransport st) {
            st.onRequest(this::handleRequest);
            st.start();
        } else {
            LOG.warning("start() 仅支持 StdioTransport");
        }
    }

    // ======================== Request Entry Points ========================

    /** stdio 回调入口 */
    public void handleRequest(JsonRpcMessage.Request req) {
        processRequest(req, transport);
    }

    /**
     * 处理单个 JSON-RPC 请求（HTTP 模式入口，传入请求专属的 responseTransport）。
     * 内部负责记录结构化日志、刷新 session lastActivity。
     */
    public void processRequest(JsonRpcMessage.Request req, McpTransport responseTransport) {
        long startTime = System.currentTimeMillis();
        String remoteAddr = responseTransport.getRemoteAddr();

        // 非 init 请求：通过 remoteAddr 刷新最近 session 的 lastActivity
        if (!"initialize".equals(req.method)) {
            refreshSessionByAddr(remoteAddr);
        }

        // 包装 transport 以捕获响应内容（不影响原 transport 的响应）
        CaptureTransport capture = new CaptureTransport(responseTransport);
        dispatchRequest(req, capture);

        long duration = System.currentTimeMillis() - startTime;
        String agentName = resolveAgentName(remoteAddr, req);
        String responseContent = capture.captured.get();

        String sKey = agentName != null ? agentName : remoteAddr;
        logStore.record(sKey, agentName, req.method,
                safeSerialize(req.params), responseContent,
                capture.capturedIsError.get(), duration);
    }

    // ======================== Dispatcher ========================

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
                case "initialize"                -> handleInitialize(id, (Map<String, Object>) req.params, t);
                case "initialized"               -> handleInitialized(id, t);
                case "tools/list"                -> handleToolsList(id, t);
                case "tools/call"                -> handleToolsCall(id, (Map<String, Object>) req.params, t);
                case "ping"                      -> handlePing(id, t);
                case "notifications/initialized" -> handleInitialized(id, t);
                default                          -> t.sendError(id, -32601, "Method not found: " + method);
            }
        } catch (Exception e) {
            LOG.severe("处理请求异常: " + method + " - " + e.getMessage());
            t.sendError(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    // ======================== Handlers ========================

    private void handleNotification(String method, Object params) {
        switch (method) {
            case "notifications/initialized" -> initialized = true;
            case "notifications/cancelled"   -> LOG.fine("收到取消通知");
            default                          -> LOG.fine("忽略未知通知: " + method);
        }
    }

    /** MCP Initialize 握手 —— session key = clientName */
    @SuppressWarnings("unchecked")
    private void handleInitialize(Object id, Map<String, Object> params, McpTransport t) {
        Map<String, Object> clientInfo = params != null ? (Map<String, Object>) params.get("clientInfo") : null;
        String clientName = clientInfo != null ? (String) clientInfo.get("name") : "unknown";
        String clientVersion = clientInfo != null ? (String) clientInfo.get("version") : "";
        String remoteAddr = t.getRemoteAddr();
        LOG.info("收到 initialize 请求 (client: " + clientInfo + ", addr: " + remoteAddr + ")");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", MCP_VERSION);
        result.put("capabilities", Map.of("tools", Map.of("listChanged", true)));
        result.put("serverInfo", Map.of("name", SERVER_NAME, "version", SERVER_VERSION));
        t.sendResponse(id, result);

        // 以 clientName 作为 session key（同名 agent 重连视为同一 session，保留原连接时间）
        SessionInfo existing = sessions.get(clientName);
        Instant originalConnectedAt = existing != null ? existing.connectedAt() : Instant.now();
        sessions.put(clientName, new SessionInfo(
                clientName, clientVersion, remoteAddr, originalConnectedAt, Instant.now()
        ));
    }

    private void handleInitialized(Object id, McpTransport t) {
        initialized = true;
        LOG.info("客户端初始化完成");
        if (id != null) {
            t.sendResponse(id, Map.of());
        }
    }

    private void handleToolsList(Object id, McpTransport t) {
        t.sendResponse(id, Map.of("tools", toolHandler.listTools()));
    }

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
            t.sendResponse(id, Map.of("content", content, "isError", false));
        } catch (IllegalArgumentException e) {
            t.sendError(id, -32602, e.getMessage());
        } catch (Exception e) {
            LOG.severe("工具调用失败: " + toolName + " - " + e.getMessage());
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("content", List.of(Map.of("type", "text", "text", "Error: " + e.getMessage())));
            errorResult.put("isError", true);
            t.sendResponse(id, errorResult);
        }
    }

    private void handlePing(Object id, McpTransport t) {
        t.sendResponse(id, Map.of());
    }

    // ======================== Helpers ========================

    /** 通过 remoteAddr 刷新最近活跃 session 的 lastActivity */
    private void refreshSessionByAddr(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isEmpty()) return;
        sessions.values().stream()
                .filter(s -> remoteAddr.equals(s.remoteAddr()))
                .max(Comparator.comparing(SessionInfo::lastActivity))
                .ifPresent(s -> s.setLastActivity(Instant.now()));
    }

    /** 解析当前请求关联的 agent 名称（用于日志记录） */
    @SuppressWarnings("unchecked")
    private String resolveAgentName(String remoteAddr, JsonRpcMessage.Request req) {
        if ("initialize".equals(req.method)) {
            var params = req.params;
            if (params instanceof Map<?, ?> p) {
                Object ci = p.get("clientInfo");
                if (ci instanceof Map<?, ?> c) {
                    Object n = c.get("name");
                    if (n instanceof String s && !s.isBlank()) return s;
                }
            }
            return "unknown";
        }
        // 非 init 请求：通过 remoteAddr 反向匹配最近 session
        if (remoteAddr != null && !remoteAddr.isEmpty()) {
            return sessions.values().stream()
                    .filter(s -> remoteAddr.equals(s.remoteAddr()))
                    .max(Comparator.comparing(SessionInfo::lastActivity))
                    .map(SessionInfo::clientName)
                    .orElse(null);
        }
        return null;
    }

    /** 清理超时 session */
    private void cleanupExpiredSessions() {
        Instant cutoff = Instant.now().minusSeconds(SESSION_TIMEOUT_SECONDS);
        sessions.entrySet().removeIf(e -> e.getValue().lastActivity().isBefore(cutoff));
    }

    /** JSON 容错序列化 */
    private String safeSerialize(Object obj) {
        if (obj == null) return "null";
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return obj.toString();
        }
    }

    // ======================== Inner Class: CaptureTransport ========================

    /**
     * 包装 transport，在透传所有调用到 delegate 的同时捕获响应内容，
     * 用于结构化日志记录。
     */
    private static class CaptureTransport implements McpTransport {
        private final McpTransport delegate;
        final AtomicReference<String> captured = new AtomicReference<>("");
        final AtomicBoolean capturedIsError = new AtomicBoolean(false);

        CaptureTransport(McpTransport delegate) { this.delegate = delegate; }

        @Override
        public void sendResponse(Object id, Object result) {
            try {
                captured.set(JsonRpcMessage.toJson(new JsonRpcMessage.Response(id, result)));
            } catch (JsonProcessingException e) {
                captured.set("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"_serializationError\":\"" + e.getMessage() + "\"}");
            }
            delegate.sendResponse(id, result);
        }

        @Override
        public void sendError(Object id, int code, String message) {
            capturedIsError.set(true);
            try {
                captured.set(JsonRpcMessage.toJson(new JsonRpcMessage.Error(id, code, message)));
            } catch (JsonProcessingException e) {
                captured.set("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":" + code + ",\"message\":\"" + message + "\"}}");
            }
            delegate.sendError(id, code, message);
        }

        @Override
        public void sendNotification(String method, Object params) {
            delegate.sendNotification(method, params);
        }

        @Override
        public String getRemoteAddr() {
            return delegate.getRemoteAddr();
        }
    }
}
