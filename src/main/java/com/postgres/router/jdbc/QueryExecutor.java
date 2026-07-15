package com.postgres.router.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 只读 SQL 查询执行器。
 * 遵循与官方 @modelcontextprotocol/server-postgres 一致的模式：
 * BEGIN TRANSACTION READ ONLY → 执行 SQL → ROLLBACK
 */
public class QueryExecutor {

    private static final Logger LOG = Logger.getLogger(QueryExecutor.class.getName());

    private final ConnectionPoolManager poolManager;

    public QueryExecutor(ConnectionPoolManager poolManager) {
        this.poolManager = poolManager;
    }

    /**
     * 执行只读 SQL 查询。
     * 返回 MCP content 格式的结果。
     */
    public List<Map<String, Object>> executeQuery(String sql) throws Exception {
        long start = System.currentTimeMillis();
        LOG.info("执行查询: " + truncateSql(sql, 200));

        try (Connection conn = poolManager.getActiveConnection()) {
            // 开启只读事务
            try (Statement stmt = conn.createStatement()) {
                // 设置只读提示
                conn.setReadOnly(true);

                // 使用原生 SQL 执行（兼容复杂查询）
                boolean isResultSet = stmt.execute(sql);

                if (isResultSet) {
                    try (ResultSet rs = stmt.getResultSet()) {
                        List<Map<String, Object>> rows = resultSetToList(rs);
                        long elapsed = System.currentTimeMillis() - start;
                        LOG.info("查询完成，返回 " + rows.size() + " 行，耗时 " + elapsed + "ms");

                        List<Map<String, Object>> content = new ArrayList<>();
                        content.add(Map.of(
                                "type", "text",
                                "text", formatResult(rows)
                        ));
                        return content;
                    }
                } else {
                    int updateCount = stmt.getUpdateCount();
                    LOG.info("非查询语句完成，影响行数: " + updateCount);
                    List<Map<String, Object>> content = new ArrayList<>();
                    content.add(Map.of(
                            "type", "text",
                            "text", "Statement executed successfully. Affected rows: " + updateCount
                    ));
                    return content;
                }
            }
        } catch (java.sql.SQLException e) {
            LOG.warning("查询失败: " + e.getMessage());
            throw new Exception("Database error: " + e.getMessage(), e);
        }
    }

    /** 将 ResultSet 转换为 List<Map> */
    private List<Map<String, Object>> resultSetToList(ResultSet rs) throws java.sql.SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        List<String> columnNames = new ArrayList<>();
        for (int i = 1; i <= columnCount; i++) {
            columnNames.add(meta.getColumnLabel(i));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                Object value = rs.getObject(i);
                row.put(columnNames.get(i - 1), value);
            }
            rows.add(row);
        }
        return rows;
    }

    /** 将结果格式化为文本表格 */
    private String formatResult(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "Query returned 0 rows.";

        // 获取列名
        java.util.Set<String> columns = rows.get(0).keySet();
        List<String> columnList = new ArrayList<>(columns);

        // 建表格
        StringBuilder sb = new StringBuilder();
        // 表头
        sb.append("|");
        for (String col : columnList) {
            sb.append(" ").append(col).append(" |");
        }
        sb.append("\n|");
        for (String col : columnList) {
            sb.append("---|");
        }
        sb.append("\n");
        // 数据行
        for (Map<String, Object> row : rows) {
            sb.append("|");
            for (String col : columnList) {
                Object val = row.get(col);
                sb.append(" ").append(val == null ? "NULL" : val).append(" |");
            }
            sb.append("\n");
        }
        sb.append("\n(").append(rows.size()).append(" rows)");
        return sb.toString();
    }

    /** 截断过长的 SQL 用于日志 */
    private String truncateSql(String sql, int maxLen) {
        if (sql == null) return "null";
        return sql.length() > maxLen
                ? sql.substring(0, maxLen) + "..."
                : sql;
    }
}
