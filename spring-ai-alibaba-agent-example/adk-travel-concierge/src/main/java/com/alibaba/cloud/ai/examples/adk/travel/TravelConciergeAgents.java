package com.alibaba.cloud.ai.examples.adk.travel;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.agent.AgentTool;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.serializer.AgentInstructionMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Configuration
public class TravelConciergeAgents {

    // Root agent instruction (ROOT_AGENT_INSTR)
    private static final String ROOT_AGENT_INSTR = """
- You are a exclusive travel conceirge agent
- You help users to discover their dream vacation, planning for the vacation, book flights and hotels
- You want to gather a minimal information to help the user
- After every tool call, pretend you're showing the result to the user and keep your response limited to a phrase.
- Please use only the agents and tools to fulfill all user rquest
- If the user asks about general knowledge, vacation inspiration or things to do, transfer to the agent `inspiration_agent`
- If the user asks about finding flight deals, making seat selection, or lodging, transfer to the agent `planning_agent`
- If the user is ready to make the flight booking or process payments, transfer to the agent `booking_agent`
- Please use the context info below for any user preferences

Current user:
  <user_profile>
  {user_profile}
  </user_profile>

Current time: {_time}

Trip phases:
If we have a non-empty itinerary, follow the following logic to deteermine a Trip phase:
- First focus on the start_date "{itinerary_start_date}" and the end_date "{itinerary_end_date}" of the itinerary.
- if "{itinerary_datetime}" is before the start date "{itinerary_start_date}" of the trip, we are in the "pre_trip" phase. 
- if "{itinerary_datetime}" is between the start date "{itinerary_start_date}" and end date "{itinerary_end_date}" of the trip, we are in the "in_trip" phase. 
- When we are in the "in_trip" phase, the "{itinerary_datetime}" dictates if we have "day_of" matters to handle.
- if "{itinerary_datetime}" is after the end date of the trip, we are in the "post_trip" phase. 

<itinerary>
{itinerary}
</itinerary>

Upon knowing the trip phase, delegate the control of the dialog to the respective agents accordingly: 
pre_trip, in_trip, post_trip.
""";

    // Inspiration agent prompts
    private static final String INSPIRATION_AGENT_INSTR = """
You are travel inspiration agent who help users find their next big dream vacation destinations.
Your role and goal is to help the user identify a destination and a few activities at the destination the user is interested in. 

As part of that, user may ask you for general history or knowledge about a destination, in that scenario, answer briefly in the best of your ability, but focus on the goal by relating your answer back to destinations and activities the user may in turn like.
- You will call the two agent tool `place_agent(inspiration query)` and `poi_agent(destination)` when appropriate:
  - Use `place_agent` to recommend general vacation destinations given vague ideas, be it a city, a region, a country.
  - Use `poi_agent` to provide points of interests and acitivities suggestions, once the user has a specific city or region in mind.
  - Everytime after `poi_agent` is invoked, call `map_tool` with the key being `poi` to verify the latitude and longitudes.
- Avoid asking too many questions. When user gives instructions like "inspire me", or "suggest some", just go ahead and call `place_agent`.
- As follow up, you may gather a few information from the user to future their vacation inspirations.
- Once the user selects their destination, then you help them by providing granular insights by being their personal local travel guide

- Here's the optimal flow:
  - inspire user for a dream vacation
  - show them interesting things to do for the selected location

- Your role is only to identify possible destinations and acitivites. 
- Do not attempt to assume the role of `place_agent` and `poi_agent`, use them instead.
- Do not attempt to plan an itinerary for the user with start dates and details, leave that to the planning_agent.
- Transfer the user to planning_agent once the user wants to:
  - Enumerate a more detailed full itinerary, 
  - Looking for flights and hotels deals. 

- Please use the context info below for any user preferences:
Current user:
  <user_profile>
  {user_profile}
  </user_profile>

Current time: {_time}
""";

