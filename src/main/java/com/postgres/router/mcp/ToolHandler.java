package com.postgres.router.mcp;

import com.postgres.router.jdbc.QueryExecutor;

import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * MCP 工具注册与调用路由。
 * 定义工具列表，并将工具调用路由到对应的处理器。
 */
public class ToolHandler {

    private static final Logger LOG = Logger.getLogger(ToolHandler.class.getName());

    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();
    private final QueryExecutor queryExecutor;

    public ToolHandler(QueryExecutor queryExecutor) {
        this.queryExecutor = queryExecutor;
        registerBuiltinTools();
    }

    /** 工具定义 */
    public record ToolDefinition(
            String name,
            String description,
            Map<String, Object> inputSchema
    ) {}

    /** 注册内置工具 */
    private void registerBuiltinTools() {
        // query 工具 — 兼容官方 @modelcontextprotocol/server-postgres
        tools.put("query", new ToolDefinition(
                "query",
                "Run a read-only SQL query against the active database",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "sql", Map.of(
                                        "type", "string",
                                        "description", "SQL query to execute"
                                )
                        ),
                        "required", List.of("sql")
                )
        ));
    }

    /** 获取完整的工具列表，供 tools/list 响应 */
    public List<Map<String, Object>> listTools() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ToolDefinition def : tools.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", def.name());
            entry.put("description", def.description());
            entry.put("inputSchema", def.inputSchema());
            result.add(entry);
        }
        return result;
    }

    /** 调用工具，返回 MCP content 列表 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> callTool(String name, Map<String, Object> arguments) throws Exception {
        if (!tools.containsKey(name)) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }

        if ("query".equals(name)) {
            String sql = arguments != null ? (String) arguments.get("sql") : null;
            if (sql == null || sql.isBlank()) {
                throw new IllegalArgumentException("Missing required parameter: sql");
            }
            return queryExecutor.executeQuery(sql);
        }

        throw new IllegalArgumentException("Unknown tool: " + name);
    }
}
