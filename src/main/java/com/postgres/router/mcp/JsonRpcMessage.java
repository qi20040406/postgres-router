package com.postgres.router.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * JSON-RPC 2.0 消息模型。
 * 支持 Request、Response、Notification、Error 四种消息类型。
 */
public class JsonRpcMessage {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // ======================== 请求 ========================

    /** JSON-RPC 2.0 请求 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Request {
        @JsonProperty("jsonrpc")
        public String jsonrpc = "2.0";
        public Object id;        // number | string | null
        public String method;
        public Object params;    // Map | null

        public Request() {}
        public Request(Object id, String method, Object params) {
            this.id = id;
            this.method = method;
            this.params = params;
        }
    }

    // ======================== 响应 ========================

    /** JSON-RPC 2.0 成功响应 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Response {
        @JsonProperty("jsonrpc")
        public String jsonrpc = "2.0";
        public Object id;
        public Object result;

        public Response() {}
        public Response(Object id, Object result) {
            this.id = id;
            this.result = result;
        }
    }

    // ======================== 错误 ========================

    /** JSON-RPC 2.0 错误 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Error {
        @JsonProperty("jsonrpc")
        public String jsonrpc = "2.0";
        public Object id;
        public ErrorData error;

        public Error() {}
        public Error(Object id, int code, String message) {
            this.id = id;
            this.error = new ErrorData(code, message);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorData {
        public int code;
        public String message;
        public Object data;

        public ErrorData() {}
        public ErrorData(int code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    // ======================== 序列化工具 ========================

    /** 从 JSON 字符串解析为 Request 或 Notification */
    public static Object parse(String json) throws JsonProcessingException {
        return MAPPER.readValue(json, Object.class);
    }

    /** 序列化为 JSON 字符串 */
    public static String toJson(Object msg) throws JsonProcessingException {
        return MAPPER.writeValueAsString(msg);
    }

    /** 从 JSON 字符串解析为 Request */
    public static Request parseRequest(String json) throws JsonProcessingException {
        return MAPPER.readValue(json, Request.class);
    }
}