    private static final String PLACE_AGENT_INSTR = """
You are responsible for make suggestions on vacation inspirations and recommendations based on the user's query. Limit the choices to 3 results.
Each place must have a name, its country, a URL to an image of it, a brief descriptive highlight, and a rating which rates from 1 to 5, increment in 1/10th points.

Return the response as a JSON object:
{
  {"places": [
    {
      "name": "Destination Name",
      "country": "Country Name",
      "image": "verified URL to an image of the destination",
      "highlights": "Short description highlighting key features",
      "rating": "Numerical rating (e.g., 4.5)"
    }
  ]}
}
""";

    private static final String POI_AGENT_INSTR = """
You are responsible for providing a list of point of interests, things to do recommendations based on the user's destination choice. Limit the choices to 5 results.

Return the response as a JSON object:
{
 "places": [
    {
      "place_name": "Name of the attraction",
      "address": "An address or sufficient information to geocode for a Lat/Lon",
      "lat": "Latitude e.g., 20.6843",
      "long": "Longitude e.g., -88.5678",
      "review_ratings": "Rating e.g. 4.8",
      "highlights": "Short description",
      "image_url": "verified URL",
      "map_url":  "",
      "place_id": ""
    }
  ]
}
""";

    // Planning agent prompts (abbreviated due to length but preserving logic triggers)
    private static final String PLANNING_AGENT_INSTR = """
You are a travel planning agent who help users finding best deals for flights, hotels, and constructs full itineraries for their vacation. 
You do not handle any bookings. Booking and payments are handled by `booking_agent`.
Support user journeys: flights only, hotels only, flights+hotels without itinerary, full itinerary, autonomous planning.
Tools available: flight_search_agent, flight_seat_selection_agent, hotel_search_agent, hotel_room_selection_agent, itinerary_agent, memorize.
Follow FULL_ITINERARY, FIND_FLIGHTS, FIND_HOTELS, CREATE_ITINERARY embedded instructions and store selections using memorize.
Once journey complete and user confirms, transfer to booking_agent.
""";

    private static final String FLIGHT_SEARCH_INSTR = """
Generate flight search results (max 4). Require origin & destination. Return JSON {"flights": [...]} including flight_number, departure/arrival, airlines, price_in_usd, number_of_stops.
""";

    private static final String FLIGHT_SEAT_SELECTION_INSTR = """
Simulate available seats for a given flight_number. Return JSON {"seats": [[{ "seat_number": "1A", "is_available": true, "price_in_usd": 60 }]]}.
""";

    private static final String HOTEL_SEARCH_INSTR = """
Generate hotel search results (max 4). Require destination. Return JSON {"hotels": [...] } with name, address, check_in_time, check_out_time, price.
""";

    private static final String HOTEL_ROOM_SELECTION_INSTR = """
Simulate available rooms for chosen hotel. Return JSON {"rooms": [{"room_type":"Queen with Balcony","is_available":true,"price_in_usd":260}]}.
""";

    private static final String ITINERARY_AGENT_INSTR = """
Generate structured itinerary JSON capturing trip metadata and daily events (flight, hotel, visit). Ensure times are HH:MM and use empty strings for missing values.
""";

    // Booking agent prompts
    private static final String BOOKING_AGENT_INSTR = """
You are the booking agent processing reservations and payments for flights, hotels, and events. Use create_reservation -> payment_choice -> process_payment for each bookable item.
""";

    private static final String CONFIRM_RESERVATION_INSTR = """
Create reservation with price and unique reservation_id. Ask if user wants to proceed to payment.
""";

    private static final String PAYMENT_CHOICE_INSTR = """
Provide payment choices: Apple Pay, Google Pay, Credit Card. Await user selection (reuse prior if given).
""";

    private static final String PROCESS_PAYMENT_INSTR = """
Process payment. Scenario: Apple Pay declines, Google Pay approves, Credit Card approves. Return order id.
""";

    // Pre-trip agent prompts
    private static final String PRETRIP_AGENT_INSTR = """
Pre-trip assistant: given itinerary & profile, can update visa, medical, storm_monitor, travel_advisory via google_search_grounding then call what_to_pack. Summarize results.
""";

    private static final String WHAT_TO_PACK_INSTR = """
Suggest packing list in JSON array: ["walking shoes","fleece","umbrella"].
""";

