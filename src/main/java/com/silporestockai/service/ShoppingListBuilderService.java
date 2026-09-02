package com.silporestockai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.OrderType;
import com.silporestockai.model.ShoppingListDraft;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.repository.ShoppingListItemRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.service.telegram.ShoppingListMessageService;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Builds a shopping list from whatever the person has to hand, and shows it before anything is ordered.
 *
 * <p>This exists because of eighty-four bananas. The old path went from a profile answer straight to a Silpo cart,
 * with no moment where a human could look at the list and say "that is obviously wrong" — and a comma-split of the
 * sentence «все окрім молочки та бананів» produced exactly that. Two changes follow from it: the list is built by a
 * model reading the person's own words, and nothing reaches a cart until they have seen it.
 */
@Slf4j
@Service
public class ShoppingListBuilderService {

    private static final String STEP_AWAITING_INPUT = "AWAITING_INPUT";
    private static final String STEP_AWAITING_APPROVAL = "AWAITING_APPROVAL";
    private static final String STEP_AWAITING_EDIT = "AWAITING_EDIT";

    /** Own mapper, as elsewhere in the app: Boot 4 carries both Jackson 2 and Jackson 3. */
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final ClaudeApiClient claudeApiClient;
    private final ShoppingListService shoppingListService;
    private final ShoppingListItemRepository shoppingListItemRepository;
    private final UserProfileRepository userProfileRepository;
    private final BaselineBasketRepository baselineBasketRepository;
    private final ConversationStateService conversationStateService;
    private final CartConfirmationService cartConfirmationService;
    private final ShoppingListMessageService messages;
    private final TelegramOutboundService telegramOutboundService;
    private final String systemPrompt;

    public ShoppingListBuilderService(
            ClaudeApiClient claudeApiClient,
            ShoppingListService shoppingListService,
            ShoppingListItemRepository shoppingListItemRepository,
            UserProfileRepository userProfileRepository,
            BaselineBasketRepository baselineBasketRepository,
            ConversationStateService conversationStateService,
            CartConfirmationService cartConfirmationService,
            ShoppingListMessageService messages,
            TelegramOutboundService telegramOutboundService,
            @Value("classpath:prompts/shopping-list-system.txt") Resource systemPromptResource) {
        this.claudeApiClient = claudeApiClient;
        this.shoppingListService = shoppingListService;
        this.shoppingListItemRepository = shoppingListItemRepository;
        this.userProfileRepository = userProfileRepository;
        this.baselineBasketRepository = baselineBasketRepository;
        this.conversationStateService = conversationStateService;
        this.cartConfirmationService = cartConfirmationService;
        this.messages = messages;
        this.telegramOutboundService = telegramOutboundService;
        this.systemPrompt = read(systemPromptResource);
    }

    /** Opens the conversation: a photo, a receipt, or a sentence. */
    public void askForInput(User user) {
        long chatId = user.getTelegramChatId();
        conversationStateService.save(chatId, ConversationFlow.LIST_BUILDING, STEP_AWAITING_INPUT, Map.of());
        telegramOutboundService.sendMessage(chatId, messages.askForInputText());
    }

    /**
     * Shows a list somebody else produced — the weekly plan uses this instead of building a cart on its own.
     *
     * <p>Whatever is shown becomes the only live list for this user, regardless of which flow produced it or the one
     * before it. Without this, an ad-hoc {@code /list} answer and a weekly plan's derived list can both sit in
     * {@code shopping_list_item} at once — invisible right up until an order merges both, sends the same product to
     * Silpo twice in one call, and the whole cart is refused with a bare 400.
     */
    public void present(User user, List<ShoppingListItem> items) {
        long chatId = user.getTelegramChatId();
        if (items.isEmpty()) {
            telegramOutboundService.sendMessage(chatId, messages.couldNotBuildText());
            return;
        }
        shoppingListService.keepOnly(user.getId(), items.stream().map(ShoppingListItem::getId).toList());
        conversationStateService.save(chatId, ConversationFlow.LIST_BUILDING, STEP_AWAITING_APPROVAL, Map.of());
        telegramOutboundService.sendMessageWithButtons(chatId, messages.listText(items), messages.listButtons());
    }

    /** Everything a chat sitting in {@link ConversationFlow#LIST_BUILDING} can send. */
    public void handle(User user, TelegramIncomingUpdate incoming) {
        long chatId = incoming.chatId();
        String step = stepOf(chatId);

        switch (incoming) {
            case TelegramIncomingUpdate.ButtonTap tap -> {
                telegramOutboundService.answerCallback(tap.callbackQueryId());
                handleTap(user, tap.data());
            }
            case TelegramIncomingUpdate.Text text -> {
                if (STEP_AWAITING_APPROVAL.equals(step)) {
                    // They typed instead of tapping. Treat it as the edit they meant.
                    buildAndShow(user, "Поточний список треба змінити так: " + text.text(), null);
                    return;
                }
                buildAndShow(user, text.text(), null);
            }
            case TelegramIncomingUpdate.Photo photo -> {
                telegramOutboundService.sendMessage(chatId, messages.buildingText());
                byte[] image = telegramOutboundService.downloadFile(photo.fileId());
                buildAndShow(user, "Ось фото. Склади список покупок на тиждень.", image);
            }
            case TelegramIncomingUpdate.Voice ignored ->
                telegramOutboundService.sendMessage(chatId, "Напиши текстом або надішли фото, будь ласка.");
        }
    }

