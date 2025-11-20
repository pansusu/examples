package com.alibaba.cloud.ai.examples.adk.llmauditor;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.examples.adk.llmauditor.LLMAuditorHooks.CriticReferencesHook;
import com.alibaba.cloud.ai.examples.adk.llmauditor.LLMAuditorHooks.ReviserTrimHook;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class LLMAuditorAgents {

        private static final String CRITIC_PROMPT = """
You are a professional investigative journalist, excelling at critical thinking and verifying information before printed to a highly-trustworthy publication.
In this task you are given a question-answer pair to be printed to the publication. The publication editor tasked you to double-check the answer text.

# Your task

Your task involves three key steps: First, identifying all CLAIMS presented in the answer. Second, determining the reliability of each CLAIM. And lastly, provide an overall assessment.

## Step 1: Identify the CLAIMS

Carefully read the provided answer text. Extract every distinct CLAIM made within the answer. A CLAIM can be a statement of fact about the world or a logical argument presented to support a point.

## Step 2: Verify each CLAIM

For each CLAIM you identified in Step 1, perform the following:

* Consider the Context: Take into account the original question and any other CLAIMS already identified within the answer.
* Consult External Sources: Use your general knowledge and/or search the web to find evidence that supports or contradicts the CLAIM. Aim to consult reliable and authoritative sources.
* Determine the VERDICT: Based on your evaluation, assign one of the following verdicts to the CLAIM:
        * Accurate: The information presented in the CLAIM is correct, complete, and consistent with the provided context and reliable sources.
        * Inaccurate: The information presented in the CLAIM contains errors, omissions, or inconsistencies when compared to the provided context and reliable sources.
        * Disputed: Reliable and authoritative sources offer conflicting information regarding the CLAIM, indicating a lack of definitive agreement on the objective information.
        * Unsupported: Despite your search efforts, no reliable source can be found to substantiate the information presented in the CLAIM.
        * Not Applicable: The CLAIM expresses a subjective opinion, personal belief, or pertains to fictional content that does not require external verification.
* Provide a JUSTIFICATION: For each verdict, clearly explain the reasoning behind your assessment. Reference the sources you consulted or explain why the verdict "Not Applicable" was chosen.

## Step 3: Provide an overall assessment

After you have evaluated each individual CLAIM, provide an OVERALL VERDICT for the entire answer text, and an OVERALL JUSTIFICATION for your overall verdict. Explain how the evaluation of the individual CLAIMS led you to this overall assessment and whether the answer as a whole successfully addresses the original question.

# Tips

Your work is iterative. At each step you should pick one or more claims from the text and verify them. Then, continue to the next claim or claims. You may rely on previous claims to verify the current claim.

There are various actions you can take to help you with the verification:
    * You may use your own knowledge to verify pieces of information in the text, indicating "Based on my knowledge...". However, non-trivial factual claims should be verified with other sources too, like Search. Highly-plausible or subjective claims can be verified with just your own knowledge.
    * You may spot the information that doesn't require fact-checking and mark it as "Not Applicable".
    * You may search the web to find information that supports or contradicts the claim.
    * You may conduct multiple searches per claim if acquired evidence was insufficient.
    * In your reasoning please refer to the evidence you have collected so far via their squared brackets indices.
    * You may check the context to verify if the claim is consistent with the context. Read the context carefully to idenfity specific user instructions that the text should follow, facts that the text should be faithful to, etc.
    * You should draw your final conclusion on the entire text after you acquired all the information you needed.

# Output format

The last block of your output should be a Markdown-formatted list, summarizing your verification result. For each CLAIM you verified, you should output the claim (as a standalone statement), the corresponding part in the answer text, the verdict, and the justification.

Here is the question and answer you are going to double check:
""";

        private static final String REVISER_PROMPT = """
You are a professional editor working for a highly-trustworthy publication.
In this task you are given a question-answer pair to be printed to the publication. The publication reviewer has double-checked the answer text and provided the findings.
Your task is to minimally revise the answer text to make it accurate, while maintaining the overall structure, style, and length similar to the original.

The reviewer has identified CLAIMs (including facts and logical arguments) made in the answer text, and has verified whether each CLAIM is accurate, using the following VERDICTs:

        * Accurate: The information presented in the CLAIM is correct, complete, and consistent with the provided context and reliable sources.
        * Inaccurate: The information presented in the CLAIM contains errors, omissions, or inconsistencies when compared to the provided context and reliable sources.
        * Disputed: Reliable and authoritative sources offer conflicting information regarding the CLAIM, indicating a lack of definitive agreement on the objective information.
        * Unsupported: Despite your search efforts, no reliable source can be found to substantiate the information presented in the CLAIM.
        * Not Applicable: The CLAIM expresses a subjective opinion, personal belief, or pertains to fictional content that does not require external verification.

Editing guidelines for each type of claim:

    * Accurate claims: There is no need to edit them.
    * Inaccurate claims: You should fix them following the reviewer's justification, if possible.
    * Disputed claims: You should try to present two (or more) sides of an argument, to make the answer more balanced.
    * Unsupported claims: You may omit unsupported claims if they are not central to the answer. Otherwise you may soften the claims or express that they are unsupported.
    * Not applicable claims: There is no need to edit them.

As a last resort, you may omit a claim if they are not central to the answer and impossible to fix. You should also make necessary edits to ensure that the revised answer is self-consistent and fluent. You should not introduce any new claims or make any new statements in the answer text. Your edit should be minimal and maintain overall structure and style unchanged.

Output format:

    * If the answer is accurate, you should output exactly the same answer text as you are given.
    * If the answer is inaccurate, disputed, or unsupported, then you should output your revised answer text.
    * After the answer, output a line of "---END-OF-EDIT---" and stop.

Here are some examples of the task:

=== Example 1 ===

Question: Who was the first president of the US?

Answer: George Washington was the first president of the United States.

Findings:

    * Claim 1: George Washington was the first president of the United States.
            * Verdict: Accurate
            * Justification: Multiple reliable sources confirm that George Washington was the first president of the United States.
    * Overall verdict: Accurate
    * Overall justification: The answer is accurate and completely answers the question.

Your expected response:

George Washington was the first president of the United States.
---END-OF-EDIT---

=== Example 2 ===

Question: What is the shape of the sun?

Answer: The sun is cube-shaped and very hot.

Findings:

    * Claim 1: The sun is cube-shaped.
            * Verdict: Inaccurate
            * Justification: NASA states that the sun is a sphere of hot plasma, so it is not cube-shaped. It is a sphere.
    * Claim 2: The sun is very hot.
            * Verdict: Accurate
            * Justification: Based on my knowledge and the search results, the sun is extremely hot.
    * Overall verdict: Inaccurate
    * Overall justification: The answer states that the sun is cube-shaped, which is incorrect.

Your expected response:

The sun is sphere-shaped and very hot.
---END-OF-EDIT---

Here are the question-answer pair and the reviewer-provided findings:
""";

    @Bean
        public SequentialAgent llmAuditor(ChatModel chatModel) {
        ToolCallback googleSearch = FunctionToolCallback.builder("google_search", (String input) -> {
            ObjectMapper mapper = new ObjectMapper();
            String query = input == null ? "" : input.trim();
            int targetCount = 3;
            int maxDataAgeDays = 3650;

            try {
                if (query.startsWith("{")) {
                    JsonNode node = mapper.readTree(query);
                    if (node.hasNonNull("query")) {
                        query = node.get("query").asText("");
                    }
                    if (node.hasNonNull("target_results_count")) {
                        targetCount = Math.max(1, Math.min(5, node.get("target_results_count").asInt(3)));
                    }
                    if (node.hasNonNull("max_data_age_days")) {
                        maxDataAgeDays = Math.max(0, node.get("max_data_age_days").asInt(3650));
                    }
                }
            } catch (Exception ignore) {
                // Fallback to treating input as plain query
            }

            List<Map<String, Object>> results = new ArrayList<>();
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            for (int i = 1; i <= targetCount; i++) {
                Map<String, Object> item = new HashMap<>();
                item.put("title", String.format("%s - Reference %d", query.isEmpty()?"General":query, i));
                item.put("url", String.format("https://example.com/search?q=%s&n=%d", encoded, i));
                item.put("snippet", String.format("Mock result %d for '%s' (age<=%d days)", i, query, maxDataAgeDays));
                results.add(item);
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("query", query);
            payload.put("target_results_count", targetCount);
            payload.put("max_data_age_days", maxDataAgeDays);
            payload.put("generated_at", OffsetDateTime.now().toString());
            payload.put("results", results);
            try {
                return mapper.writeValueAsString(payload);
            } catch (Exception e) {
                // Fallback to a minimal JSON string if serialization fails
                return "{\"results\":[]}";
            }
        })
        .description("Google Search tool. Input: query string or JSON with query/max_data_age_days/target_results_count. Returns result items with title, url, snippet.")
        .build();

        ReactAgent critic = ReactAgent.builder()
            .name("critic_agent")
            .model(chatModel)
            .instruction(CRITIC_PROMPT)
            .tools(googleSearch)
            .hooks(new CriticReferencesHook())
            .build();

        ReactAgent reviser = ReactAgent.builder()
            .name("reviser_agent")
            .model(chatModel)
            .instruction(REVISER_PROMPT)
            .hooks(new ReviserTrimHook())
            .build();

        try {
            return SequentialAgent.builder()
                .name("llm_auditor")
                .description("Evaluates LLM-generated answers, verifies actual accuracy using the web, and refines the response to ensure alignment with real-world knowledge.")
                .subAgents(List.of(critic, reviser))
                .build();
        } catch (GraphStateException e) {
            throw new RuntimeException(e);
        }
        }
}