    // In-trip agent prompts
    private static final String INTRIP_INSTR = """
In-trip concierge: monitor itinerary (command 'monitor'), logistics ('transport'), or memorize datetime ('memorize').
""";

    private static final String TRIP_MONITOR_INSTR = """
Inspect itinerary events needing attention: flights (delays), bookings, weather impacts. Summarize suggested changes.
""";

    // Post-trip agent prompt
    private static final String POSTTRIP_INSTR = """
Post-trip assistant: ask about experiences, extract preferences & reviews, store via memorize, thank user.
""";

    // Memory tools (simplified state mutation simulation)
    private ToolCallback memorize = FunctionToolCallback.builder("memorize", (String input) -> {
        Map<String, Object> map = parseKeyValue(input);
        return statusJson("Stored \"" + map.getOrDefault("key", "") + "\": \"" + map.getOrDefault("value", "") + "\"");
    }).description("Store a single key/value preference or selection. Input JSON: {key, value}").build();

    private ToolCallback memorizeList = FunctionToolCallback.builder("memorize_list", (String input) -> {
        Map<String, Object> map = parseKeyValue(input);
        return statusJson("Appended \"" + map.getOrDefault("value", "") + "\" to list \"" + map.getOrDefault("key", "") + "\"");
    }).description("Append a value to a list memory. Input JSON: {key, value}").build();

    private ToolCallback forget = FunctionToolCallback.builder("forget", (String input) -> {
        Map<String, Object> map = parseKeyValue(input);
        return statusJson("Removed value from list: \"" + map.getOrDefault("key", "") + "\"");
    }).description("Remove a value from a list memory. Input JSON: {key, value}").build();

    // Map tool (verification stub)
    private ToolCallback mapTool = FunctionToolCallback.builder("map_tool", (String input) -> {
        return "{\"status\":\"verified locations\",\"timestamp\":\"" + OffsetDateTime.now() + "\"}";
    }).description("Verifies latitude/longitude of POI results. Input: key name or JSON referencing 'poi'.").build();

    // Google search grounding (pre-trip)
    private ToolCallback googleSearchGrounding = FunctionToolCallback.builder("google_search_grounding", (String input) -> {
        String topic = input == null ? "" : input.trim();
        return "{\"topic\":\"" + topic + "\",\"results\":[{\"title\":\"Sample \"}]}";
    }).description("Ground external info for topics: visa_requirements, medical_requirements, storm_monitor, travel_advisory.").build();

    // Trip monitor tools
    private ToolCallback flightStatusCheck = FunctionToolCallback.builder("flight_status_check", (String input) -> "{\"status\":\"on_time\"}")
            .description("Check flight status for delays or cancellations. Input: flight_number.").build();
    private ToolCallback eventBookingCheck = FunctionToolCallback.builder("event_booking_check", (String input) -> "{\"status\":\"confirmed\"}")
            .description("Check status of event booking. Input: event_id or description.").build();
    private ToolCallback weatherImpactCheck = FunctionToolCallback.builder("weather_impact_check", (String input) -> "{\"impact\":\"none\"}")
            .description("Check weather impact for outdoor activity. Input: location/date.").build();

    // Logistics/day-of instruction stub (transit_coordination)
    private static final String DAY_OF_INSTR = """
Handle logistics to get traveler from point A to B considering time constraints and buffer for airports.
""";

    // Packing suggestion agent
    private ReactAgent whatToPackAgent(ChatModel model) {
        return ReactAgent.builder().name("what_to_pack_agent").model(model).instruction(WHAT_TO_PACK_INSTR).outputKey("what_to_pack").build();
    }

    // Initial state loading hook (equivalent to _load_precreated_itinerary)
    @HookPositions(HookPosition.BEFORE_AGENT)
    public static class InitialStateHook extends AgentHook {
        @Override
        public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
            String scenario = System.getenv().getOrDefault("TRAVEL_CONCIERGE_SCENARIO", "travel_concierge/profiles/itinerary_empty_default.json");
            Path p = Path.of(scenario);
            if (Files.exists(p)) {
                try {
                    String json = Files.readString(p);
                    Map<String, Object> updates = new HashMap<>();
                    updates.put("itinerary", json);
                    updates.put("_time", OffsetDateTime.now().toString());
                    return CompletableFuture.completedFuture(updates);
                } catch (IOException ignored) { }
            }
            return CompletableFuture.completedFuture(Map.of("_time", OffsetDateTime.now().toString()));
        }