    private void handleTap(User user, String data) {
        long chatId = user.getTelegramChatId();
        switch (data) {
            case ShoppingListMessageService.CALLBACK_ORDER -> order(user);
            case ShoppingListMessageService.CALLBACK_EDIT -> {
                conversationStateService.save(chatId, ConversationFlow.LIST_BUILDING, STEP_AWAITING_EDIT, Map.of());
                telegramOutboundService.sendMessage(chatId, messages.askForEditText());
            }
            case ShoppingListMessageService.CALLBACK_CANCEL -> {
                conversationStateService.save(chatId, ConversationFlow.NONE, null, Map.of());
                telegramOutboundService.sendMessage(chatId, messages.cancelledText());
            }
            default -> log.debug("ignoring unknown list callback {} in chat {}", data, chatId);
        }
    }

    /**
     * Asks the model for a list and puts it in front of the user.
     *
     * <p>The stored ad-hoc list is replaced each time rather than appended to: an edit produces a new list, and half
     * of the old one next to half of the new one is worse than either.
     */
    public void buildAndShow(User user, String instruction, byte[] image) {
        long chatId = user.getTelegramChatId();
        ShoppingListDraft draft;
        try {
            String userPrompt = describe(user.getId(), instruction, currentItems(user.getId()));
            draft = image == null
                    ? claudeApiClient.completeStructured(systemPrompt, userPrompt, ShoppingListDraft.class)
                    : MAPPER.readValue(
                            claudeApiClient.image(systemPrompt, userPrompt, image, "image/jpeg"),
                            ShoppingListDraft.class);
        } catch (Exception e) {
            log.error("could not build a shopping list for user {}", user.getId(), e);
            telegramOutboundService.sendMessage(chatId, messages.couldNotBuildText());
            return;
        }
        if (draft == null || draft.items() == null || draft.items().isEmpty()) {
            telegramOutboundService.sendMessage(chatId, messages.couldNotBuildText());
            return;
        }

        shoppingListItemRepository.deleteAll(shoppingListItemRepository.findByUserIdAndMealPlanIdIsNull(user.getId()));
        List<ShoppingListItem> stored = shoppingListService.createAdHocList(user.getId(), draft.items());
        present(user, stored);
    }

    /** Approval. From here on it is task 10's confirmation, unchanged. */
    private void order(User user) {
        List<ShoppingListItem> items = currentItems(user.getId());
        if (items.isEmpty()) {
            telegramOutboundService.sendMessage(user.getTelegramChatId(), messages.couldNotBuildText());
            return;
        }
        // The first confirmed basket is what every later check-in is compared against; a later one is not.
        OrderType type = baselineBasketRepository
                        .findByUserIdAndIsCurrentTrue(user.getId())
                        .isPresent()
                ? OrderType.AD_HOC
                : OrderType.INITIAL;
        cartConfirmationService.present(user, items, type);
    }

    /**
     * Whatever list {@link #present} last put in front of this user — ad-hoc or derived from a weekly plan.
     * {@code order()} and the edit prompt both need "the list on screen right now", not one origin of it: a list
     * {@link com.silporestockai.service.MealPlanHandoffService} handed over carries a {@code mealPlanId}, so filtering
     * it out here made tapping «Замовити» right after a weekly plan fail with "could not build a list" — nothing was
     * wrong, the query just never looked at the right rows.
     */
    private List<ShoppingListItem> currentItems(UUID userId) {
        return shoppingListItemRepository.findByUserId(userId);
    }

    /**
     * What the model is told.
     *
     * <p>Restrictions and dislikes go in as the person's own sentence, in quotes. Handing over a comma-split of
     * «все окрім молочки та бананів» is what produced eighty-four bananas: as a list it says the opposite of what
     * was meant, and only the surrounding words carry the negation.
     */
    private String describe(UUID userId, String instruction, List<ShoppingListItem> current) {
        StringBuilder text = new StringBuilder();
        userProfileRepository.findByUserId(userId).ifPresent(profile -> {
            text.append("Родина: ")
                    .append(
                            profile.getHouseholdSize() == null || profile.getHouseholdSize() <= 0
                                    ? "невідомо скільки людей"
                                    : profile.getHouseholdSize() + " людей")
                    .append(".\n");
            quoted(text, "Про алергії та обмеження людина сказала", profile.getDietaryRestrictions());
            quoted(text, "Про те, чого не їдять, людина сказала", profile.getDislikedFoods());
            if (profile.getWeeklyBudget() != null) {
                text.append("Орієнтовний бюджет на тиждень: ")
                        .append(profile.getWeeklyBudget().toPlainString())
                        .append(" грн.\n");
            }
        });
        if (!current.isEmpty()) {
            text.append("\nПоточний список:\n");
            current.forEach(item -> text.append("- ")
                    .append(item.getName())
                    .append(" — ")
                    .append(item.getQuantity())
                    .append(' ')
                    .append(item.getUnit() == null ? "" : item.getUnit())
                    .append('\n'));
        }
        text.append("\nЩо каже людина:\n").append(instruction);
        return text.toString();
    }

    /** Quoted verbatim, never split: the sentence is the data. */
    private static void quoted(StringBuilder text, String label, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        text.append(label).append(": «").append(String.join(" ", values)).append("».\n");
    }

    private String stepOf(long chatId) {
        return conversationStateService.load(chatId).getCurrentStep();
    }

    private static String read(Resource resource) {
        try (var stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the shopping list system prompt", e);
        }
    }
}
