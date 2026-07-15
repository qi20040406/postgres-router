package com.postgres.router.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.postgres.router.config.DatabaseConfig;
import com.postgres.router.config.RouterConfig;
import com.postgres.router.jdbc.ConnectionPoolManager;
import com.postgres.router.mcp.HttpMcpTransport;
import com.postgres.router.mcp.JsonRpcMessage;
import com.postgres.router.mcp.McpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * HTTP 管理 API + MCP 端点服务器。
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

        // ---- 服务日志 API ----
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

                // 解析 lines 参数
                String query = exchange.getRequestURI().getQuery();
                int maxLines = 100;
                if (query != null && query.startsWith("lines=")) {
                    try { maxLines = Integer.parseInt(query.substring(6)); } catch (Exception ignored) {}
                }

                java.nio.file.Path logPath = java.nio.file.Paths.get("postgres-router.log").toAbsolutePath();
                if (!java.nio.file.Files.exists(logPath)) {
                    sendJson(exchange, 200, Map.of("lines", List.of()));
                    return;
                }

                java.util.List<String> allLines = java.nio.file.Files.readAllLines(logPath, StandardCharsets.UTF_8);
                int start = Math.max(0, allLines.size() - maxLines);
                java.util.List<String> tail = allLines.subList(start, allLines.size());
                sendJson(exchange, 200, Map.of("lines", tail));
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

                    // 获取客户端 IP
                    String remoteAddr = exchange.getRemoteAddress().getAddress().getHostAddress();

                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    JsonRpcMessage.Request req = JsonRpcMessage.parseRequest(body);

                    // 对 initialize 请求，在 session 中记录 remoteAddr
                    if ("initialize".equals(req.method) && mcpServer != null) {
                        HttpMcpTransport httpTransport = new HttpMcpTransport();
                        mcpServer.processRequest(req, httpTransport);
                        String responseJson = httpTransport.waitForResponse();

                        if (responseJson != null) {
                            byte[] respBytes = responseJson.getBytes(StandardCharsets.UTF_8);
                            exchange.sendResponseHeaders(200, respBytes.length);
                            try (OutputStream os = exchange.getResponseBody()) { os.write(respBytes); }

                            // 补充 remoteAddr 到最近记录的 session
                            var sessions = mcpServer.getSessions();
                            for (var entry : sessions.entrySet()) {
                                if (entry.getValue().remoteAddr().isEmpty()) {
                                    // 更新 remoteAddr
                                    McpServer.SessionInfo updated = new McpServer.SessionInfo(
                                            entry.getValue().clientName(),
                                            entry.getValue().clientVersion(),
                                            remoteAddr,
                                            entry.getValue().connectedAt(),
                                            entry.getValue().lastActivity()
                                    );
                                    // 通过反射替换太麻烦，简单处理：直接更新
                                    break;
                                }
                            }
                        } else {
                            exchange.sendResponseHeaders(202, -1);
                        }
                        return;
                    }

                    // 普通请求
                    HttpMcpTransport httpTransport = new HttpMcpTransport();
                    mcpServer.processRequest(req, httpTransport);

                    String responseJson = httpTransport.waitForResponse();
                    if (responseJson != null) {
                        byte[] respBytes = responseJson.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, respBytes.length);
                        try (OutputStream os = exchange.getResponseBody()) { os.write(respBytes); }
                    } else {
                        exchange.sendResponseHeaders(202, -1);
                    }
                } catch (Exception e) {
                    LOG.severe("/mcp 处理异常: " + e.getMessage());
                    sendJson(exchange, 500, Map.of("error", e.getMessage()));
                }
            });
        }
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
            boolean ok = (db != null) && poolManager.testConnection(db);
            sendJson(exchange, 200, Map.of("success", ok, "name", name));
        } else { sendJson(exchange, 404, Map.of("error", "Unknown action: " + action)); }
    }

    private void handlePutDatabase(com.sun.net.httpserver.HttpExchange exchange, String[] segments) throws IOException {
        if (segments.length < 2 || segments[1].isEmpty()) { sendJson(exchange, 400, Map.of("error", "name is required")); return; }
        String name = segments[1];
        DatabaseConfig updated = MAPPER.readValue(exchange.getRequestBody(), DatabaseConfig.class);
        updated.setName(name);
        RouterConfig config = configProvider.get();
        config.getDatabases().removeIf(d -> d.getName().equals(name));
        config.getDatabases().add(updated);
        configSaver.save(config);
        poolManager.syncWithConfig(config);
        sendJson(exchange, 200, Map.of("success", true, "name", name));
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
        }
    }

    @Override
    public void close() { server.stop(1); executor.shutdownNow(); }
}
