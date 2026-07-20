package com.postgres.router.gui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.application.Platform;
import javafx.collections.*;
import javafx.scene.paint.Color;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 数据库列表主控制器。
 * TabPane 双标签布局：数据库管理 + MCP 配置复制。
 */
public class DatabaseListController {

    private static final Logger LOG = Logger.getLogger(DatabaseListController.class.getName());

    // ---- 颜色常量 ----
    private static final String BRAND_BG     = "#2c3e50";
    private static final String BRAND_FG     = "#ecf0f1";
    private static final String GREEN        = "#27ae60";
    private static final String GREEN_HOVER  = "#219a52";
    private static final String RED          = "#e74c3c";
    private static final String BG_LIGHT     = "#f0f2f5";
    private static final String CARD_BORDER  = "#dcdde1";
    private static final String CODE_BG      = "#1e1e1e";
    private static final String CODE_FG      = "#9cdcfe";
    private static final String ORANGE       = "#e67e22";
    private static final String ORANGE_HOVER = "#d35400";

    private final String apiBaseUrl;
    private final BorderPane root;
    private final VBox databaseList;
    private final Label statusLabel;
    private final Label statusDot;
    private final Button restartBtn;
    private javafx.stage.Stage stageField;

    @SuppressWarnings("unchecked")
    public DatabaseListController(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;

        root = new BorderPane();
        root.setStyle("-fx-background-color: " + BG_LIGHT + ";");

        // ==================== TOP: 品牌栏 ====================
        HBox brandBar = new HBox(10);
        brandBar.setPadding(new Insets(12, 16, 12, 16));
        brandBar.setStyle("-fx-background-color: " + BRAND_BG + ";");

        Label appName = new Label("MCP Postgres Router");
        appName.setStyle("-fx-text-fill: " + BRAND_FG + "; -fx-font-size: 16px; -fx-font-weight: bold;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        statusDot = new Label("●");
        statusDot.setStyle("-fx-text-fill: #95a5a6; -fx-font-size: 10px;");
        Label statusHint = new Label("MCP");
        statusHint.setStyle("-fx-text-fill: " + BRAND_FG + "; -fx-font-size: 11px;");

        brandBar.getChildren().addAll(appName, spacer, statusDot, statusHint);

        // 重启服务按钮
        restartBtn = createStyledButton("🔄 重启服务", ORANGE, ORANGE_HOVER);
        restartBtn.setTooltip(new Tooltip("重启后台 MCP 服务"));
        restartBtn.setOnAction(e -> restartService());
        brandBar.getChildren().add(restartBtn);

        // 最小化到托盘按钮
        javafx.scene.control.Button minimizeBtn = new javafx.scene.control.Button("最小化到托盘");
        minimizeBtn.setStyle(
                "-fx-background-color: #1565C0;" +
                " -fx-text-fill: white;" +
                " -fx-font-size: 12px;" +
                " -fx-padding: 4 10 4 10;" +
                " -fx-background-radius: 4;" +
                " -fx-cursor: hand;"
        );
        minimizeBtn.setTooltip(new Tooltip("最小化到托盘"));
        minimizeBtn.setOnAction(e -> {
            if (stageField != null) {
                SystemTrayHelper.minimizeToTray(stageField);
            }
        });

        brandBar.getChildren().add(minimizeBtn);
        root.setTop(brandBar);

        // ==================== CENTER: TabPane ====================
        TabPane tabPane = new TabPane();
        tabPane.setStyle("-fx-background-color: transparent; -fx-tab-min-height: 32;");

        // ---- Tab 1: 数据库管理 ----
        Tab dbTab = new Tab("🗄  数据库管理");
        dbTab.setClosable(false);

        BorderPane dbContent = new BorderPane();
        dbContent.setStyle("-fx-background-color: " + BG_LIGHT + ";");
        dbContent.setPadding(new Insets(12, 16, 12, 16));

        // 操作栏
        HBox actionBar = new HBox(8);
        actionBar.setPadding(new Insets(0, 0, 10, 0));

        Button refreshBtn = createStyledButton("🔄 刷新", GREEN, GREEN_HOVER);
        refreshBtn.setOnAction(e -> refresh());

        Region actionSpacer = new Region();
        HBox.setHgrow(actionSpacer, Priority.ALWAYS);

        Button addBtn = createStyledButton("➕ 添加数据库", GREEN, GREEN_HOVER);
        addBtn.setOnAction(e -> showAddDialog());

        actionBar.getChildren().addAll(refreshBtn, actionSpacer, addBtn);
        dbContent.setTop(actionBar);

        // 数据库卡片列表
        databaseList = new VBox(8);
        ScrollPane scroll = new ScrollPane(databaseList);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-border-color: " + CARD_BORDER + "; -fx-border-radius: 4;");
        dbContent.setCenter(scroll);

        dbTab.setContent(dbContent);

        // ---- Tab 2: MCP 配置 ----
        Tab configTab = new Tab("📋  MCP 配置");
        configTab.setClosable(false);

        VBox configContent = new VBox(10);
        configContent.setPadding(new Insets(16));
        configContent.setStyle("-fx-background-color: " + BG_LIGHT + ";");

        Label configTitle = new Label("MCP 配置文件内容");
        configTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;");

        Label configDesc = new Label("将此配置添加到您的 MCP 客户端即可连接到此路由器（一次写入，永久使用）：");
        configDesc.setStyle("-fx-font-size: 11px; -fx-text-fill: #7f8c8d;");
        configDesc.setWrapText(true);

        // ===== URL 配置 =====
        String jsonUrl = "{\n" +
                "  \"mcpServers\": {\n" +
                "    \"postgres-router\": {\n" +
                "      \"url\": \"http://127.0.0.1:18880/mcp\"\n" +
                "    }\n" +
                "  }\n" +
                "}";
        String urlOnly = "http://127.0.0.1:18880/mcp";

        // ---- 完整 JSON 格式（推荐）----
        Label fmtJsonLabel = new Label("📦 完整 JSON 格式（推荐，直接粘贴到 MCP 配置文件）");
        fmtJsonLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + GREEN + ";");

        TextArea areaJson = buildCodeArea(jsonUrl, 4);
        HBox rowJson = new HBox(8);
        rowJson.setAlignment(Pos.CENTER_LEFT);
        rowJson.getChildren().add(makeCopyButton(jsonUrl));
        Label hintJson = new Label("HTTP URL 方式，多 agent 共享同一服务，无需启动额外进程");
        hintJson.setStyle("-fx-text-fill: #95a5a6; -fx-font-size: 11px;");
        rowJson.getChildren().add(hintJson);

        // ---- 单独 URL 文本（适用于手动指定端点的场景）----
        Label fmtUrlLabel = new Label("🔗 单独 URL 地址");
        fmtUrlLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + BRAND_BG + ";");

