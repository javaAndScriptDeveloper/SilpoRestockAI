package com.silporestockai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.model.DishIngredients;
import com.silporestockai.model.OrderType;
import com.silporestockai.model.PlannedIngredient;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * "Хочу приготувати оце — замов усе для цього" (task 36): the ingredients of one dish, as an ad-hoc cart.
 *
 * <p>Two model calls and nothing new downstream. {@link #orderIngredients} asks for a single dish's shopping
 * list — generic ingredient names, household-sized quantities, the exact shape task 09's name search already
 * resolves — and hands it to the ordinary cart confirmation as {@link OrderType#AD_HOC}, so unresolved lines
 * are reported the usual way and the baseline is never touched. {@link #identifyDish} reads a photo of a
 * plated dish and returns a name for the person to confirm; it is approximate by nature and says so through a
 * confidence the caller checks before trusting it.
 *
 * <p>Not task 22's search-first path on purpose: that exists for branded ready meals, where Claude naming a
 * product is the bug. «Спагеті» and «яйця» are what the catalog search is good at.
 */
@Slf4j
@Service
public class DishIngredientsService {

    /** Below this the vision guess is not offered as a name, only a request for one. */
    static final double IDENTIFY_CONFIDENCE_THRESHOLD = 0.5;

    private static final int DEFAULT_SERVINGS = 2;
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final ClaudeApiClient claudeApiClient;
    private final UserProfileRepository userProfileRepository;
    private final CartConfirmationService cartConfirmationService;
    private final TelegramOutboundService telegramOutboundService;
    private final String ingredientsSystemPrompt;
    private final String identifySystemPrompt;

    public DishIngredientsService(
            ClaudeApiClient claudeApiClient,
            UserProfileRepository userProfileRepository,
            CartConfirmationService cartConfirmationService,
            TelegramOutboundService telegramOutboundService,
            @Value("classpath:prompts/dish-ingredients-system.txt") Resource ingredientsSystemPromptResource,
            @Value("classpath:prompts/dish-identify-system.txt") Resource identifySystemPromptResource) {
        this.claudeApiClient = claudeApiClient;
        this.userProfileRepository = userProfileRepository;
        this.cartConfirmationService = cartConfirmationService;
        this.telegramOutboundService = telegramOutboundService;
        this.ingredientsSystemPrompt = read(ingredientsSystemPromptResource);
        this.identifySystemPrompt = read(identifySystemPromptResource);
    }

    /** Generates the dish's shopping list for this household and puts the resulting cart up for confirmation. */
    public void orderIngredients(User user, String dishName) {
        long chatId = user.getTelegramChatId();
        int servings = servingsFor(user.getId());
        DishIngredients dish;
        try {
            dish = claudeApiClient.completeStructured(
                    ingredientsSystemPrompt,
                    "Страва: %s.\nПорцій: %d.".formatted(dishName, servings),
                    DishIngredients.class);
        } catch (RuntimeException e) {
            log.error("could not list ingredients for «{}» for user {}", dishName, user.getId(), e);
            telegramOutboundService.sendMessage(
                    chatId, "Не зміг скласти інгредієнти для «%s». Спробуй ще раз трохи пізніше.".formatted(dishName));
            return;
        }
        List<PlannedIngredient> lines = dish == null || dish.items() == null
                ? List.of()
                : dish.items().stream()
                        .filter(line -> line != null
                                && line.name() != null
                                && !line.name().isBlank())
                        .toList();
        if (lines.isEmpty()) {
            telegramOutboundService.sendMessage(
                    chatId, "Не зрозумів, що купувати для «%s». Напиши назву страви інакше.".formatted(dishName));
            return;
        }
        List<ShoppingListItem> items = lines.stream()
                .map(line -> ShoppingListItem.builder()
                        .id(UUID.randomUUID())
                        .userId(user.getId())
                        .name(line.name().trim())
                        .quantity(line.quantity())
                        .unit(line.unit())
                        .category(line.category())
                        // Never trust a product id the model may have typed here — task 09's search resolves these.
                        .silpoProductId(null)
                        .build())
                .toList();
        telegramOutboundService.sendMessage(
                chatId,
                "Інгредієнти для «%s» на %d %s — збираю кошик."
                        .formatted(dishName, servings, servings == 1 ? "порцію" : servings < 5 ? "порції" : "порцій"));
        if (cartConfirmationService.present(user, items, OrderType.AD_HOC)) {
            log.info("presented a {}-line ingredient cart for «{}» to user {}", items.size(), dishName, user.getId());
        } else {
            log.info("could not present an ingredient cart for «{}» to user {}", dishName, user.getId());
        }
    }

    /**
     * What dish is on the photo, when the model is confident enough to say. Empty means "ask the person" — a
     * wrong guess silently turned into a cart is the failure this method exists to avoid.
     */
    public Optional<String> identifyDish(byte[] image, String mediaType) {
        String answer;
        try {
            answer = claudeApiClient.image(identifySystemPrompt, "Що це за страва?", image, mediaType);
        } catch (RuntimeException e) {
            log.warn("could not identify a dish from a photo: {}", e.getMessage());
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(answer);
            String name = node.path("dishName").isTextual()
                    ? node.path("dishName").asText().trim()
                    : "";
            double confidence = node.path("confidence").asDouble(0);
            if (name.isBlank() || confidence < IDENTIFY_CONFIDENCE_THRESHOLD) {
                log.info("dish photo not identified confidently (name='{}', confidence={})", name, confidence);
                return Optional.empty();
            }
            return Optional.of(name);
        } catch (Exception e) {
            log.warn("dish identification answer was not JSON: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private int servingsFor(UUID userId) {
        return userProfileRepository
                .findByUserId(userId)
                .map(profile -> profile.getHouseholdSize())
                .filter(size -> size != null && size > 0)
                .orElse(DEFAULT_SERVINGS);
    }

    private static String read(Resource resource) {
        try (var stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read a dish prompt", e);
        }
    }
}
