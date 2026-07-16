package com.postgres.router.mcp;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.logging.Logger;

/**
 * 服务请求日志存储（内存环形缓冲区）。
 * 记录 agent 对 MCP 代理的全部请求/响应，支持按 id 查看详情。
 */
public class ServiceLogStore {

    private static final Logger LOG = Logger.getLogger(ServiceLogStore.class.getName());

    /** 默认保留的最大条目数 */
    private static final int DEFAULT_CAPACITY = 1000;

    /** 单条响应内容最大长度（超过部分截断，详情接口保留原始值） */
    private static final int RESPONSE_PREVIEW_MAX = 200;

    private final int capacity;
    private final LinkedBlockingDeque<LogEntry> entries;

    public ServiceLogStore() {
        this(DEFAULT_CAPACITY);
    }

    public ServiceLogStore(int capacity) {
        this.capacity = capacity;
        this.entries = new LinkedBlockingDeque<>(capacity);
    }

    /** 添加一条日志条目（超出容量时自动移除最旧条目） */
    public void addEntry(LogEntry entry) {
        while (!entries.offerLast(entry)) {
            entries.pollFirst();
        }
    }

    /** 最近 N 条，按时间正序 */
    public List<LogEntry> getRecent(int count) {
        List<LogEntry> result = new ArrayList<>();
        Iterator<LogEntry> it = entries.iterator();
        int skip = Math.max(0, entries.size() - count);
        int i = 0;
        while (it.hasNext()) {
            if (i >= skip) result.add(it.next());
            else it.next();
            i++;
        }
        return result;
    }

    /** 按 id 精确查找 */
    public Optional<LogEntry> getById(String id) {
        return entries.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    /** 按 agent 名称筛选最近 N 条 */
    public List<LogEntry> getByAgent(String agentName, int count) {
        List<LogEntry> result = new ArrayList<>();
        Iterator<LogEntry> it = entries.descendingIterator();
        while (it.hasNext() && result.size() < count) {
            LogEntry e = it.next();
            if (agentName.equals(e.agentName())) result.add(0, e);
        }
        return result;
    }

    /** 当前条目数 */
    public int size() {
        return entries.size();
    }

    /**
     * 日志条目 record。
     * 每个条目对应一次 MCP 请求/响应。
     */
    public record LogEntry(
            String id,
            Instant timestamp,
            String sessionKey,
            String agentName,
            String method,
            String requestParams,
            String responsePreview,
            String responseContent,
            boolean isError,
            long durationMs) {

        /** 截短的响应预览（用于列表展示） */
        public static String trimPreview(String full) {
            if (full == null) return "";
            if (full.length() <= RESPONSE_PREVIEW_MAX) return full;
            return full.substring(0, RESPONSE_PREVIEW_MAX) + "… (" + full.length() + " chars)";
        }

        /** 截短的请求参数预览 */
        public static String trimParamsPreview(String params) {
            if (params == null) return "";
            int limit = 120;
            if (params.length() <= limit) return params;
            return params.substring(0, limit) + "…";
        }
    }

    /** 便捷方法：构建并添加日志条目 */
    public LogEntry record(String sessionKey, String agentName, String method,
                           String requestParams, String responseContent,
                           boolean isError, long durationMs) {
        String id = UUID.randomUUID().toString();
        String preview = LogEntry.trimPreview(responseContent);
        LogEntry entry = new LogEntry(id, Instant.now(), sessionKey, agentName,
                method, requestParams, preview, responseContent, isError, durationMs);
        addEntry(entry);
        return entry;
    }
}