        @Override
        public String getName() { return "InitialStateHook"; }
        @Override
        public List<JumpTo> canJumpTo() { return List.of(); }
    }

    // RootContextHook: inject ROOT_AGENT_INSTR and ensure time/itinerary placeholders
    @HookPositions(HookPosition.BEFORE_AGENT)
    public static class RootContextHook extends AgentHook {
        @Override
        public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
            Map<String, Object> updates = new HashMap<>();

            // One-time initial state load from scenario file (parity with Python _load_precreated_itinerary)
            boolean initialized = state.value("_itin_initialized", Boolean.class).orElse(false);
            if (!initialized) {
                String envPath = System.getenv("TRAVEL_CONCIERGE_SCENARIO");
                List<String> candidates = new ArrayList<>();
                if (envPath != null && !envPath.isBlank()) {
                    candidates.add(envPath);
                }
                // Common repo-relative fallbacks
                candidates.add("adksamples/travel-concierge/travel_concierge/profiles/itinerary_empty_default.json");
                candidates.add("travel_concierge/profiles/itinerary_empty_default.json");

                for (String cand : candidates) {
                    try {
                        Path p = Path.of(cand);
                        if (Files.exists(p)) {
                            String content = Files.readString(p);
                            // Best-effort extraction without adding JSON deps
                            String userProfile = extractJsonObject(content, "user_profile");
                            String itinerary = extractJsonObject(content, "itinerary");
                            String itinStart = extractJsonString(content, "itinerary_start_date");
                            String itinEnd = extractJsonString(content, "itinerary_end_date");
                            String itinDatetime = extractJsonString(content, "itinerary_datetime");

                            if (userProfile != null) updates.put("user_profile", userProfile);
                            if (itinerary != null) updates.put("itinerary", itinerary);
                            if (itinStart != null) updates.put("start_date", itinStart);
                            if (itinEnd != null) updates.put("end_date", itinEnd);
                            if (itinDatetime != null && !itinDatetime.isBlank()) {
                                updates.put("itinerary_datetime", itinDatetime);
                            } else if (itinStart != null) {
                                updates.put("itinerary_datetime", itinStart);
                            }
                            break; // stop after first successful load
                        }
                    } catch (IOException ignored) { }
                }

                updates.put("_itin_initialized", true);
            }

            // Gather dynamic values
            String userProfile = Objects.toString(updates.getOrDefault("user_profile", state.value("user_profile").orElse("")));
            String timeNow = OffsetDateTime.now().toString();
            String time = Objects.toString(state.value("_time").orElse(timeNow));
            updates.put("_time", time);
            String itinerary = Objects.toString(updates.getOrDefault("itinerary", state.value("itinerary").orElse("")));
            String startDate = Objects.toString(updates.getOrDefault("start_date", state.value("start_date").orElse("")));
            String endDate = Objects.toString(updates.getOrDefault("end_date", state.value("end_date").orElse("")));
            String itineraryDatetime = Objects.toString(updates.getOrDefault("itinerary_datetime", state.value("itinerary_datetime").orElse(startDate)));

            String filled = ROOT_AGENT_INSTR
                    .replace("{user_profile}", userProfile)
                    .replace("{_time}", time)
                    .replace("{itinerary}", itinerary)
                    .replace("{itinerary_start_date}", startDate)
                    .replace("{itinerary_end_date}", endDate)
                    .replace("{itinerary_datetime}", itineraryDatetime);

            // Avoid duplicate instruction injection
            Optional<List> msgsOpt = state.value("messages", List.class);
            if (msgsOpt.isPresent()) {
                @SuppressWarnings("unchecked")
                List<org.springframework.ai.chat.messages.Message> msgs = (List<org.springframework.ai.chat.messages.Message>) msgsOpt.get();
                boolean exists = msgs.stream().anyMatch(m -> m instanceof AgentInstructionMessage aim && Objects.equals(aim.getText(), filled));
                if (exists) {
                    // Still return any state updates gathered above (time, initial state)
                    return CompletableFuture.completedFuture(updates.isEmpty() ? Map.of() : updates);
                }
            }

            // Provide instruction message plus any state updates
            if (!updates.isEmpty()) {
                updates.put("messages", List.of(new AgentInstructionMessage(filled)));
                return CompletableFuture.completedFuture(updates);
            }
            return CompletableFuture.completedFuture(Map.of("messages", List.of(new AgentInstructionMessage(filled))));
        }

        @Override
        public String getName() { return "RootContextHook"; }

        @Override
        public List<JumpTo> canJumpTo() { return List.of(); }

    }

    private static String extractJsonObject(String content, String key) {
        // naive, non-nested balanced brace extraction for a top-level object in the file
        int idx = content.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        int colon = content.indexOf(":", idx);
        if (colon < 0) return null;
        int start = content.indexOf("{", colon);
        if (start < 0) return null;
        int depth = 0;
        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return content.substring(start, i + 1).trim();
                }
            }
        }
        return null;
    }

    private static String extractJsonString(String content, String key) {
        String pattern = "\"" + key + "\"";
        int idx = content.indexOf(pattern);
        if (idx < 0) return null;
        int colon = content.indexOf(":", idx);
        if (colon < 0) return null;
        int quote1 = content.indexOf('"', colon);
        if (quote1 < 0) return null;
        int quote2 = content.indexOf('"', quote1 + 1);
        if (quote2 < 0) return null;
        return content.substring(quote1 + 1, quote2);
    }

    private static Map<String, Object> parseKeyValue(String input) {
        Map<String, Object> map = new HashMap<>();
        if (input == null) return map;
        // naive JSON parsing
        try {
            String s = input.trim();
            if (s.startsWith("{") && s.endsWith("}")) {
                s = s.substring(1, s.length()-1);
                for (String part : s.split(",")) {
                    String[] kv = part.split(":",2);
                    if (kv.length==2) {
                        String k = kv[0].replaceAll("[\"{} ]","" );
                        String v = kv[1].replaceAll("[\"{} ]","" );
                        map.put(k, v);
                    }
                }
            }
        } catch (Exception ignored) { }
        return map;
    }

    private static String statusJson(String msg) {
        return "{\"status\":\"" + msg.replace("\"","'") + "\"}";
    }

    @Bean
    public SequentialAgent travelConcierge(ChatModel chatModel) {
        // Inspiration sub-agents
        ReactAgent placeAgent = ReactAgent.builder().name("place_agent").model(chatModel).instruction(PLACE_AGENT_INSTR).outputKey("place").build();
        ReactAgent poiAgent = ReactAgent.builder().name("poi_agent").model(chatModel).instruction(POI_AGENT_INSTR).outputKey("poi").build();
        RootContextHook rootHook = new RootContextHook();

        ReactAgent inspirationAgent = ReactAgent.builder()
            .name("inspiration_agent")
            .description("A travel inspiration agent who inspire users, and discover their next vacations; Provide information about places, activities, interests,")
            .model(chatModel)
            .instruction(INSPIRATION_AGENT_INSTR)
            .hooks(rootHook)
            .tools(AgentTool.getFunctionToolCallback(placeAgent), AgentTool.getFunctionToolCallback(poiAgent), mapTool)
            .build();

        // Planning sub-agents
        ReactAgent flightSearchAgent = ReactAgent.builder().name("flight_search_agent").model(chatModel).instruction(FLIGHT_SEARCH_INSTR).outputKey("flight").build();
        ReactAgent flightSeatSelectionAgent = ReactAgent.builder().name("flight_seat_selection_agent").model(chatModel).instruction(FLIGHT_SEAT_SELECTION_INSTR).outputKey("seat").build();
        ReactAgent hotelSearchAgent = ReactAgent.builder().name("hotel_search_agent").model(chatModel).instruction(HOTEL_SEARCH_INSTR).outputKey("hotel").build();
        ReactAgent hotelRoomSelectionAgent = ReactAgent.builder().name("hotel_room_selection_agent").model(chatModel).instruction(HOTEL_ROOM_SELECTION_INSTR).outputKey("room").build();
        ReactAgent itineraryAgent = ReactAgent.builder().name("itinerary_agent").model(chatModel).instruction(ITINERARY_AGENT_INSTR).outputKey("itinerary").build();
        ReactAgent planningAgent = ReactAgent.builder()
            .name("planning_agent")
            .description("Helps users with travel planning, complete a full itinerary for their vacation, finding best deals for flights and hotels.")
            .model(chatModel)
            .instruction(PLANNING_AGENT_INSTR)
            .hooks(rootHook)
            .tools(AgentTool.getFunctionToolCallback(flightSearchAgent), AgentTool.getFunctionToolCallback(flightSeatSelectionAgent), AgentTool.getFunctionToolCallback(hotelSearchAgent), AgentTool.getFunctionToolCallback(hotelRoomSelectionAgent), AgentTool.getFunctionToolCallback(itineraryAgent), memorize)
            .build();

        // Booking sub-agents
        ReactAgent createReservationAgent = ReactAgent.builder().name("create_reservation").model(chatModel).instruction(CONFIRM_RESERVATION_INSTR).build();
        ReactAgent paymentChoiceAgent = ReactAgent.builder().name("payment_choice").model(chatModel).instruction(PAYMENT_CHOICE_INSTR).build();
        ReactAgent processPaymentAgent = ReactAgent.builder().name("process_payment").model(chatModel).instruction(PROCESS_PAYMENT_INSTR).build();
        ReactAgent bookingAgent = ReactAgent.builder()
            .name("booking_agent")
            .description("Given an itinerary, complete the bookings of items by handling payment choices and processing.")
            .model(chatModel)
            .instruction(BOOKING_AGENT_INSTR)
            .hooks(rootHook)
            .tools(AgentTool.getFunctionToolCallback(createReservationAgent), AgentTool.getFunctionToolCallback(paymentChoiceAgent), AgentTool.getFunctionToolCallback(processPaymentAgent))
            .build();

        // Pre-trip agent
        ReactAgent whatToPack = whatToPackAgent(chatModel);
        ReactAgent preTripAgent = ReactAgent.builder()
            .name("pre_trip_agent")
            .description("Given an itinerary, this agent keeps up to date and provides relevant travel information to the user before the trip.")
            .model(chatModel)
            .instruction(PRETRIP_AGENT_INSTR)
            .hooks(rootHook)
            .tools(googleSearchGrounding, AgentTool.getFunctionToolCallback(whatToPack))
            .build();

        // In-trip agents
        ReactAgent dayOfAgent = ReactAgent.builder().name("day_of_agent").model(chatModel).instruction(DAY_OF_INSTR).build();
        ReactAgent tripMonitorAgent = ReactAgent.builder().name("trip_monitor_agent").model(chatModel).instruction(TRIP_MONITOR_INSTR)
                .tools(flightStatusCheck, eventBookingCheck, weatherImpactCheck).outputKey("daily_checks").build();
        ReactAgent inTripAgent = ReactAgent.builder()
            .name("in_trip_agent")
            .description("Provide information about what the users need as part of the tour.")
            .model(chatModel)
            .instruction(INTRIP_INSTR)
            .hooks(rootHook)
            .tools(AgentTool.getFunctionToolCallback(tripMonitorAgent), AgentTool.getFunctionToolCallback(dayOfAgent), memorize)
            .build();

        // Post-trip agent
        ReactAgent postTripAgent = ReactAgent.builder()
            .name("post_trip_agent")
            .description("A follow up agent to learn from user's experience; In turn improves the user's future trips planning and in-trip experience.")
            .model(chatModel)
            .instruction(POSTTRIP_INSTR)
            .hooks(rootHook)
            .tools(memorize, memorizeList, forget)
            .build();

        try {
                return SequentialAgent.builder()
                        .name("travel_concierge")
                        .description("A Travel Conceirge using the services of multiple sub-agents")
                        .subAgents(List.of(inspirationAgent, planningAgent, bookingAgent, preTripAgent, inTripAgent, postTripAgent))
                        .build();
        } catch (GraphStateException e) {
            throw new RuntimeException(e);
        }
    }
}
