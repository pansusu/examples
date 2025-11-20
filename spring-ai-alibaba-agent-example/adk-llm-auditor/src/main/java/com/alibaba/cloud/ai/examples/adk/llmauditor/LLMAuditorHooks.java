package com.alibaba.cloud.ai.examples.adk.llmauditor;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.state.RemoveByHash;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Hooks to approximate Python callbacks:
 * - CriticReferencesHook: after model, appends a Reference section based on recent google_search tool results
 * - ReviserTrimHook: after model, trims output at ---END-OF-EDIT---
 */
public class LLMAuditorHooks {

    @HookPositions({HookPosition.AFTER_MODEL})
    public static class CriticReferencesHook extends ModelHook {
        @Override
        public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
            Optional<List> opt = state.value("messages", List.class);
            if (opt.isEmpty()) return CompletableFuture.completedFuture(Map.of());
            @SuppressWarnings("unchecked")
            List<Message> messages = (List<Message>) opt.get();
            if (messages.isEmpty()) return CompletableFuture.completedFuture(Map.of());

            // Collect recent google_search tool results (titles and urls)
            List<String> refs = new ArrayList<>();
            for (int i = messages.size() - 1; i >= 0 && refs.size() < 5; i--) {
                Message m = messages.get(i);
                if (m instanceof ToolResponseMessage trm) {
                    for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
                        if ("google_search".equals(resp.name())) {
                            String content = Objects.toString(resp.responseData(), "");
                            refs.addAll(extractRefs(content));
                        }
                    }
                }
            }
            if (refs.isEmpty()) return CompletableFuture.completedFuture(Map.of());

            StringBuilder sb = new StringBuilder();
            sb.append("\n\nReference:\n\n");
            for (String line : refs) {
                sb.append(line);
            }
            AssistantMessage refMsg = new AssistantMessage(sb.toString());
            return CompletableFuture.completedFuture(Map.of("messages", List.of(refMsg)));
        }

        private List<String> extractRefs(String json) {
            try {
                // very light-weight extract without JSON lib to avoid deps; expect {"results":[{...}]}
                List<String> lines = new ArrayList<>();
                int arrIdx = json.indexOf("\"results\"");
                if (arrIdx < 0) return lines;
                int start = json.indexOf('[', arrIdx);
                int end = json.indexOf(']', start);
                if (start < 0 || end < 0) return lines;
                String arr = json.substring(start + 1, end);
                String[] items = arr.split("\\},\\{");
                for (String it : items) {
                    String title = extractString(it, "title");
                    String url = extractString(it, "url");
                    String snippet = extractString(it, "snippet");
                    if (!title.isEmpty() && !url.isEmpty()) {
                        lines.add(String.format("* [%s](%s)%s\n", title, url,
                                snippet.isEmpty() ? "" : ": " + snippet));
                    }
                }
                return lines;
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }

        private String extractString(String src, String key) {
            try {
                int k = src.indexOf('"' + key + '"');
                if (k < 0) return "";
                int c = src.indexOf(':', k);
                int q1 = src.indexOf('"', c + 1);
                int q2 = src.indexOf('"', q1 + 1);
                if (q1 < 0 || q2 < 0) return "";
                return src.substring(q1 + 1, q2);
            } catch (Exception e) {
                return "";
            }
        }

        @Override
        public String getName() {
            return "CriticReferencesHook";
        }

        @Override
        public List<JumpTo> canJumpTo() {
            return List.of();
        }
    }

    @HookPositions({HookPosition.AFTER_MODEL})
    public static class ReviserTrimHook extends ModelHook {
        private static final String MARK = "---END-OF-EDIT---";
        @Override
        public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
            Optional<List> opt = state.value("messages", List.class);
            if (opt.isEmpty()) return CompletableFuture.completedFuture(Map.of());
            @SuppressWarnings("unchecked")
            List<Message> messages = (List<Message>) opt.get();
            if (messages.isEmpty()) return CompletableFuture.completedFuture(Map.of());
            Message last = messages.get(messages.size() - 1);
            if (!(last instanceof AssistantMessage am)) return CompletableFuture.completedFuture(Map.of());
            String content = Objects.toString(am.getText(), "");
            int idx = content.indexOf(MARK);
            if (idx < 0) return CompletableFuture.completedFuture(Map.of());
            String trimmed = content.substring(0, idx).trim();
            AssistantMessage newMsg = new AssistantMessage(trimmed, am.getMetadata(), am.getToolCalls(), am.getMedia());
            return CompletableFuture.completedFuture(Map.of("messages", List.of(newMsg, new RemoveByHash<>(am))));
        }

        @Override
        public String getName() {
            return "ReviserTrimHook";
        }

        @Override
        public List<JumpTo> canJumpTo() {
            return List.of();
        }
    }
}