        TextField urlField = new TextField(urlOnly);
        urlField.setEditable(false);
        urlField.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px; -fx-text-fill: " + CODE_FG + "; -fx-control-inner-background: " + CODE_BG + "; -fx-border-radius: 4; -fx-background-radius: 4;");
        HBox.setHgrow(urlField, Priority.ALWAYS);

        HBox rowUrl = new HBox(8);
        rowUrl.setAlignment(Pos.CENTER_LEFT);
        rowUrl.getChildren().add(urlField);
        rowUrl.getChildren().add(makeCopyButton(urlOnly));

        configContent.getChildren().addAll(
                configTitle, configDesc,
                fmtJsonLabel, areaJson, rowJson,
                new Separator(),
                fmtUrlLabel, rowUrl
        );
        configTab.setContent(configContent);

        tabPane.getTabs().addAll(dbTab, configTab);

        // ---- Tab 3: 已连接 Agent ----
        Tab sessionTab = new Tab("🔗  已连接 Agent");
        sessionTab.setClosable(false);

        VBox sessionContent = new VBox(10);
        sessionContent.setPadding(new Insets(16));
        sessionContent.setStyle("-fx-background-color: " + BG_LIGHT + ";");

        Label sessionTitle = new Label("活跃 Agent 连接");
        sessionTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;");

        TableView<Map<String, String>> sessionTable = new TableView<>();
        sessionTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        sessionTable.setStyle("-fx-background-color: white; -fx-border-color: " + CARD_BORDER + "; -fx-border-radius: 4;");

