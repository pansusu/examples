/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.demo.graph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.GraphRepresentation;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.node.HttpNode;
import com.alibaba.cloud.ai.graph.node.QuestionClassifierNode;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;

@Component
public class GraphBuilder {

    @Bean
    public CompiledGraph buildGraph(ChatModel chatModel) throws GraphStateException {
        ChatClient chatClient = ChatClient.builder(chatModel).defaultAdvisors(new SimpleLoggerAdvisor()).build();

        KeyStrategyFactory keyStrategyFactory = new KeyStrategyFactoryBuilder()
                .addPatternStrategy("input", (o1, o2) -> o2)
                .addPatternStrategy("department_classifier_output", (o1, o2) -> o2)
                .addPatternStrategy("negative_processor_output", (o1, o2) -> o2)
                .addPatternStrategy("pos_neg_classifier_output", (o1, o2) -> o2)
                .addPatternStrategy("positive_recorder_output", (o1, o2) -> o2)
                .build();

        StateGraph stateGraph = new StateGraph(keyStrategyFactory);
        // add nodes
        // —— QuestionClassifierNode [pos_neg_classifier] ——
        QuestionClassifierNode questionClassifier1 = QuestionClassifierNode.builder()
            .chatClient(chatClient)
            .inputTextKey("input")
            .categories(List.of("positive feedback", "negative feedback"))
            .outputKey("pos_neg_classifier_output")
            .classificationInstructions(List.of("请根据输入内容选择对应分类"))
            .build();
        stateGraph.addNode("pos_neg_classifier", AsyncNodeAction.node_async(questionClassifier1));

        // —— QuestionClassifierNode [department_classifier] ——
        QuestionClassifierNode questionClassifier2 = QuestionClassifierNode.builder()
            .chatClient(chatClient)
            .inputTextKey("input")
            .categories(List.of("after-sale service", "product quality"))
            .outputKey("department_classifier_output")
            .classificationInstructions(List.of("请根据输入内容选择对应分类"))
            .build();
        stateGraph.addNode("department_classifier", AsyncNodeAction.node_async(questionClassifier2));

        // —— HttpNode [positive_recorder] ——
        HttpNode http1 = HttpNode.builder()
                .url("http://47.83.24.236:38080/negative")
                .header("Content-Type", "application/json")
                .retryConfig(new HttpNode.RetryConfig(3, 100, true))
                .outputKey("positive_recorder_output")
                .build();
        stateGraph.addNode("positive_recorder", AsyncNodeAction.node_async(http1));

        // —— HttpNode [negative_processor] ——
        HttpNode http2 = HttpNode.builder()
                .url("http://47.83.24.236:38080/positive")
                .header("Content-Type", "application/json")
                .retryConfig(new HttpNode.RetryConfig(3, 100, true))
                .outputKey("negative_processor_output")
                .build();
        stateGraph.addNode("negative_processor", AsyncNodeAction.node_async(http2));


        // add edges
        stateGraph.addEdge(START, "pos_neg_classifier");
        stateGraph.addEdge("positive_recorder", END);
        stateGraph.addEdge("negative_processor", END);
        stateGraph.addConditionalEdges("pos_neg_classifier",
            edge_async(state -> {
                String value = state.value("pos_neg_classifier_output", String.class).orElse("");
            	if (value.contains("negative feedback")) return "negative feedback";
            	if (value.contains("positive feedback")) return "positive feedback";
                return null;
            }),
            Map.of("negative feedback", "department_classifier", "positive feedback", "negative_processor")
        );
        stateGraph.addConditionalEdges("department_classifier",
            edge_async(state -> {
                String value = state.value("department_classifier_output", String.class).orElse("");
            	if (value.contains("after-sale service")) return "after-sale service";
                if (value.contains("product quality")) return "product quality";
                return null;
            }),
            Map.of("after-sale service", "positive_recorder", "product quality", "positive_recorder")
        );

        printGraphImage(stateGraph);

        return stateGraph.compile();
    }

    private static void printGraphImage(StateGraph stateGraph) {
        GraphRepresentation graphRepresentation = stateGraph.getGraph(GraphRepresentation.Type.PLANTUML,
                "workflow graph");
        System.out.println("\n\n");
        System.out.println(graphRepresentation.content());
        System.out.println("\n\n");
    }
}
