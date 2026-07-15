package com.postgres.router.config;

/**
 * 单个数据库连接配置模型。
 */
public class DatabaseConfig {

    private String name;
    private String host;
    private int port = 5432;
    private String database;
    private String username;
    private String password;
    private boolean ssl;
    private String description;

    public DatabaseConfig() {
    }

    public DatabaseConfig(String name, String host, int port, String database,
                          String username, String password, boolean ssl, String description) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.ssl = ssl;
        this.description = description;
    }

    /** 生成 JDBC URL */
    public String jdbcUrl() {
        String url = String.format("jdbc:postgresql://%s:%d/%s", host, port, database);
        if (ssl) {
            url += "?sslmode=require";
        }
        return url;
    }

    // ---- Getters & Setters ----

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public boolean isSsl() { return ssl; }
    public void setSsl(boolean ssl) { this.ssl = ssl; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    @Override
    public String toString() {
        return String.format("%s (%s@%s:%d/%s)", name, username, host, port, database);
    }
}
