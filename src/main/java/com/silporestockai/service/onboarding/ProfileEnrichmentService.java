package com.silporestockai.service.onboarding;

import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.model.SilpoProfileSnapshot;
import com.silporestockai.service.CartBuildingService;
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

    /**
     * Favorites are a catalog view, so the tool wants the branch / delivery type / time slot of the guest's cart
     * (its schema marks all three required). Called with no arguments it answered «Invalid arguments» on every
     * live onboarding — a yellow line in the demo console at the one moment the console is on camera.
     */
    private static final String TOOL_FAVORITES = "silpo_get_my_favorites";

    /** The tools named by task 06. Each is called independently so one refusal does not lose the rest. */
    private static final List<String> PROFILE_TOOLS = List.of(
            "silpo_get_my_family", "silpo_get_my_food_restrictions", "silpo_get_my_online_orders", TOOL_FAVORITES);

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
    private final CartBuildingService cartBuildingService;

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
            return sanitize(
                    claudeApiClient.completeStructured(EXTRACTION_PROMPT, gathered, SilpoProfileSnapshot.class));
        } catch (RuntimeException e) {
            log.warn("could not normalise the Silpo profile for user {}: {}", userId, e.getMessage());
            return SilpoProfileSnapshot.empty();
        }
    }

    /**
     * Structured output still emits a numeric default sometimes despite the prompt saying to leave an unknown field
     * empty, and a household of zero people does not exist. Clamping it to null here, once, at the boundary where
     * untrusted model output enters the system, is what keeps {@link SilpoProfileSnapshot#isEmpty()} honest — a
     * snapshot with nothing usable in it must report itself as empty, or the onboarding confirmation screen ends up
     * blank instead of falling back to asking.
     */
    private static SilpoProfileSnapshot sanitize(SilpoProfileSnapshot snapshot) {
        if (snapshot.householdSize() != null && snapshot.householdSize() <= 0) {
            return new SilpoProfileSnapshot(
                    null,
                    snapshot.hasKids(),
                    snapshot.kidsAges(),
                    snapshot.dietaryRestrictions(),
                    snapshot.frequentItems());
        }
        return snapshot;
    }

    private List<String> collectToolOutput(UUID userId) {
        List<String> gathered = new ArrayList<>();
        for (String tool : PROFILE_TOOLS) {
            try {
                Map<String, Object> arguments = TOOL_FAVORITES.equals(tool) ? favoritesArguments(userId) : Map.of();
                if (arguments == null) {
                    continue;
                }
                McpToolResponse response = silpoMcpClient.callTool(tool, arguments, userId);
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

    /**
     * The cart context behind the favorites call, or null when this guest has no cart to scope it by (no saved
     * delivery address yet, Silpo down) — in which case the tool is skipped rather than called with arguments it
     * would refuse. Nothing here may throw: enrichment has one fallback path, the empty snapshot.
     */
    private Map<String, Object> favoritesArguments(UUID userId) {
        try {
            return cartBuildingService.getOrCreateCartContext(userId).catalogArguments();
        } catch (RuntimeException e) {
            log.info("no cart context for user {}; skipping {}: {}", userId, TOOL_FAVORITES, e.getMessage());
            return null;
        }
    }
}
