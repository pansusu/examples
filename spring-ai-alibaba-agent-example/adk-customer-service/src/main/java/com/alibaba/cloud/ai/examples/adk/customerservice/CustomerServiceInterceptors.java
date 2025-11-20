package com.alibaba.cloud.ai.examples.adk.customerservice;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.Collections;
import java.util.List;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;

/**
 * Hooks and interceptors to mirror Python callbacks: before_agent, before_tool, after_tool.
 */
public class CustomerServiceInterceptors {

    /**
     * BEFORE_AGENT: ensure customer_profile is present in state.
     */
    @HookPositions({HookPosition.BEFORE_AGENT})
    public static class CustomerServiceProfileHook extends AgentHook {
        @Override
        public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
            Map<String, Object> updates = new HashMap<>();
            if (state.value("customer_profile").isEmpty()) {
                updates.put("customer_profile", CustomerProfile.current().toJson());
            }
            return CompletableFuture.completedFuture(updates);
        }

        @Override
        public String getName() {
            return "CustomerServiceProfileHook";
        }

        @Override
        public List<JumpTo> canJumpTo() {
            return Collections.emptyList();
        }

    }

    /**
     * ToolInterceptor implementing before_tool/after_tool semantics from Python.
     */
    public static class CustomerServiceToolInterceptor extends ToolInterceptor {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        @Override
        public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
            try {
                Map<String, Object> args = parseArgs(request.getArguments());
                // lowercase values recursively
                args = lowercaseValues(args);

                // Validate customer_id if present
                if (args.containsKey("customer_id")) {
                    String cid = String.valueOf(args.get("customer_id"));
                    if (!"123".equals(cid)) {
                        return ToolCallResponse.of(request.getToolCallId(), request.getToolName(),
                                "You cannot use the tool with customer_id " + cid + ", only for 123.");
                    }
                }

                // Pre-approval flow for sync_ask_for_approval
                if ("sync_ask_for_approval".equals(request.getToolName())) {
                    Object v = args.get("value");
                    double value = v == null ? 0 : toDouble(v);
                    if (value <= 10.0) {
                        return ToolCallResponse.of(request.getToolCallId(), request.getToolName(),
                                "{\"status\":\"approved\",\"message\":\"You can approve this discount; no manager needed.\"}");
                    }
                }

                // Shortcut message for modify_cart if both add/remove
                if ("modify_cart".equals(request.getToolName())) {
                    Object add = args.get("items_added");
                    Object rem = args.get("items_removed");
                    if (asBoolean(add) && asBoolean(rem)) {
                        return ToolCallResponse.of(request.getToolCallId(), request.getToolName(),
                                "{\"result\":\"I have added and removed the requested items.\"}");
                    }
                }

                // Call underlying tool
                ToolCallResponse response = handler.call(ToolCallRequest.builder(request)
                        .arguments(writeArgs(args))
                        .build());

                // after_tool semantics (log/apply – mocked as pass-through)
                // In a real system, apply discount to cart upon approval, etc.
                return response;
            } catch (Exception e) {
                return ToolCallResponse.of(request.getToolCallId(), request.getToolName(),
                        "Tool interceptor error: " + e.getMessage());
            }
        }

        @Override
        public String getName() {
            return "CustomerServiceToolInterceptor";
        }

        private static Map<String, Object> parseArgs(String json) {
            if (json == null || json.isBlank()) return new HashMap<>();
            try {
                return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                // Not JSON, treat as simple input string
                Map<String, Object> m = new HashMap<>();
                m.put("input", json);
                return m;
            }
        }

        private static String writeArgs(Map<String, Object> map) {
            try {
                return MAPPER.writeValueAsString(map);
            } catch (Exception e) {
                return "{}";
            }
        }

        private static Map<String, Object> lowercaseValues(Map<String, Object> src) {
            Map<String, Object> out = new HashMap<>();
            for (Map.Entry<String, Object> e : src.entrySet()) {
                out.put(e.getKey(), lower(e.getValue()));
            }
            return out;
        }

        private static Object lower(Object v) {
            if (v == null) return null;
            if (v instanceof String s) return s.toLowerCase(Locale.ROOT);
            if (v instanceof Map<?, ?> m) {
                Map<String, Object> res = new HashMap<>();
                for (Map.Entry<?, ?> en : m.entrySet()) {
                    res.put(String.valueOf(en.getKey()), lower(en.getValue()));
                }
                return res;
            }
            if (v instanceof Iterable<?> it) {
                java.util.List<Object> list = new java.util.ArrayList<>();
                for (Object o : it) list.add(lower(o));
                return list;
            }
            return v;
        }

        private static boolean asBoolean(Object v) {
            if (v == null) return false;
            if (v instanceof Boolean b) return b;
            return Boolean.parseBoolean(String.valueOf(v));
        }

        private static double toDouble(Object v) {
            if (v instanceof Number n) return n.doubleValue();
            try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0; }
        }
    }
}
