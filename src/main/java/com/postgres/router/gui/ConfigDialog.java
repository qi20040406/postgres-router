package com.postgres.router.gui;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 添加/编辑数据库配置的对话框。
 */
public class ConfigDialog {

    private final TextField nameField = new TextField();
    private final TextField hostField = new TextField();
    private final TextField portField = new TextField("5432");
    private final TextField databaseField = new TextField();
    private final TextField usernameField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final CheckBox sslCheck = new CheckBox("启用 SSL");
    private final TextField descriptionField = new TextField();

    private final Map<String, Object> existingConfig;

    public ConfigDialog(Map<String, Object> existingConfig) {
        this.existingConfig = existingConfig;
        if (existingConfig != null) {
            populateFields(existingConfig);
        }
    }

    /** 从已有配置填充字段 */
    @SuppressWarnings("unchecked")
    private void populateFields(Map<String, Object> config) {
        nameField.setText((String) config.getOrDefault("name", ""));
        hostField.setText((String) config.getOrDefault("host", ""));
        portField.setText(String.valueOf(config.getOrDefault("port", 5432)));
        databaseField.setText((String) config.getOrDefault("database", ""));
        usernameField.setText((String) config.getOrDefault("username", ""));
        // 密码不回显
        sslCheck.setSelected(Boolean.TRUE.equals(config.get("ssl")));
        descriptionField.setText((String) config.getOrDefault("description", ""));
    }

    /** 显示对话框，返回配置结果 */
    public Optional<Map<String, Object>> showAndWait() {
        Dialog<Map<String, Object>> dialog = new Dialog<>();
        dialog.setTitle(existingConfig != null ? "编辑数据库配置" : "添加数据库配置");
        dialog.setHeaderText(existingConfig != null
                ? "编辑数据库连接信息"
                : "输入新的数据库连接信息");

        ButtonType saveButtonType = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(20, 150, 10, 10));

        grid.add(new Label("名称*:"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("主机*:"), 0, 1);
        grid.add(hostField, 1, 1);
        grid.add(new Label("端口*:"), 0, 2);
        grid.add(portField, 1, 2);
        grid.add(new Label("数据库名*:"), 0, 3);
        grid.add(databaseField, 1, 3);
        grid.add(new Label("用户名*:"), 0, 4);
        grid.add(usernameField, 1, 4);
        grid.add(new Label("密码*:"), 0, 5);
        grid.add(passwordField, 1, 5);
        grid.add(sslCheck, 1, 6);
        grid.add(new Label("描述:"), 0, 7);
        grid.add(descriptionField, 1, 7);

        dialog.getDialogPane().setContent(grid);

        // 请求焦点到名称字段
        nameField.requestFocus();

        // 转换结果为 Map
        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == saveButtonType) {
                return collectConfig();
            }
            return null;
        });

        // 验证
        Button saveBtn = (Button) dialog.getDialogPane().lookupButton(saveButtonType);
        saveBtn.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (!validate()) {
                event.consume();
            }
        });

        return dialog.showAndWait();
    }

    /** 收集表单数据 */
    private Map<String, Object> collectConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", nameField.getText().trim());
        config.put("host", hostField.getText().trim());
        config.put("port", Integer.parseInt(portField.getText().trim()));
        config.put("database", databaseField.getText().trim());
        config.put("username", usernameField.getText().trim());
        config.put("password", passwordField.getText());
        config.put("ssl", sslCheck.isSelected());
        config.put("description", descriptionField.getText().trim());
        return config;
    }

    /** 表单验证 */
    private boolean validate() {
        if (nameField.getText().isBlank()) {
            showError("名称不能为空");
            nameField.requestFocus();
            return false;
        }
        if (hostField.getText().isBlank()) {
            showError("主机地址不能为空");
            hostField.requestFocus();
            return false;
        }
        try {
            int port = Integer.parseInt(portField.getText().trim());
            if (port < 1 || port > 65535) {
                showError("端口必须在 1-65535 之间");
                portField.requestFocus();
                return false;
            }
        } catch (NumberFormatException e) {
            showError("端口必须是数字");
            portField.requestFocus();
            return false;
        }
        if (databaseField.getText().isBlank()) {
            showError("数据库名不能为空");
            databaseField.requestFocus();
            return false;
        }
        if (usernameField.getText().isBlank()) {
            showError("用户名不能为空");
            usernameField.requestFocus();
            return false;
        }
        if (!existingConfig.containsKey("password_retained") && passwordField.getText().isEmpty()) {
            // 如果是新增，密码必填；如果是编辑且密码为空，保留原密码
            if (existingConfig == null) {
                showError("密码不能为空");
                passwordField.requestFocus();
                return false;
            }
        }
        return true;
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message, ButtonType.OK);
        alert.showAndWait();
    }
}
