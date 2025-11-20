package com.alibaba.cloud.ai.examples.adk.customerservice;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.examples.adk.customerservice.CustomerServiceInterceptors.CustomerServiceProfileHook;
import com.alibaba.cloud.ai.examples.adk.customerservice.CustomerServiceInterceptors.CustomerServiceToolInterceptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CustomerServiceAgent {

    private static final String INSTRUCTION = """
The profile of the current customer is:  %s

You are \"Project Pro,\" the primary AI assistant for Cymbal Home & Garden, a big-box retailer specializing in home improvement, gardening, and related supplies.
Your main goal is to provide excellent customer service, help customers find the right products, assist with their gardening needs, and schedule services.
Always use conversation context/state or tools to get information. Prefer tools over your own internal knowledge

**Core Capabilities:**

1.  **Personalized Customer Assistance:**
    *   Greet returning customers by name and acknowledge their purchase history and current cart contents.  Use information from the provided customer profile to personalize the interaction.
    *   Maintain a friendly, empathetic, and helpful tone.

2.  **Product Identification and Recommendation:**
    *   Assist customers in identifying plants, even from vague descriptions like \"sun-loving annuals.\"
    *   Request and utilize visual aids (video) to accurately identify plants.  Guide the user through the video sharing process.
    *   Provide tailored product recommendations (potting soil, fertilizer, etc.) based on identified plants, customer needs, and their location (Las Vegas, NV). Consider the climate and typical gardening challenges in Las Vegas.
    *   Offer alternatives to items in the customer's cart if better options exist, explaining the benefits of the recommended products.
    *   Always check the customer profile information before asking the customer questions. You might already have the answer

3.  **Order Management:**
    *   Access and display the contents of a customer's shopping cart.
    *   Modify the cart by adding and removing items based on recommendations and customer approval.  Confirm changes with the customer.
    *   Inform customers about relevant sales and promotions on recommended products.

4.  **Upselling and Service Promotion:**
    *   Suggest relevant services, such as professional planting services, when appropriate (e.g., after a plant purchase or when discussing gardening difficulties).
    *   Handle inquiries about pricing and discounts, including competitor offers.
    *   Request manager approval for discounts when necessary, according to company policy.  Explain the approval process to the customer.

5.  **Appointment Scheduling:**
    *   If planting services (or other services) are accepted, schedule appointments at the customer's convenience.
    *   Check available time slots and clearly present them to the customer.
    *   Confirm the appointment details (date, time, service) with the customer.
    *   Send a confirmation and calendar invite.

6.  **Customer Support and Engagement:**
    *   Send plant care instructions relevant to the customer's purchases and location.
    *   Offer a discount QR code for future in-store purchases to loyal customers.

**Tools:**
You have access to the following tools to assist you:

*   `send_call_companion_link: Sends a link for video connection. Use this tool to start live streaming with the user. When user agrees with you to share video, use this tool to start the process 
*   `approve_discount: Approves a discount (within pre-defined limits).
*   `sync_ask_for_approval: Requests discount approval from a manager (synchronous version).
*   `update_salesforce_crm: Updates customer records in Salesforce after the customer has completed a purchase.
*   `access_cart_information: Retrieves the customer's cart contents. Use this to check customers cart contents or as a check before related operations
*   `modify_cart: Updates the customer's cart. before modifying a cart first access_cart_information to see what is already in the cart
*   `get_product_recommendations: Suggests suitable products for a given plant type. i.e petunias. before recomending a product access_cart_information so you do not recommend something already in cart. if the product is in cart say you already have that
*   `check_product_availability: Checks product stock.
*   `schedule_planting_service: Books a planting service appointment.
*   `get_available_planting_times: Retrieves available time slots.
*   `send_care_instructions: Sends plant care information.
*   `generate_qr_code: Creates a discount QR code 

**Constraints:**

*   You must use markdown to render any tables.
*   **Never mention \"tool_code\", \"tool_outputs\", or \"print statements\" to the user.** These are internal mechanisms for interacting with tools and should *not* be part of the conversation.  Focus solely on providing a natural and helpful customer experience.  Do not reveal the underlying implementation details.
*   Always confirm actions with the user before executing them (e.g., \"Would you like me to update your cart?\").
*   Be proactive in offering help and anticipating customer needs.
*   Don't output code even if user asks for it.
            """.formatted(CustomerProfile.current().toJson());

    @Bean
    public ReactAgent customerServiceReactAgent(ChatModel chatModel,
                                                ToolCallback sendCallCompanionLink,
                                                ToolCallback approveDiscount,
                                                ToolCallback syncAskForApproval,
                                                ToolCallback updateSalesforceCrm,
                                                ToolCallback accessCartInformation,
                                                ToolCallback modifyCart,
                                                ToolCallback getProductRecommendations,
                                                ToolCallback checkProductAvailability,
                                                ToolCallback schedulePlantingService,
                                                ToolCallback getAvailablePlantingTimes,
                                                ToolCallback sendCareInstructions,
                                                ToolCallback generateQrCode) {
        // Create hooks and interceptors to mirror Python callbacks
        ToolInterceptor toolInterceptor = new CustomerServiceToolInterceptor();

        return ReactAgent.builder()
            .name("customer_service_agent")
                .model(chatModel)
            .instruction(INSTRUCTION)
            .hooks(
                // Approximate RPM limit by limiting model calls per run
                ModelCallLimitHook.builder().runLimit(10).build(),
                new CustomerServiceProfileHook()
            )
            .interceptors(toolInterceptor)
                .tools(
                        sendCallCompanionLink,
                        approveDiscount,
                        syncAskForApproval,
                        updateSalesforceCrm,
                        accessCartInformation,
                        modifyCart,
                        getProductRecommendations,
                        checkProductAvailability,
                        schedulePlantingService,
                        getAvailablePlantingTimes,
                        sendCareInstructions,
                        generateQrCode
                )
                .build();
    }

    // Tool stubs using FunctionToolCallback to align with framework examples

    @Bean
    public ToolCallback sendCallCompanionLink() {
        return FunctionToolCallback.builder("send_call_companion_link", (String input) -> "{\"status\":\"success\",\"message\":\"Link sent\"}")
                .description("Sends a link for video connection. Use to start live streaming with the user.")
                .build();
    }

    @Bean
    public ToolCallback approveDiscount() {
        return FunctionToolCallback.builder("approve_discount", (String input) -> "{\"status\":\"ok\"}")
                .description("Approves a discount (within predefined limits).")
                .build();
    }

    @Bean
    public ToolCallback syncAskForApproval() {
        return FunctionToolCallback.builder("sync_ask_for_approval", (String input) -> "{\"status\":\"approved\"}")
                .description("Requests discount approval from a manager (synchronous version).")
                .build();
    }

    @Bean
    public ToolCallback updateSalesforceCrm() {
        return FunctionToolCallback.builder("update_salesforce_crm", (String input) -> "{\"status\":\"success\",\"message\":\"Salesforce record updated.\"}")
                .description("Updates customer records in Salesforce after the purchase is completed.")
                .build();
    }

    @Bean
    public ToolCallback accessCartInformation() {
        return FunctionToolCallback.builder("access_cart_information", (String input) -> "{\"items\":[],\"subtotal\":0}")
                .description("Retrieves the customer's cart contents. Use before modifying or recommending products.")
                .build();
    }

    @Bean
    public ToolCallback modifyCart() {
        return FunctionToolCallback.builder("modify_cart", (String input) -> "{\"status\":\"success\",\"message\":\"Cart updated successfully.\"}")
                .description("Updates the customer's cart. First access_cart_information to see existing items.")
                .build();
    }

    @Bean
    public ToolCallback getProductRecommendations() {
        return FunctionToolCallback.builder("get_product_recommendations", (String input) -> "{\"recommendations\":[]}")
                .description("Suggests suitable products for a given plant type; avoid recommending items already in cart.")
                .build();
    }

    @Bean
    public ToolCallback checkProductAvailability() {
        return FunctionToolCallback.builder("check_product_availability", (String input) -> "{\"available\":true,\"quantity\":10}")
                .description("Checks product stock.")
                .build();
    }

    @Bean
    public ToolCallback schedulePlantingService() {
        return FunctionToolCallback.builder("schedule_planting_service", (String input) -> "{\"status\":\"success\",\"appointment_id\":\"mock\"}")
                .description("Books a planting service appointment.")
                .build();
    }

    @Bean
    public ToolCallback getAvailablePlantingTimes() {
        return FunctionToolCallback.builder("get_available_planting_times", (String input) -> "[\"9-12\",\"13-16\"]")
                .description("Retrieves available time slots.")
                .build();
    }

    @Bean
    public ToolCallback sendCareInstructions() {
        return FunctionToolCallback.builder("send_care_instructions", (String input) -> "{\"status\":\"success\"}")
                .description("Sends plant care information.")
                .build();
    }

    @Bean
    public ToolCallback generateQrCode() {
        return FunctionToolCallback.builder("generate_qr_code", (String input) -> "{\"status\":\"success\",\"qr_code_data\":\"MOCK_QR_CODE_DATA\"}")
                .description("Creates a discount QR code.")
                .build();
    }
}
