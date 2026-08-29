package com.silporestockai.service.onboarding;

import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.model.SilpoProfileSnapshot;
import com.silporestockai.service.SilpoAuthService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Learns what it can about a household from their Silpo account, so onboarding asks as few questions as possible.
 *
 * <p>Nothing here throws. A guest who never connected, a guest with no order history, a tool that is not granted, an
 * MCP outage and a Claude failure all produce an empty snapshot, so the flow has exactly one fallback path to maintain
 * instead of five.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileEnrichmentService {

    /** The tools named by task 06. Each is called independently so one refusal does not lose the rest. */
    private static final List<String> PROFILE_TOOLS = List.of(
            "silpo_get_my_family",
            "silpo_get_my_food_restrictions",
            "silpo_get_my_online_orders",
            "silpo_get_my_favorites");

    private static final String EXTRACTION_PROMPT = """
            Ти отримуєш сирі відповіді інструментів профілю «Сільпо» для одного клієнта.
            Витягни з них лише те, що там справді є. Нічого не вигадуй.
            Якщо якогось значення в даних немає — залиш поле порожнім.
            kidsAges — вік дітей числами. dietaryRestrictions — алергії та дієтичні обмеження.
            frequentItems — назви товарів, які клієнт купує регулярно.
            """;

    private final SilpoAuthService silpoAuthService;
    private final SilpoMcpClient silpoMcpClient;
    private final ClaudeApiClient claudeApiClient;

    public SilpoProfileSnapshot enrich(UUID userId) {
        if (!silpoAuthService.isConnected(userId)) {
            log.debug("user {} has not connected Silpo; skipping enrichment", userId);
            return SilpoProfileSnapshot.empty();
        }

        String gathered = String.join("\n\n", collectToolOutput(userId));
        if (gathered.isBlank()) {
            log.info("Silpo returned nothing usable for user {}; onboarding will ask instead", userId);
            return SilpoProfileSnapshot.empty();
        }

        try {
            return claudeApiClient.completeStructured(EXTRACTION_PROMPT, gathered, SilpoProfileSnapshot.class);
        } catch (RuntimeException e) {
            log.warn("could not normalise the Silpo profile for user {}: {}", userId, e.getMessage());
            return SilpoProfileSnapshot.empty();
        }
    }

    private List<String> collectToolOutput(UUID userId) {
        List<String> gathered = new ArrayList<>();
        for (String tool : PROFILE_TOOLS) {
            try {
                McpToolResponse response = silpoMcpClient.callTool(tool, Map.of(), userId);
                if (response.isError()
                        || response.text() == null
                        || response.text().isBlank()) {
                    continue;
                }
                gathered.add(tool + ": " + response.text());
            } catch (RuntimeException e) {
                // A 403 means this guest has not granted the tool; the others may still answer.
                log.info("Silpo tool {} unavailable for user {}: {}", tool, userId, e.getMessage());
            }
        }
        return gathered;
    }
}
