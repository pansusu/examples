package com.alibaba.cloud.ai.examples.adk.bugassistant;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;

@Configuration
public class SoftwareBugAssistantAgent {

    private static final String AGENT_INSTRUCTION = """
You are a skilled expert in triaging and debugging software issues for a coffee machine company, QuantumRoast.

**INSTRUCTION:**

Your general process is as follows:

1. **Understand the user's request.** Analyze the user's initial request to understand the goal - for example, "I am seeing X issue. Can you help me find similar open issues?" If you do not understand the request, ask for more information.   
2. **Identify the appropriate tools.** You will be provided with tools for a SQL-based bug ticket database (create, update, search tickets by description). You will also be able to web search via Google Search. Identify one **or more** appropriate tools to accomplish the user's request.  
3. **Populate and validate the parameters.** Before calling the tools, do some reasoning to make sure that you are populating the tool parameters correctly. For example, when creating a new ticket, make sure that the Title and Description are different, and that the Priority field is set. Use common sense to assign P0 to high priority issues, down to P3 for low-priority issues. Always set the default status to "open" especially for new bugs.   
4. **Call the tools.** Once the parameters are validated, call the tool with the determined parameters.  
5. **Analyze the tools' results, and provide insights back to the user.** Return the tools' result in a human-readable format. State which tools you called, if any. If your result is 2 or more bugs, always use a markdown table to report back. If there is any code, or timestamp, in the result, format the code with markdown backticks, or codeblocks.   
6. **Ask the user if they need anything else.**

**TOOLS:**

1.  **get_current_date:**
    This tool allows you to figure out the current date (today). If a user
    asks something along the lines of "What tickets were opened in the last
    week?" you can use today's date to figure out the past week.

2.  **search-tickets**
    This tool allows you to search for similar or duplicate tickets by
    performing a vector search based on ticket descriptions. A cosine distance
    less than or equal to 0.3 can signal a similar or duplicate ticket.

3.  **update-ticket-status**
    This tool allows you to update the status of a ticket. Status can be
    one of 'Open', 'In Progress', 'Closed', 'Resolved'.

4.  **update-ticket-priority**
    This tool allows you to update the priority of a ticket. Priority can be
    one of 'P0 - Critical', 'P1 - High', 'P2 - Medium', or 'P3 - Low'.

5. **create-new-ticket**
    This tool allows you to create a new ticket/issue.

6. **get-ticket-by-id**
    This tool allows you to retrieve a ticket by its ID.

7.  **get-tickets-by-date-range**
    This tool allows you to retrieve tickets created or updated within a specific date range.

8.  **get-tickets-by-assignee**
    This tool allows you to retrieve tickets with a specific assignee.

9.  **get-tickets-by-status**
    This tool allows you to retrieve tickets with a specific status.

10.  **get-tickets-by-priority**
    This tool allows you to retrieve tickets with a specific priority.

11.  **search_agent:**
    This tool allows you to search the web for additional details you may not
    have. Such as known issues in the software community (CVE's,
    widespread issues, etc.) Only use this tool if other tools can not answer
    the user query.

12. **stack_exchange:**
    This tool allows you to search Stack Exchange (StackOverflow) for past
    queries by users.
""";

    @Bean
    public ReactAgent softwareAssistant(ChatModel chatModel,
                                        ToolCallback getCurrentDateTool,
                                        ToolCallback searchTool,
                                        ToolCallback langchainTool,
                                        ToolCallback toolboxTools,
                                        ToolCallback mcpTools) {
        return ReactAgent.builder()
                .name("software_assistant")
                .model(chatModel)
                .instruction(AGENT_INSTRUCTION)
                .tools(getCurrentDateTool, searchTool, langchainTool, toolboxTools, mcpTools)
                .build();
    }

    @Bean
    public ToolCallback getCurrentDateTool() {
        return FunctionToolCallback.builder("get_current_date", (String input) -> {
            return "{\"current_date\":\"" + LocalDate.now().toString() + "\"}";
        })
        .description("Get the current date in the format YYYY-MM-DD")
        .build();
    }

    @Bean
    public ToolCallback searchTool() {
        return FunctionToolCallback.builder("search_agent", (String query) -> {
            // Mock implementation - should use Google Search
            return "Search results for: " + query;
        })
        .description("Search the web for additional details you may not have. Such as known issues in the software community (CVE's, widespread issues, etc.) Only use this tool if other tools can not answer the user query.")
        .build();
    }

    @Bean
    public ToolCallback langchainTool() {
        return FunctionToolCallback.builder("stack_exchange", (String args) -> {
            // Mock implementation - should use StackExchange API
            return "StackExchange results";
        })
        .description("Search Stack Exchange (StackOverflow) for past queries by users.")
        .build();
    }

    @Bean
    public ToolCallback toolboxTools() {
        return FunctionToolCallback.builder("toolbox_tools", (String args) -> {
            // Mock implementation - should use MCP Toolbox for Databases
            // This should include: search-tickets, update-ticket-status, update-ticket-priority,
            // create-new-ticket, get-ticket-by-id, get-tickets-by-date-range, get-tickets-by-assignee,
            // get-tickets-by-status, get-tickets-by-priority
            return "Toolbox results";
        })
        .description("Database tools for managing bug tickets. Includes: search-tickets (vector search), update-ticket-status, update-ticket-priority, create-new-ticket, get-ticket-by-id, get-tickets-by-date-range, get-tickets-by-assignee, get-tickets-by-status, get-tickets-by-priority")
        .build();
    }

    @Bean
    public ToolCallback mcpTools() {
        return FunctionToolCallback.builder("mcp_tools", (String args) -> {
            // Mock implementation - should use GitHub MCP server
            // This should include: search_repositories, search_issues, list_issues, get_issue,
            // list_pull_requests, get_pull_request
            return "MCP results";
        })
        .description("GitHub MCP tools for accessing external repositories and issues. Includes: search_repositories, search_issues, list_issues, get_issue, list_pull_requests, get_pull_request")
        .build();
    }
}
