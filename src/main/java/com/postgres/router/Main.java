package com.postgres.router;

import com.postgres.router.config.ConfigManager;
import com.postgres.router.config.RouterConfig;
import com.postgres.router.gui.AdminApp;
import com.postgres.router.http.AdminApiServer;
import com.postgres.router.jdbc.ConnectionPoolManager;
import com.postgres.router.jdbc.QueryExecutor;
import com.postgres.router.mcp.*;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * MCP Postgres Router 主入口。
 *
 * 模式：
 *   java -jar postgres-router.jar           → MCP stdio 模式（向后兼容）
 *   java -jar postgres-router.jar --server  → HTTP 常驻服务（多 agent 共享）
 *   java -jar postgres-router.jar --proxy   → stdio→HTTP 桥接
 *   java -jar postgres-router.jar --gui     → GUI 管理模式
 */
public class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    private static final int ADMIN_API_PORT = 18880;
    private static final String CONFIG_FILE = "config.yml";
    private static final String MCP_SERVER_URL = "http://127.0.0.1:" + ADMIN_API_PORT + "/mcp";

    public static void main(String[] args) throws Exception {
        initLogging();

        if (args.length == 0) {
            // 默认：MCP stdio 模式（向后兼容）
            startMcpMode();
        } else {
            switch (args[0]) {
                case "--server" -> startServerMode();
                case "--proxy"  -> startProxyMode();
                case "--gui"    -> startGuiMode();
                default         -> startMcpMode();
            }
        }
    }

    // ======================== 初始化公共部分 ========================

    /** 创建共享组件：配置、连接池、查询执行器 */
    private static record ServerComponents(
            ConfigManager configManager,
            ConnectionPoolManager poolManager,
            QueryExecutor queryExecutor
    ) {}

    private static ServerComponents initComponents() throws Exception {
        Path configPath = Paths.get(CONFIG_FILE).toAbsolutePath();
        ConfigManager configManager = new ConfigManager(configPath);
        RouterConfig config = configManager.load();

        ConnectionPoolManager poolManager = new ConnectionPoolManager();
        poolManager.syncWithConfig(config);
        configManager.startWatching(poolManager::syncWithConfig);

        QueryExecutor queryExecutor = new QueryExecutor(poolManager);
        return new ServerComponents(configManager, poolManager, queryExecutor);
    }

    // ======================== --server 模式 ========================

    /** HTTP 常驻服务模式：多 agent 通过 /mcp 共享，GUI 通过 /api 管理 */
    private static void startServerMode() throws Exception {
        LOG.info("启动 --server 模式 (HTTP 常驻服务)");

        ServerComponents c = initComponents();
        ServiceLogStore logStore = new ServiceLogStore(1000);
        HttpMcpTransport transport = new HttpMcpTransport();
        McpServer mcpServer = new McpServer(transport, c.queryExecutor, logStore);

        AdminApiServer apiServer = new AdminApiServer(
                ADMIN_API_PORT,
                c.poolManager,
                c.configManager::current,
                c.configManager::save,
                mcpServer
        );
        apiServer.start();

        LOG.info("=== MCP Postgres Router (--server) 启动完成 ===");
        LOG.info("MCP HTTP 端点: " + MCP_SERVER_URL + "  (多 agent 共享)");
        LOG.info("管理 API:     http://127.0.0.1:" + ADMIN_API_PORT + "/api/databases");
        LOG.info("GUI 模式:     java -jar postgres-router.jar --gui");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { apiServer.close(); c.configManager().close(); c.poolManager().close(); }
            catch (Exception ignored) {}
        }));

        Thread.sleep(Long.MAX_VALUE);
    }

    // ======================== --proxy 模式 ========================

    /** stdio→HTTP 桥接模式：读取 stdin，POST 到 /mcp，写回 stdout */
    private static void startProxyMode() throws Exception {
        LOG.info("启动 --proxy 模式 (stdio → HTTP 桥接)");

        HttpClient httpClient = HttpClient.newHttpClient();
        try (BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
             PrintWriter stdout = new PrintWriter(new OutputStreamWriter(System.out, java.nio.charset.StandardCharsets.UTF_8), true)) {

            String line;
            while ((line = stdin.readLine()) != null) {
                if (line.isBlank()) continue;

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(MCP_SERVER_URL))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(line, java.nio.charset.StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.body() != null && !response.body().isBlank()) {
                    stdout.println(response.body());
                }
            }
        }
        LOG.info("stdin 已关闭，代理退出");
    }

    // ======================== 原有模式（向后兼容）====================

    /** GUI 管理模式 */
    private static void startGuiMode() {
        LOG.info("启动 GUI 管理模式");

        if (!isJavaFxAvailable()) {
            System.err.println("Java FX 不可用，请检查运行环境。");
            System.exit(1);
        }

        AdminApp.setApiBaseUrl("http://127.0.0.1:" + ADMIN_API_PORT);
        if (!isMcpServiceRunning()) {
            System.err.println("⚠ MCP 服务未运行，数据库管理功能不可用");
            System.err.println("   请在后台先启动服务：java -jar postgres-router.jar --server");
            System.err.println("   MCP 配置复制功能仍可使用。");
        }

        AdminApp.launchGui(new String[0]);
    }

    /** MCP stdio 模式（向后兼容，单一 agent 独占）*/
    private static void startMcpMode() throws Exception {
        LOG.info("启动 MCP stdio 模式");

        ServerComponents c = initComponents();

        StdioTransport transport = new StdioTransport();
        McpServer mcpServer = new McpServer(transport, c.queryExecutor);
        mcpServer.start();

        AdminApiServer apiServer = new AdminApiServer(
                ADMIN_API_PORT,
                c.poolManager,
                c.configManager::current,
                c.configManager::save
        );
        apiServer.start();

        LOG.info("=== MCP Postgres Router (stdio) 启动完成 ===");
        LOG.info("推荐使用 --server 模式实现多 agent 共享");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { apiServer.close(); transport.close(); c.configManager().close(); c.poolManager().close(); }
            catch (Exception ignored) {}
        }));

        Thread.sleep(Long.MAX_VALUE);
    }

    // ======================== 工具方法 ========================

    private static void initLogging() {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "[%1$tH:%1$tM:%1$tS] %4$-6s %2$s - %5$s%6$s%n");

        try {
            FileHandler fh = new FileHandler("postgres-router_%g.log", 1024 * 1024, 3, true);
            fh.setFormatter(new SimpleFormatter());
            Logger.getLogger("").addHandler(fh);
        } catch (Exception e) {
            System.err.println("日志文件初始化失败: " + e.getMessage());
        }
    }

    private static boolean isJavaFxAvailable() {
        try { Class.forName("javafx.application.Application"); return true; }
        catch (ClassNotFoundException e) { return false; }
    }

    private static boolean isMcpServiceRunning() {
        try {
            java.net.URL url = new java.net.URI(
                    "http://127.0.0.1:" + ADMIN_API_PORT + "/api/databases").toURL();
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            return conn.getResponseCode() == 200;
        } catch (Exception e) { return false; }
    }
}
