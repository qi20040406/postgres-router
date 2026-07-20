package com.postgres.router.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.postgres.router.config.DatabaseConfig;
import com.postgres.router.config.RouterConfig;
import com.postgres.router.jdbc.ConnectionPoolManager;
import com.postgres.router.mcp.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * HTTP 管理 API + MCP 端点服务器。
 *
 * <p>重构要点：
 * <ul>
 *   <li>/mcp 端点：创建 HttpMcpTransport 时传入 remoteAddr，由 McpServer 在 initialize 时直接记录</li>
 *   <li>/api/logs 改为结构化 JSON，支持分页、按 session 过滤、单条详情查看</li>
 *   <li>移除原有的无效 remoteAddr 补充代码</li>
 * </ul>
 */
public class AdminApiServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(AdminApiServer.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final com.sun.net.httpserver.HttpServer server;
    private final ConnectionPoolManager poolManager;
    private final RouterConfigProvider configProvider;
    private final ConfigSaver configSaver;
    private final McpServer mcpServer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "admin-http");
        t.setDaemon(true);
        return t;
    });

    @FunctionalInterface
    public interface RouterConfigProvider { RouterConfig get(); }
    @FunctionalInterface
    public interface ConfigSaver { void save(RouterConfig config) throws IOException; }

    public AdminApiServer(int port, ConnectionPoolManager poolManager,
                          RouterConfigProvider configProvider, ConfigSaver configSaver) throws IOException {
        this(port, poolManager, configProvider, configSaver, null);
    }

    public AdminApiServer(int port, ConnectionPoolManager poolManager,
                          RouterConfigProvider configProvider, ConfigSaver configSaver,
                          McpServer mcpServer) throws IOException {
        this.poolManager = poolManager;
        this.configProvider = configProvider;
        this.configSaver = configSaver;
        this.mcpServer = mcpServer;
        this.server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        registerRoutes();
        server.setExecutor(executor);
    }

    private void registerRoutes() {
        // ---- 数据库管理 API ----
        server.createContext("/api/databases", exchange -> {
            try {
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

                if ("OPTIONS".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }

                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                String restPath = path.substring("/api/databases".length());
                String[] segments = restPath.split("/");

                try {
                    switch (method) {
                        case "GET" -> handleGetDatabases(exchange);
                        case "POST" -> handlePostDatabase(exchange, segments);
                        case "PUT" -> handlePutDatabase(exchange, segments);
                        case "DELETE" -> handleDeleteDatabase(exchange, segments);
                        default -> sendJson(exchange, 405, Map.of("error", "Method not allowed"));
                    }
                } catch (Exception e) {
                    LOG.warning("API 处理异常: " + e.getMessage());
                    sendJson(exchange, 500, Map.of("error", e.getMessage()));
                }
            } catch (Exception e) {
                LOG.severe("HTTP 处理异常: " + e.getMessage());
            }
        });

        // ---- Agent 会话列表 API ----
        server.createContext("/api/sessions", exchange -> {
            try {
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                if ("OPTIONS".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                if (!"GET".equals(exchange.getRequestMethod())) {
                    sendJson(exchange, 405, Map.of("error", "Method not allowed"));
                    return;
                }
                Map<String, McpServer.SessionInfo> sessions = mcpServer != null ? mcpServer.getSessions() : Map.of();
                List<Map<String, Object>> list = new ArrayList<>();
                for (var entry : sessions.entrySet()) {
                    McpServer.SessionInfo s = entry.getValue();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", entry.getKey());
                    item.put("clientName", s.clientName());
                    item.put("clientVersion", s.clientVersion());
                    item.put("remoteAddr", s.remoteAddr());
                    item.put("connectedAt", s.connectedAt().toString());
                    item.put("lastActivity", s.lastActivity().toString());
                    list.add(item);
                }
                sendJson(exchange, 200, Map.of("sessions", list));
            } catch (Exception e) {
                sendJson(exchange, 500, Map.of("error", e.getMessage()));
            }
        });

        // ---- 结构化服务日志 API ----
        // GET /api/logs?limit=100&offset=0&sessionKey=xxx → 列表
        // GET /api/logs/{id} → 单条详情
        server.createContext("/api/logs", exchange -> {
            try {
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                if ("OPTIONS".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                if (!"GET".equals(exchange.getRequestMethod())) {
                    sendJson(exchange, 405, Map.of("error", "Method not allowed"));
                    return;
                }

                String path = exchange.getRequestURI().getPath();
                String query = exchange.getRequestURI().getQuery();

                // 路径匹配 /api/logs/{id}
                if (path.length() > "/api/logs/".length()) {
                    String logId = path.substring("/api/logs/".length());
                    UUID id;
                    try {
                        id = UUID.fromString(logId);
                    } catch (IllegalArgumentException e) {
                        sendJson(exchange, 400, Map.of("error", "Invalid log id format"));
                        return;
                    }
                    Optional<ServiceLogStore.LogEntry> opt = mcpServer != null
                            ? mcpServer.getLogStore().getById(logId)
                            : Optional.<ServiceLogStore.LogEntry>empty();
                    if (opt.isPresent()) {
                        sendJson(exchange, 200, logEntryToDetailMap(opt.get()));
                    } else {
                        sendJson(exchange, 404, Map.of("error", "Log not found: " + logId));
                    }
                    return;
                }

                // /api/logs 列表
                int limit = 100;
                int offset = 0;
                String sessionKey = null;
                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] kv = param.split("=", 2);
                        if (kv.length == 2) {
                            switch (kv[0]) {
                                case "limit"      -> { try { limit = Integer.parseInt(kv[1]); } catch (Exception ignored) {} }
                                case "offset"     -> { try { offset = Integer.parseInt(kv[1]); } catch (Exception ignored) {} }
                                case "sessionKey" -> sessionKey = kv[1];
                            }
                        }
                    }
                }
                limit = Math.min(limit, 500);

                List<ServiceLogStore.LogEntry> entries;
                if (sessionKey != null && !sessionKey.isEmpty()) {
                    entries = mcpServer != null ? mcpServer.getLogStore().getByAgent(sessionKey, limit) : List.of();
                } else {
                    entries = mcpServer != null ? mcpServer.getLogStore().getRecent(limit + offset) : List.of();
                    if (offset > 0) {
                        int end = entries.size();
                        int start = Math.max(0, end - limit);
                        entries = entries.subList(start, end);
                    }
                }

                List<Map<String, Object>> items = new ArrayList<>();
                for (ServiceLogStore.LogEntry e : entries) {
                    items.add(logEntryToListMap(e));
                }
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("logs", items);
                resp.put("count", items.size());
                resp.put("total", mcpServer != null ? mcpServer.getLogStore().size() : 0);
                sendJson(exchange, 200, resp);
            } catch (Exception e) {
                sendJson(exchange, 500, Map.of("error", e.getMessage()));
            }
        });

        // ---- 服务重启 API ----
        server.createContext("/api/service/restart", exchange -> {
            try {
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

                if ("OPTIONS".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendJson(exchange, 405, Map.of("error", "Method not allowed"));
                    return;
                }

                String jarPath = resolveServerJarPath();
                boolean windows = System.getProperty("os.name", "").toLowerCase().contains("windows");
                List<String> cmd = new ArrayList<>();
                if (windows) {
                    cmd.add(System.getProperty("java.home") + "/bin/javaw");
                } else {
                    cmd.add(System.getProperty("java.home") + "/bin/java");
                }
                cmd.add("-Dfile.encoding=UTF-8");
                cmd.add("-jar");
                cmd.add(jarPath);
                cmd.add("--server");

                // 立即返回
                sendJson(exchange, 200, Map.of(
                        "success", true,
                        "message", "服务正在重启，请稍候...",
                        "command", String.join(" ", cmd)
                ));

                // 守护线程：延迟500ms启动新进程，旧进程仍在处理请求
                new Thread(() -> {
                    try {
                        Thread.sleep(500);
                        LOG.info("正在重启服务: " + String.join(" ", cmd));
                        new ProcessBuilder(cmd).start();
                        // 让当前线程池和 HTTP 服务器有时间发送完响应
                        Thread.sleep(1000);
                        LOG.info("旧服务进程退出");
                        System.exit(0);
                    } catch (Exception ex) {
                        LOG.severe("重启服务失败: " + ex.getMessage());
                    }
                }, "service-restarter").start();

            } catch (Exception e) {
                sendJson(exchange, 500, Map.of("error", e.getMessage()));
            }
        });

        // ---- MCP HTTP 端点 ----
        if (mcpServer != null) {
            server.createContext("/mcp", exchange -> {
                try {
                    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
                    exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                    exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
                    exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

                    if ("OPTIONS".equals(exchange.getRequestMethod())) {
                        exchange.sendResponseHeaders(204, -1);
                        return;
                    }
                    if (!"POST".equals(exchange.getRequestMethod())) {
                        sendJson(exchange, 405, Map.of("error", "Only POST allowed"));
                        return;
                    }

                    // 获取客户端 IP 并传递给 HttpMcpTransport
                    String remoteAddr = exchange.getRemoteAddress() != null
                            ? exchange.getRemoteAddress().getAddress().getHostAddress()
                            : "unknown";

                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    JsonRpcMessage.Request req = JsonRpcMessage.parseRequest(body);

                    // 创建带 remoteAddr 的 transport，McpServer 在 handleInitialize 时会记录
                    HttpMcpTransport httpTransport = new HttpMcpTransport(remoteAddr);
                    mcpServer.processRequest(req, httpTransport);

                    // 通知（id == null）不需要等待响应，直接返回 202
                    if (req.id == null) {
                        exchange.sendResponseHeaders(202, -1);
                    } else {
                        String responseJson = httpTransport.waitForResponse();
                        if (responseJson != null) {
                            byte[] respBytes = responseJson.getBytes(StandardCharsets.UTF_8);
                            exchange.sendResponseHeaders(200, respBytes.length);
                            try (OutputStream os = exchange.getResponseBody()) { os.write(respBytes); }
                        } else {
                            exchange.sendResponseHeaders(202, -1);
                        }
                    }
                } catch (Exception e) {
                    LOG.severe("/mcp 处理异常: " + e.getMessage());
                    sendJson(exchange, 500, Map.of("error", e.getMessage()));
                }
            });
        }
    }

    // ======================== 日志序列化辅助 ========================

    private Map<String, Object> logEntryToListMap(ServiceLogStore.LogEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("timestamp", e.timestamp().toString());
        m.put("sessionKey", e.sessionKey());
        m.put("agentName", e.agentName());
        m.put("method", e.method());
        m.put("requestParams", ServiceLogStore.LogEntry.trimParamsPreview(e.requestParams()));
        m.put("responsePreview", e.responsePreview());
        m.put("isError", e.isError());
        m.put("durationMs", e.durationMs());
        return m;
    }

    private Map<String, Object> logEntryToDetailMap(ServiceLogStore.LogEntry e) {
        Map<String, Object> m = logEntryToListMap(e);
        // 详情接口返回完整内容
        m.put("requestParams", e.requestParams());
        m.put("responseContent", e.responseContent());
        return m;
    }

    // ======================== 数据库管理 API ========================

    private void handleGetDatabases(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        RouterConfig config = configProvider.get();
        String activeName = poolManager.getActiveName();
        List<Map<String, Object>> dbList = new ArrayList<>();
        if (config.getDatabases() != null) {
            for (DatabaseConfig db : config.getDatabases()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", db.getName());
                entry.put("host", db.getHost());
                entry.put("port", db.getPort());
                entry.put("database", db.getDatabase());
                entry.put("username", db.getUsername());
                entry.put("ssl", db.isSsl());
                entry.put("description", db.getDescription());
                entry.put("active", db.getName().equals(activeName));
                dbList.add(entry);
            }
        }
        sendJson(exchange, 200, Map.of("databases", dbList, "active", activeName));
    }

    private void handlePostDatabase(com.sun.net.httpserver.HttpExchange exchange, String[] segments) throws IOException {
        if (segments.length == 1 || segments[1].isEmpty()) {
            DatabaseConfig db = MAPPER.readValue(exchange.getRequestBody(), DatabaseConfig.class);
            if (db.getName() == null || db.getName().isBlank()) {
                sendJson(exchange, 400, Map.of("error", "name is required"));
                return;
            }
            RouterConfig config = configProvider.get();
            config.getDatabases().removeIf(d -> d.getName().equals(db.getName()));
            config.getDatabases().add(db);
            configSaver.save(config);
            poolManager.syncWithConfig(config);
            sendJson(exchange, 200, Map.of("success", true, "name", db.getName()));
            return;
        }
        String name = segments[1];
        String action = segments.length > 2 ? segments[2] : "";
        if ("switch".equals(action)) {
            poolManager.switchActive(name);
            RouterConfig config = configProvider.get();
            config.setActive(name);
            configSaver.save(config);
            sendJson(exchange, 200, Map.of("success", true, "active", name));
        } else if ("test".equals(action)) {
            DatabaseConfig db = configProvider.get().findByName(name);
            if (db == null) { try { db = MAPPER.readValue(exchange.getRequestBody(), DatabaseConfig.class); } catch (Exception ignored) {} }
            ConnectionPoolManager.TestResult result;
            if (db == null) {
                result = new ConnectionPoolManager.TestResult(false, "数据库配置「" + name + "」不存在", null);
            } else {
                result = poolManager.testConnectionWithDetails(db);
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", result.success());
            resp.put("name", name);
            resp.put("message", result.message());
            if (result.detail() != null) resp.put("detail", result.detail());
            sendJson(exchange, 200, resp);
        } else { sendJson(exchange, 404, Map.of("error", "Unknown action: " + action)); }
    }

    private void handlePutDatabase(com.sun.net.httpserver.HttpExchange exchange, String[] segments) throws IOException {
        if (segments.length < 2 || segments[1].isEmpty()) { sendJson(exchange, 400, Map.of("error", "name is required")); return; }
        String oldName = segments[1];
        DatabaseConfig updated = MAPPER.readValue(exchange.getRequestBody(), DatabaseConfig.class);
        RouterConfig config = configProvider.get();
        // 1a 修复：不覆盖 body 中的新名字。用旧名做 removeIf，保留 body 中的新名字用于保存
        config.getDatabases().removeIf(d -> d.getName().equals(oldName));
        // 1b 修复：新密码为空时保留原密码（前端不回显密码，用户未重输则 body 中 password="""")
        if (updated.getPassword() == null || updated.getPassword().isBlank()) {
            DatabaseConfig existing = config.findByName(oldName);
            if (existing != null) updated.setPassword(existing.getPassword());
        }
        config.getDatabases().add(updated);
        configSaver.save(config);
        poolManager.syncWithConfig(config);
        sendJson(exchange, 200, Map.of("success", true, "name", updated.getName()));
    }

    private void handleDeleteDatabase(com.sun.net.httpserver.HttpExchange exchange, String[] segments) throws IOException {
        if (segments.length < 2 || segments[1].isEmpty()) { sendJson(exchange, 400, Map.of("error", "name is required")); return; }
        String name = segments[1];
        RouterConfig config = configProvider.get();
        config.getDatabases().removeIf(d -> d.getName().equals(name));
        if (name.equals(config.getActive())) config.setActive(null);
        configSaver.save(config);
        poolManager.removePool(name);
        sendJson(exchange, 200, Map.of("success", true, "name", name));
    }

    private void sendJson(com.sun.net.httpserver.HttpExchange exchange, int status, Object data) throws IOException {
        byte[] json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(data);
        exchange.sendResponseHeaders(status, json.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(json); }
    }

    public void start() {
        server.start();
        LOG.info("HTTP 管理 API: http://127.0.0.1:18880/api/databases");
        if (mcpServer != null) {
            LOG.info("MCP 端点:     http://127.0.0.1:18880/mcp");
            LOG.info("Agent 会话:   http://127.0.0.1:18880/api/sessions");
            LOG.info("服务日志:     http://127.0.0.1:18880/api/logs");
        }
    }

    /**
     * 解析当前运行的 JAR 文件路径。
     * 支持 fat JAR 和 exploded classes 目录两种情况。
     */
    static String resolveServerJarPath() {
        try {
            URI uri = AdminApiServer.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Paths.get(uri).toAbsolutePath().normalize();
            if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
                return path.toString();
            }
            // exploded classes 目录，尝试推导 target/postgres-router.jar
            Path targetJar = path.resolve("postgres-router.jar");
            if (Files.isRegularFile(targetJar)) {
                return targetJar.toString();
            }
            // 向上查找 target/postgres-router.jar
            Path parent = path;
            while (parent != null) {
                targetJar = parent.resolve("target").resolve("postgres-router.jar");
                if (Files.isRegularFile(targetJar)) {
                    return targetJar.toString();
                }
                parent = parent.getParent();
            }
            // 兜底：返回当前工作目录下的 postgres-router.jar
            return Paths.get("target", "postgres-router.jar").toAbsolutePath().toString();
        } catch (Exception e) {
            return Paths.get("target", "postgres-router.jar").toAbsolutePath().toString();
        }
    }

    @Override
    public void close() { server.stop(1); executor.shutdownNow(); }
}
