package com.postgres.router.config;

import java.util.List;

/**
 * 完整的路由器配置模型。
 * 对应 config.yml 文件结构。
 */
public class RouterConfig {

    private List<DatabaseConfig> databases;
    private String active;

    public RouterConfig() {
    }

    public RouterConfig(List<DatabaseConfig> databases, String active) {
        this.databases = databases;
        this.active = active;
    }

    /** 根据 name 查找数据库配置 */
    public DatabaseConfig findByName(String name) {
        if (name == null || databases == null) return null;
        return databases.stream()
                .filter(db -> name.equals(db.getName()))
                .findFirst()
                .orElse(null);
    }

    /** 获取当前活跃的数据库配置 */
    public DatabaseConfig activeConfig() {
        return findByName(active);
    }

    // ---- Getters & Setters ----

    public List<DatabaseConfig> getDatabases() { return databases; }
    public void setDatabases(List<DatabaseConfig> databases) { this.databases = databases; }

    public String getActive() { return active; }
    public void setActive(String active) { this.active = active; }
}