        TableColumn<Map<String, String>, String> nameCol = new TableColumn<>("Agent 名称");
        nameCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().get("clientName")));
        nameCol.setPrefWidth(160);

        TableColumn<Map<String, String>, String> versionCol = new TableColumn<>("版本");
        versionCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().get("clientVersion")));
        versionCol.setPrefWidth(80);

        TableColumn<Map<String, String>, String> addrCol = new TableColumn<>("来源地址");
        addrCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().get("remoteAddr")));
        addrCol.setPrefWidth(120);

        TableColumn<Map<String, String>, String> connectedCol = new TableColumn<>("连接时间");
        connectedCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().get("connectedAt")));
        connectedCol.setPrefWidth(180);

        TableColumn<Map<String, String>, String> activityCol = new TableColumn<>("最后活跃");
        activityCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().get("lastActivity")));
        activityCol.setPrefWidth(180);

        sessionTable.getColumns().addAll(nameCol, versionCol, addrCol, connectedCol, activityCol);

        ObservableList<Map<String, String>> sessionData = FXCollections.observableArrayList();
        sessionTable.setItems(sessionData);

        Label sessionStatus = new Label("每 3 秒自动刷新");
        sessionStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: #95a5a6;");

        sessionContent.getChildren().addAll(sessionTitle, sessionTable, sessionStatus);
        sessionTab.setContent(sessionContent);

        tabPane.getTabs().add(sessionTab);

        // ---- Tab 4: 请求日志（结构化）----
        Tab logTab = new Tab("📟  请求日志");
        logTab.setClosable(false);

        VBox logContent = new VBox(10);
        logContent.setPadding(new Insets(16));
        logContent.setStyle("-fx-background-color: " + BG_LIGHT + ";");

        Label logTitle = new Label("MCP 服务请求日志（点击行查看详情）");
        logTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;");

        TableView<Map<String, String>> logTable = new TableView<>();
        logTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        logTable.setStyle("-fx-background-color: white; -fx-border-color: " + CARD_BORDER + "; -fx-border-radius: 4;");

        TableColumn<Map<String, String>, String> timeCol = new TableColumn<>("时间");
        timeCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().get("timestamp")));
        timeCol.setPrefWidth(180);

        TableColumn<Map<String, String>, String> agentCol = new TableColumn<>("Agent");
        agentCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().get("agentName")));
        agentCol.setPrefWidth(120);

        TableColumn<Map<String, String>, String> methodCol = new TableColumn<>("方法");
        methodCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().get("method")));
        methodCol.setPrefWidth(100);

        TableColumn<Map<String, String>, String> sqlCol = new TableColumn<>("请求/响应 预览");
        sqlCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().get("responsePreview")));
        sqlCol.setPrefWidth(300);

        TableColumn<Map<String, String>, String> statusCol = new TableColumn<>("状态");
        statusCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().get("isError")));
        statusCol.setPrefWidth(60);

        TableColumn<Map<String, String>, String> durCol = new TableColumn<>("耗时(ms)");
        durCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().get("durationMs")));
        durCol.setPrefWidth(80);

        logTable.getColumns().addAll(timeCol, agentCol, methodCol, sqlCol, statusCol, durCol);

        // 点击行查看详情
        logTable.setRowFactory(tv -> {
            TableRow<Map<String, String>> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    showLogDetail(row.getItem());
                }
            });
            return row;
        });

        ObservableList<Map<String, String>> logData = FXCollections.observableArrayList();
        logTable.setItems(logData);

        Label logStatus = new Label("每 3 秒自动刷新（双击查看详情）");
        logStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: #95a5a6;");

        logContent.getChildren().addAll(logTitle, logTable, logStatus);
        logTab.setContent(logContent);

        tabPane.getTabs().add(logTab);

        // 启动日志自动刷新
        startLogRefresh(logData, logStatus);
        // 启动 session 自动刷新
        startSessionRefresh(sessionData, sessionStatus);
        root.setCenter(tabPane);

        // ==================== BOTTOM: 状态栏 ====================
        HBox statusBar = new HBox(8);
        statusBar.setPadding(new Insets(6, 16, 6, 16));
        statusBar.setStyle("-fx-background-color: #dfe6e9; -fx-border-color: " + CARD_BORDER + " -fx-border-width: 1 0 0 0;");
        statusBar.setAlignment(Pos.CENTER_LEFT);

        statusLabel = new Label("就绪");
        statusLabel.setStyle("-fx-text-fill: #636e72; -fx-font-size: 11px;");

        Region statusSpacer = new Region();
        HBox.setHgrow(statusSpacer, Priority.ALWAYS);

        Label versionLabel = new Label("v1.0.0");
        versionLabel.setStyle("-fx-text-fill: #b2bec3; -fx-font-size: 10px;");

        statusBar.getChildren().addAll(statusLabel, statusSpacer, versionLabel);
        root.setBottom(statusBar);
    }

    // ======================== 重启服务 ========================

    /** 重启服务（ POST /api/service/restart，等待新进程恢复后自动刷新）*/
    private void restartService() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "确定要重启后台 MCP 服务吗？\n所有 Agent 连接将暂时断开。",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("重启服务");
        confirm.setHeaderText("重启确认");
        confirm.showAndWait().ifPresent(result -> {
            if (result != ButtonType.OK) return;
            restartBtn.setDisable(true);
            restartBtn.setText("⏳ 重启中...");
            statusLabel.setText("正在重启 MCP 服务...");
            statusDot.setStyle("-fx-text-fill: " + ORANGE + "; -fx-font-size: 10px;");

            new Thread(() -> {
                try {
                    // POST /api/service/restart
                    URL url = new URI(apiBaseUrl + "/api/service/restart").toURL();
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    try { conn.getResponseCode(); } catch (Exception ignored) {}

                    // 等待新进程启动（最多30秒）
                    boolean recovered = false;
                    for (int i = 0; i < 30; i++) {
                        Thread.sleep(1000);
                        try {
                            URL healthUrl = new URI(apiBaseUrl + "/api/databases").toURL();
                            HttpURLConnection hc = (HttpURLConnection) healthUrl.openConnection();
                            hc.setRequestMethod("GET");
                            hc.setConnectTimeout(2000);
                            if (hc.getResponseCode() == 200) {
                                recovered = true;
                                break;
                            }
                        } catch (java.net.ConnectException e) {
                            // 服务还在启动，继续等待
                        } catch (Exception ignored) {}
                    }

                    final boolean ok = recovered;
                    Platform.runLater(() -> {
                        restartBtn.setDisable(false);
                        restartBtn.setText("🔄 重启服务");
                        if (ok) {
                            statusDot.setStyle("-fx-text-fill: " + GREEN + "; -fx-font-size: 10px");
                            statusLabel.setText("✅ MCP 服务已重启");
                            refresh();
                        } else {
                            statusDot.setStyle("-fx-text-fill: " + ORANGE + "; -fx-font-size: 10px");
                            statusLabel.setText("⚠ 重启超时，请手动检查");
                        }
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        restartBtn.setDisable(false);
                        restartBtn.setText("🔄 重启服务");
                        statusDot.setStyle("-fx-text-fill: " + RED + "; -fx-font-size: 10px");
                        statusLabel.setText("⚠ 重启失败: " + e.getMessage());
                    });
                }
            }, "service-restart-watcher").start();
        });
    }

    // ======================== 工具方法 ========================

    private Button createStyledButton(String text, String bg, String hoverBg) {
        Button btn = new Button(text);
        btn.setStyle(
                "-fx-background-color: " + bg + ";" +
                " -fx-text-fill: white;" +
                " -fx-font-size: 12px;" +
                " -fx-font-weight: bold;" +
                " -fx-padding: 6 14 6 14;" +
                " -fx-background-radius: 4;" +
                " -fx-border-radius: 4;" +
                " -fx-cursor: hand;"
        );
        btn.setOnMouseEntered(e ->
                btn.setStyle(btn.getStyle().replace(bg, hoverBg)));
        btn.setOnMouseExited(e ->
                btn.setStyle(btn.getStyle().replace(hoverBg, bg)));
        return btn;
    }

    /** 创建暗色代码风格 TextArea */
    private TextArea buildCodeArea(String text, int rows) {
        TextArea ta = new TextArea(text);
        ta.setEditable(false);
        ta.setPrefRowCount(rows);
        ta.setWrapText(false);
        ta.setStyle(
                "-fx-font-family: 'Consolas', monospace;" +
                " -fx-font-size: 12px;" +
                " -fx-text-fill: " + CODE_FG + ";" +
                " -fx-control-inner-background: " + CODE_BG + ";" +
                " -fx-border-color: " + CARD_BORDER + ";" +
                " -fx-border-radius: 4;" +
                " -fx-background-radius: 4;"
        );
        return ta;
    }

    /** 创建一键复制按钮 */
    private Button makeCopyButton(String content) {
        Button btn = createStyledButton("📋 复制此配置", GREEN, GREEN_HOVER);
        btn.setOnAction(e -> {
            ClipboardContent clipContent = new ClipboardContent();
            clipContent.putString(content);
            Clipboard.getSystemClipboard().setContent(clipContent);
            btn.setText("✅ 已复制!");
            new Thread(() -> {
                try { Thread.sleep(1500); } catch (InterruptedException ex) {}
                Platform.runLater(() -> btn.setText("📋 复制此配置"));
            }).start();
        });
        return btn;
    }

    public BorderPane getRoot() {
        return root;
    }

    /** 设置所属 Stage（供最小化到托盘使用）*/
    public void setStage(javafx.stage.Stage stage) {
        this.stageField = stage;
    }

    /** 标记 MCP 连接状态 */
    private void setMcpStatus(boolean connected) {
        if (connected) {
            statusDot.setStyle("-fx-text-fill: " + GREEN + "; -fx-font-size: 10px;");
        } else {
            statusDot.setStyle("-fx-text-fill: " + RED + "; -fx-font-size: 10px;");
        }
    }

    /** 启动 session 列表自动刷新（每 3 秒）*/
    private void startSessionRefresh(ObservableList<Map<String, String>> data, Label status) {
        Thread refresher = new Thread(() -> {
            while (true) {
                try {
                    URL url = new URI(apiBaseUrl + "/api/sessions").toURL();
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(3000);
                    if (conn.getResponseCode() == 200) {
                        String json = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        Map<String, Object> result = mapper.readValue(json, Map.class);
                        List<Map<String, Object>> sessions = (List<Map<String, Object>>) result.get("sessions");
                        if (sessions != null) {
                            List<Map<String, String>> rows = sessions.stream().map(s -> {
                                Map<String, String> row = new java.util.LinkedHashMap<>();
                                row.put("clientName", str(s.get("clientName")));
                                row.put("clientVersion", str(s.get("clientVersion")));
                                row.put("remoteAddr", str(s.get("remoteAddr")));
                                row.put("connectedAt", str(s.get("connectedAt")));
                                row.put("lastActivity", str(s.get("lastActivity")));
                                return row;
                            }).toList();
                            Platform.runLater(() -> {
                                data.setAll(rows);
                                status.setText(sessions.size() + " 个活跃连接 · 每 3 秒自动刷新");
                            });
                        }
                    }
                } catch (Exception e) {
                    // 服务未就绪时静默跳过
                }
                try { Thread.sleep(3000); } catch (InterruptedException e) { break; }
            }
        }, "session-refresher");
        refresher.setDaemon(true);
        refresher.start();
    }

    private String str(Object o) { return o != null ? o.toString() : ""; }

    /** 服务日志自动刷新（每 3 秒）*/
    @SuppressWarnings("unchecked")
    private void startLogRefresh(ObservableList<Map<String, String>> data, Label status) {
        Thread refresher = new Thread(() -> {
            while (true) {
                try {
                    URL url = new URI(apiBaseUrl + "/api/logs?limit=200").toURL();
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(3000);
                    if (conn.getResponseCode() == 200) {
                        String json = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        Map<String, Object> result = mapper.readValue(json, Map.class);
                        List<Map<String, Object>> logs = (List<Map<String, Object>>) result.get("logs");
                        if (logs != null) {
                            List<Map<String, String>> rows = logs.stream().map(m -> {
                                Map<String, String> row = new java.util.LinkedHashMap<>();
                                row.put("id", str(m.get("id")));
                                row.put("timestamp", str(m.get("timestamp")));
                                row.put("agentName", str(m.get("agentName")));
                                row.put("method", str(m.get("method")));
                                row.put("responsePreview", str(m.get("responsePreview")));
                                row.put("isError", Boolean.TRUE.equals(m.get("isError")) ? "❌" : "✅");
                                row.put("durationMs", String.valueOf(m.get("durationMs")));
                                return row;
                            }).toList();
                            Platform.runLater(() -> {
                                data.setAll(rows);
                                status.setText(rows.size() + " 条请求 · 每 3 秒自动刷新（双击查看详情）");
                            });
                        }
                    }
                } catch (Exception e) {
                    // skip when server not ready
                }
                try { Thread.sleep(3000); } catch (InterruptedException e) { break; }
            }
        }, "log-refresher");
        refresher.setDaemon(true);
        refresher.start();
    }

    /** 双击日志行：弹窗查看详情（回查服务器获取完整内容）*/
    @SuppressWarnings("unchecked")
    private void showLogDetail(Map<String, String> row) {
        String logId = row.get("id");
        try {
            URL url = new URI(apiBaseUrl + "/api/logs/" + logId).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            if (conn.getResponseCode() == 200) {
                String json = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> detail = mapper.readValue(json, Map.class);
                String params = str(detail.get("requestParams"));
                String response = str(detail.get("responseContent"));

                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("日志详情");
                alert.setHeaderText("请求 " + logId + " · " + detail.get("method"));

                TextArea area = new TextArea(params + "\n\n--- 响应 ---\n" + response);
                area.setEditable(false);
                area.setPrefRowCount(18);
                area.setPrefColumnCount(80);
                area.setWrapText(false);
                area.setStyle("-fx-font-family: 'Courier New', monospace; -fx-font-size: 11px;");

                alert.getDialogPane().setExpandableContent(new javafx.scene.layout.VBox(area));
                alert.getDialogPane().setExpanded(true);
                alert.setResizable(true);
                alert.showAndWait();
            }
        } catch (Exception e) {
            Alert err = new Alert(Alert.AlertType.ERROR, e.getMessage());
            err.showAndWait();
        }
    }

    // ======================== 业务逻辑(保持不变) ========================

    /** 从服务器刷新数据库列表 */
    @SuppressWarnings("unchecked")
    public void refresh() {
        statusLabel.setText("正在加载...");
        databaseList.getChildren().clear();

        try {
            URL url = new URI(apiBaseUrl + "/api/databases").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);

            if (conn.getResponseCode() != 200) {
                statusLabel.setText("⚠ 连接失败: HTTP " + conn.getResponseCode() + " — 请确保 MCP 服务正在运行");
                setMcpStatus(false);
                return;
            }

            setMcpStatus(true);
            String json = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> data = mapper.readValue(json, Map.class);
            List<Map<String, Object>> databases = (List<Map<String, Object>>) data.get("databases");
            String activeName = (String) data.get("active");

            if (databases == null || databases.isEmpty()) {
                databaseList.getChildren().add(new Label("暂无数据库配置，请点击「添加数据库」"));
                statusLabel.setText("0 个数据库配置");
                return;
            }

            ToggleGroup group = new ToggleGroup();
            for (Map<String, Object> db : databases) {
                VBox card = createDatabaseCard(db, activeName, group);
                databaseList.getChildren().add(card);
            }

            statusLabel.setText(databases.size() + " 个数据库配置 · 当前活跃: " + (activeName != null ? activeName : "未设置"));
        } catch (java.net.ConnectException e) {
            statusLabel.setText("⚠ 无法连接到 MCP 服务 (127.0.0.1:18880)，请确保服务正在运行");
            setMcpStatus(false);
        } catch (Exception e) {
            statusLabel.setText("⚠ 加载失败: " + e.getMessage());
            setMcpStatus(false);
            LOG.warning("刷新数据库列表失败: " + e.getMessage());
        }
    }

    /** 创建单个数据库配置卡片 */
    @SuppressWarnings("unchecked")
    private VBox createDatabaseCard(Map<String, Object> db, String activeName, ToggleGroup group) {
        String name = (String) db.get("name");
        String host = (String) db.get("host");
        int port = (int) db.get("port");
        String database = (String) db.get("database");
        String description = (String) db.get("description");
        boolean isActive = Boolean.TRUE.equals(db.get("active"));

        VBox card = new VBox(6);
        card.setPadding(new Insets(10, 12, 10, 12));
        String leftBorder = isActive ? "  -fx-border-color: " + GREEN + " " + CARD_BORDER + " " + CARD_BORDER + " " + CARD_BORDER + ";" : "  -fx-border-color: " + CARD_BORDER + ";";
        card.setStyle(
                "-fx-background-color: white;" +
                " -fx-border-radius: 4;" +
                " -fx-background-radius: 4;" +
                leftBorder +
                " -fx-border-width: 0 0 0 " + (isActive ? "4" : "1") + ";"
        );

        HBox topRow = new HBox(10);
        topRow.setAlignment(Pos.CENTER_LEFT);

        RadioButton radio = new RadioButton();
        radio.setToggleGroup(group);
        radio.setSelected(isActive);
        radio.setStyle("-fx-cursor: hand;");
        radio.setOnAction(e -> switchDatabase(name));

        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #2c3e50;");

        Label statusBadge = new Label(isActive ? "已连接" : "");
        if (isActive) {
            statusBadge.setStyle("-fx-text-fill: white; -fx-font-size: 10px; -fx-font-weight: bold; -fx-background-color: " + GREEN + "; -fx-padding: 2 6 2 6; -fx-background-radius: 3;");
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button testBtn = new Button("测试");
        testBtn.setStyle(
                "-fx-background-color: #dfe6e9; -fx-text-fill: #636e72;" +
                " -fx-font-size: 11px; -fx-padding: 3 10 3 10;" +
                " -fx-background-radius: 3; -fx-border-radius: 3; -fx-cursor: hand;"
        );
        testBtn.setOnAction(e -> testConnection(name, testBtn));

        Button editBtn = new Button("编辑");
        editBtn.setStyle(testBtn.getStyle());
        editBtn.setOnAction(e -> showEditDialog(db));

        Button deleteBtn = new Button("删除");
        deleteBtn.setStyle(
                "-fx-background-color: #ffeaa7; -fx-text-fill: #d63031;" +
                " -fx-font-size: 11px; -fx-padding: 3 10 3 10;" +
                " -fx-background-radius: 3; -fx-border-radius: 3; -fx-cursor: hand;"
        );
        deleteBtn.setOnAction(e -> deleteDatabase(name));

        topRow.getChildren().addAll(radio, nameLabel, statusBadge, spacer, testBtn, editBtn, deleteBtn);

        Label infoLabel = new Label(host + ":" + port + "/" + database
                + (description != null && !description.isBlank() ? "  —  " + description : ""));
        infoLabel.setStyle("-fx-text-fill: #636e72; -fx-font-size: 11px;");

        card.getChildren().addAll(topRow, infoLabel);
        return card;
    }

    /** 切换活跃数据库 */
    private void switchDatabase(String name) {
        try {
            URL url = new URI(apiBaseUrl + "/api/databases/" + URLEncoder.encode(name, "UTF-8") + "/switch").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);

            if (conn.getResponseCode() == 200) {
                statusLabel.setText("✅ 已切换到: " + name);
                refresh();
            } else {
                statusLabel.setText("⚠ 切换失败");
            }
        } catch (Exception e) {
            statusLabel.setText("⚠ 切换失败: " + e.getMessage());
        }
    }

    /** 添加数据库对话框 */
    private void showAddDialog() {
        ConfigDialog dialog = new ConfigDialog(null);
        dialog.showAndWait().ifPresent(config -> {
            try {
                URL url = new URI(apiBaseUrl + "/api/databases").toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(5000);

                String json = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(config);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }

                if (conn.getResponseCode() == 200) {
                    statusLabel.setText("✅ 已添加: " + config.get("name"));
                    refresh();
                } else {
                    statusLabel.setText("⚠ 添加失败: HTTP " + conn.getResponseCode());
                }
            } catch (Exception e) {
                statusLabel.setText("⚠ 添加失败: " + e.getMessage());
            }
        });
    }

    /** 编辑数据库对话框 */
    private void showEditDialog(Map<String, Object> db) {
        ConfigDialog dialog = new ConfigDialog(db);
        dialog.showAndWait().ifPresent(config -> {
            try {
                String name = (String) db.get("name");
                URL url = new URI(apiBaseUrl + "/api/databases/" + URLEncoder.encode(name, "UTF-8")).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(5000);

                String json = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(config);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }

                if (conn.getResponseCode() == 200) {
                    statusLabel.setText("✅ 已更新: " + name);
                    refresh();
                } else {
                    statusLabel.setText("⚠ 更新失败: HTTP " + conn.getResponseCode());
                }
            } catch (Exception e) {
                statusLabel.setText("⚠ 更新失败: " + e.getMessage());
            }
        });
    }

    /** 删除数据库 */
    private void deleteDatabase(String name) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "确定删除数据库配置「" + name + "」？", ButtonType.YES, ButtonType.NO);
        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.YES) {
                try {
                    URL url = new URI(apiBaseUrl + "/api/databases/" + URLEncoder.encode(name, "UTF-8")).toURL();
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("DELETE");
                    conn.setConnectTimeout(5000);

                    if (conn.getResponseCode() == 200) {
                        statusLabel.setText("✅ 已删除: " + name);
                        refresh();
                    } else {
                        statusLabel.setText("⚠ 删除失败");
                    }
                } catch (Exception e) {
                    statusLabel.setText("⚠ 删除失败: " + e.getMessage());
                }
            }
        });
    }

    /** 获取当前 JAR 文件的绝对路径 */
    private String resolveJarPath() {
        try {
            String path = DatabaseListController.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            if (path.startsWith("/") && path.length() > 3 && path.charAt(2) == ':') {
                path = path.substring(1);
            }
            return path;
        } catch (Exception e) {
            return "D:/DEVELOP/tools/MCP/postgres-router/target/postgres-router.jar";
        }
    }

    /** 测试数据库连接（后台线程 + 超时 + 错误详情弹窗）*/
    private void testConnection(String name, Button testBtn) {
        testBtn.setDisable(true);
        testBtn.setText("测试中...");
        statusLabel.setText("正在测试连接: " + name + "...");

        new Thread(() -> {
            boolean success = false;
            String message = "";
            String detail = "";
            try {
                URL url = new URI(apiBaseUrl + "/api/databases/" + URLEncoder.encode(name, "UTF-8") + "/test").toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                if (conn.getResponseCode() == 200) {
                    String json = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    Map<String, Object> result = mapper.readValue(json, Map.class);
                    success = Boolean.TRUE.equals(result.get("success"));
                    message = result.get("message") != null ? result.get("message").toString() : "";
                    detail = result.get("detail") != null ? result.get("detail").toString() : "";
                } else {
                    message = "⚠ 测试请求失败，HTTP " + conn.getResponseCode();
                }
            } catch (Exception e) {
                message = "❌ 连接测试异常: " + e.getMessage();
            }

            final boolean ok = success;
            final String msg = message;
            final String det = detail;
            Platform.runLater(() -> {
                testBtn.setDisable(false);
                testBtn.setText("测试");
                if (ok) {
                    statusLabel.setText("✅ " + msg + ": " + name);
                } else {
                    statusLabel.setText("❌ " + msg);
                    showTestResultDialog(name, msg, det);
                }
            });
        }, "db-test-worker").start();
    }

    /** 显示可复制的错误详情对话框 */
    private void showTestResultDialog(String dbName, String message, String detail) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("连接测试失败 — " + dbName);
        dialog.setHeaderText(null);

        Label headerLabel = new Label(message);
        headerLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #e74c3c;");
        headerLabel.setWrapText(true);

        TextArea detailArea = new TextArea(detail.isEmpty() ? "（无详细错误信息）" : detail);
        detailArea.setEditable(false);
        detailArea.setWrapText(false);
        detailArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px;" +
                " -fx-control-inner-background: #1e1e1e; -fx-text-fill: #9cdcfe;");
        detailArea.setPrefSize(550, 300);

        DialogPane pane = dialog.getDialogPane();
        VBox content = new VBox(10, headerLabel, detailArea);
        content.setPadding(new javafx.geometry.Insets(15));
        pane.setContent(content);

        ButtonType copyBtnType = new ButtonType("📋 复制错误信息", ButtonBar.ButtonData.OTHER);
        pane.getButtonTypes().addAll(copyBtnType, ButtonType.CLOSE);

        final var copyBtn = (Button) pane.lookupButton(copyBtnType);
        copyBtn.setStyle("-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold;");
        copyBtn.setOnAction(e -> {
            StringBuilder sb = new StringBuilder();
            sb.append("数据库: ").append(dbName).append("\n");
            sb.append("错误: ").append(message).append("\n");
            if (!detail.isEmpty()) {
                sb.append("\n--- 详细错误信息 ---\n");
                sb.append(detail);
            }
            ClipboardContent cc = new ClipboardContent();
            cc.putString(sb.toString());
            Clipboard.getSystemClipboard().setContent(cc);
        });

        dialog.showAndWait();
    }
}
