package com.postgres.router.gui;

import javafx.application.Platform;
import javafx.stage.Stage;

import java.awt.*;
import java.util.logging.Logger;

/**
 * 系统托盘工具类。
 */
public class SystemTrayHelper {

    private static final Logger LOG = Logger.getLogger(SystemTrayHelper.class.getName());

    private static TrayIcon trayIcon;
    private static boolean installed = false;

    /** 检查当前平台是否支持系统托盘 */
    public static boolean isSupported() {
        return SystemTray.isSupported();
    }

    /** 最小化窗口到系统托盘 */
    public static void minimizeToTray(Stage stage) {
        if (!isSupported()) {
            LOG.info("当前平台不支持系统托盘");
            return;
        }

        // 隐藏唯一的Stage后FX线程不退出，否则Show菜单的Platform.runLater回调无法执行
        Platform.setImplicitExit(false);

        try {
            if (!installed) {
                installTrayIcon(stage);
            }
            stage.hide();
            if (trayIcon != null) {
                trayIcon.displayMessage("MCP Postgres Router",
                        "应用已最小化到系统托盘", TrayIcon.MessageType.INFO);
            }
        } catch (Exception e) {
            LOG.warning("系统托盘操作失败: " + e.getMessage());
        }
    }

    /** 清理系统托盘（关闭时调用） */
    public static void removeTrayIcon() {
        if (trayIcon != null) {
            try { SystemTray.getSystemTray().remove(trayIcon); } catch (Exception ignored) {}
            trayIcon = null;
            installed = false;
            LOG.info("系统托盘已移除");
        }
    }

    /** 安装系统托盘图标 */
    private static void installTrayIcon(Stage stage) {
        try {
            SystemTray tray = SystemTray.getSystemTray();

            PopupMenu popup = new PopupMenu();

            MenuItem showItem = new MenuItem("Show");
            showItem.addActionListener(e -> Platform.runLater(() -> {
                stage.show();
                stage.toFront();
            }));

            MenuItem exitItem = new MenuItem("Exit");
            exitItem.addActionListener(e -> {
                Platform.runLater(() -> {
                    stage.close();
                    Platform.exit();
                });
                System.exit(0);
            });

            popup.add(showItem);
            popup.addSeparator();
            popup.add(exitItem);

            Image image = createIcon();
            trayIcon = new TrayIcon(image, "MCP Postgres Router", popup);
            trayIcon.setImageAutoSize(true);

            trayIcon.addActionListener(e -> Platform.runLater(stage::show));

            tray.add(trayIcon);
            installed = true;
            LOG.info("系统托盘已安装");
        } catch (Exception e) {
            LOG.warning("安装系统托盘失败: " + e.getMessage());
        }
    }

    /** 创建蓝底绿字 Mcp 图标 */
    private static Image createIcon() {
        int size = 16;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        // 蓝色背景
        g.setColor(new Color(0x1565C0));
        g.fillRect(0, 0, size, size);

        // 绿色边框
        g.setColor(new Color(0x4CAF50));
        g.drawRect(0, 0, size - 1, size - 1);

        // 绿色文字 Mcp
        g.setColor(new Color(0x4CAF50));
        g.setFont(new Font("SansSerif", Font.BOLD, 10));
        g.drawString("Mcp", 1, 12);

        g.dispose();
        return img;
    }
}
