package com.postgres.router.jdbc;

import com.postgres.router.config.DatabaseConfig;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * 多数据库连接池管理器。
 * 为每个 DatabaseConfig 管理独立的连接池，支持添加、移除、切换活跃库。
 */
public class ConnectionPoolManager implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ConnectionPoolManager.class.getName());

    /** name → DataSource 映射 */
    private final Map<String, DataSource> pools = new ConcurrentHashMap<>();

    /** 当前活跃的数据库名称 */
    private final AtomicReference<String> activeName = new AtomicReference<>();

    /** 当前活跃的数据库配置（供查询执行使用） */
    private final AtomicReference<DatabaseConfig> activeConfig = new AtomicReference<>();

    // ======================== 连接池管理 ========================

    /** 为数据库配置创建或更新连接池 */
    public void addPool(DatabaseConfig config) {
        String name = config.getName();
        // 创建简单的 DataSource 实现
        SimpleDataSource ds = new SimpleDataSource(config);
        DataSource old = pools.put(name, ds);
        if (old != null) {
            LOG.info("更新连接池: " + name);
        } else {
            LOG.info("添加连接池: " + name);
        }
    }

    /** 移除数据库连接池 */
    public void removePool(String name) {
        DataSource removed = pools.remove(name);
        if (removed != null) {
            LOG.info("移除连接池: " + name);
            // 如果是当前活跃库，清空活跃状态
            if (name.equals(activeName.get())) {
                activeName.set(null);
                activeConfig.set(null);
            }
        }
    }

    /** 切换活跃数据库 */
    public void switchActive(String name) {
        if (!pools.containsKey(name)) {
            throw new IllegalArgumentException("未知数据库: " + name);
        }
        activeName.set(name);
        // 从 pools 的 DataSource 中反查 DatabaseConfig
        DataSource ds = pools.get(name);
        if (ds instanceof SimpleDataSource sds) {
            activeConfig.set(sds.config);
        }
        LOG.info("切换活跃库至: " + name);
    }

    /** 测试数据库连接是否可用 */
    public boolean testConnection(DatabaseConfig config) {
        String url = config.jdbcUrl();
        try (Connection conn = java.sql.DriverManager.getConnection(
                url, config.getUsername(), config.getPassword())) {
            return conn.isValid(5);
        } catch (SQLException e) {
            LOG.warning("连接测试失败: " + config.getName() + " - " + e.getMessage());
            return false;
        }
    }

    // ======================== 查询接口 ========================

    /** 获取当前活跃数据库的连接 */
    public Connection getActiveConnection() throws SQLException {
        String name = activeName.get();
        if (name == null) {
            throw new SQLException("没有活跃的数据库连接，请先在 GUI 中切换到目标数据库");
        }
        DataSource ds = pools.get(name);
        if (ds == null) {
            throw new SQLException("连接池不存在: " + name);
        }
        return ds.getConnection();
    }

    /** 获取当前活跃的数据库名称 */
    public String getActiveName() {
        return activeName.get();
    }

    /** 获取当前活跃的 DatabaseConfig */
    public DatabaseConfig getActiveConfig() {
        return activeConfig.get();
    }

    /** 获取所有已注册的数据库名称 */
    public java.util.Set<String> getPoolNames() {
        return pools.keySet();
    }

    // ======================== 同步配置 ========================

    /** 根据 RouterConfig 同步连接池（添加/移除/切换） */
    public void syncWithConfig(com.postgres.router.config.RouterConfig config) {
        // 添加或更新
        if (config.getDatabases() != null) {
            for (DatabaseConfig db : config.getDatabases()) {
                addPool(db);
            }
        }
        // 移除已经不存在的
        if (config.getDatabases() != null) {
            var activeNames = config.getDatabases().stream()
                    .map(DatabaseConfig::getName)
                    .toList();
            pools.keySet().removeIf(name -> !activeNames.contains(name));
        } else {
            pools.clear();
        }
        // 切换活跃库
        String active = config.getActive();
        if (active != null && pools.containsKey(active)) {
            switchActive(active);
        }
    }

    @Override
    public void close() {
        pools.clear();
        activeName.set(null);
        activeConfig.set(null);
    }

    // ======================== 简单的 DataSource 实现 ========================

    /**
     * 基于 DriverManager 的简单 DataSource 实现。
     * 每次 getConnection() 创建新连接（后续可按需替换为 HikariCP）。
     */
    private static class SimpleDataSource implements DataSource {
        private final DatabaseConfig config;

        SimpleDataSource(DatabaseConfig config) {
            this.config = config;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return java.sql.DriverManager.getConnection(
                    config.jdbcUrl(), config.getUsername(), config.getPassword());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return java.sql.DriverManager.getConnection(config.jdbcUrl(), username, password);
        }

        @Override
        public PrintWriter getLogWriter() { return null; }
        @Override
        public void setLogWriter(PrintWriter out) {}
        @Override
        public void setLoginTimeout(int seconds) {}
        @Override
        public int getLoginTimeout() { return 0; }
        @Override
        public java.util.logging.Logger getParentLogger() { return null; }
        @Override
        public <T> T unwrap(Class<T> iface) { return null; }
        @Override
        public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
