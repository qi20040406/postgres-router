package com.postgres.router.gui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.logging.Logger;

/**
 * Java FX GUI 管理界面入口。
 */
public class AdminApp extends Application {

    private static final Logger LOG = Logger.getLogger(AdminApp.class.getName());

    private static String apiBaseUrl = "http://127.0.0.1:18880";

    public static void setApiBaseUrl(String url) {
        apiBaseUrl = url;
    }

    @Override
    public void start(Stage primaryStage) {
        LOG.info("启动 Java FX GUI 管理界面");

        DatabaseListController controller = new DatabaseListController(apiBaseUrl);
        controller.setStage(primaryStage);
        Scene scene = new Scene(controller.getRoot(), 740, 540);
        scene.setFill(javafx.scene.paint.Color.web("#f0f2f5"));

        primaryStage.setTitle("MCP Postgres Router");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(640);
        primaryStage.setMinHeight(440);

        // X 按钮直接退出整个进程（清理托盘图标）
        primaryStage.setOnCloseRequest(e -> {
            SystemTrayHelper.removeTrayIcon();
            Platform.exit();
            System.exit(0);
        });
        primaryStage.show();

        controller.refresh();
    }

    public static void launchGui(String[] args) {
        launch(args);
    }
}
