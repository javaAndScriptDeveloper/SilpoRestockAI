package com.silporestockai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.entity.ConversationState;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.OrderType;
import com.silporestockai.model.PlannedIngredient;
import com.silporestockai.model.ShoppingListDelta;
import com.silporestockai.model.ShoppingListDraft;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.service.telegram.ShoppingListMessageService;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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

    /**
     * A list on screen with its keyboard under it. Public because it is the one step in this application that is a
     * state rather than a question, and other services have to be able to say so — see {@link #awaitsAnAnswer}.
     */
    public static final String STEP_AWAITING_APPROVAL = "AWAITING_APPROVAL";

    /** A cart build is in flight for this chat. Not a question either — see {@link #awaitsAnAnswer}. */
    private static final String STEP_BUILDING_CART = "BUILDING_CART";

    private static final String STEP_AWAITING_EDIT = "AWAITING_EDIT";

    /** Longer than any cart build; shorter than a household's patience. See {@link #aCartIsStillBeingBuilt}. */
    static final Duration BUILD_GUARD_TTL = Duration.ofMinutes(3);

    /** Own mapper, as elsewhere in the app: Boot 4 carries both Jackson 2 and Jackson 3. */
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final ClaudeApiClient claudeApiClient;
    private final ShoppingListService shoppingListService;
    private final ShoppingListDiffService shoppingListDiffService;
    private final UserProfileRepository userProfileRepository;
    private final BaselineBasketRepository baselineBasketRepository;
    private final ConversationStateService conversationStateService;
    private final CartConfirmationService cartConfirmationService;
    private final ShoppingListMessageService messages;
    private final TelegramOutboundService telegramOutboundService;
    private final ShoppingListPriceEstimateService priceEstimateService;
    private final String systemPrompt;

    public ShoppingListBuilderService(
            ClaudeApiClient claudeApiClient,
            ShoppingListService shoppingListService,
            ShoppingListDiffService shoppingListDiffService,
            UserProfileRepository userProfileRepository,
            BaselineBasketRepository baselineBasketRepository,
            ConversationStateService conversationStateService,
            CartConfirmationService cartConfirmationService,
            ShoppingListMessageService messages,
            TelegramOutboundService telegramOutboundService,
            ShoppingListPriceEstimateService priceEstimateService,
            @Value("classpath:prompts/shopping-list-system.txt") Resource systemPromptResource) {
        this.claudeApiClient = claudeApiClient;
        this.shoppingListService = shoppingListService;
        this.shoppingListDiffService = shoppingListDiffService;
        this.userProfileRepository = userProfileRepository;
        this.baselineBasketRepository = baselineBasketRepository;
        this.conversationStateService = conversationStateService;
        this.cartConfirmationService = cartConfirmationService;
        this.messages = messages;
        this.telegramOutboundService = telegramOutboundService;
        this.priceEstimateService = priceEstimateService;
        this.systemPrompt = read(systemPromptResource);
    }

    /**
     * What the «Список» button does: shows the list the household currently has on the table, or — when there
     * is none yet, or the last one was already ordered — opens the conversation that builds one.
     *
     * <p>Previously the button always asked "Що беремо на цей тиждень?", even seconds after a weekly plan had
     * put a full list on screen — the one thing the button's own name promises to show. Task 29 defines it as
     * "активний основний список", and the demo script's step 4 is literally "кнопка «Список» → список".
     */
    public void showCurrentOrAsk(User user) {
        List<ShoppingListItem> items = currentItems(user.getId());
        if (items.isEmpty()) {
            askForInput(user);
            return;
        }
        present(user, items);
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
        makeActive(user, items);
        telegramOutboundService.sendMessageWithButtons(
                chatId,
                messages.listText(items, priceEstimateService.estimate(user.getId(), items)),
                messages.listButtons());
    }

    /**
     * Same as {@link #present}, except an AI-triggered regeneration (diet adjustment, special-mode switch,
     * checkin-driven reorder) shows what changed against the list the household had a moment ago, not the
     * full list dumped again (task 21) — reintroducing the "wall of text" problem {@link #present}'s own
     * javadoc already explains was the point of building this class in the first place. A manual single-item
     * edit ({@link #adjustQuantity}) never goes through here — it already has its own immediate inline
     * feedback, and showing a delta on top of that would be friction over a change the user just made
     * themselves with full context.
     *
     * @param previousItems the list this user had active immediately before the regeneration that produced
     *     {@code items} — empty for a first-ever list, in which case this behaves exactly like {@link #present}
     */
    public void presentRegenerated(User user, List<ShoppingListItem> items, List<ShoppingListItem> previousItems) {
        long chatId = user.getTelegramChatId();
        if (items.isEmpty()) {
            telegramOutboundService.sendMessage(chatId, messages.couldNotBuildText());
            return;
        }
        if (previousItems.isEmpty()) {
            present(user, items);
            return;
        }
        ShoppingListDelta delta = shoppingListDiffService.diff(previousItems, items);
        makeActive(user, items);
        if (delta.totalChanges() == 0) {
            telegramOutboundService.sendMessage(chatId, "Раціон лишився без змін.");
        } else if (delta.isTrivial()) {
            telegramOutboundService.sendMessageWithButtons(
                    chatId, messages.trivialDeltaText(delta), messages.listButtons());
        } else {
            telegramOutboundService.sendMessageWithButtons(chatId, messages.deltaText(delta), messages.deltaButtons());
        }
    }

    /** Everything {@link #present} and {@link #presentRegenerated} share: make {@code items} the live list. */
    private void makeActive(User user, List<ShoppingListItem> items) {
        shoppingListService.keepOnly(
                user.getId(), items.stream().map(ShoppingListItem::getId).toList());
        conversationStateService.save(
                user.getTelegramChatId(), ConversationFlow.LIST_BUILDING, STEP_AWAITING_APPROVAL, Map.of());
    }

    /**
     * Whether this flow is genuinely waiting on this update — as opposed to merely having a list on screen.
     *
     * <p>{@link #STEP_AWAITING_APPROVAL} is not a question. It is a keyboard sitting under a finished list, and
     * nothing ever clears it: from a household's very first weekly plan onwards, {@code conversation_state} stays
     * parked there forever. While this class claimed every update in that state, each sentence a household typed was
     * silently rewritten as «Поточний список треба змінити так: …» and answered with a regenerated weekly list —
     * «замов усе для карбонари» and «замов сир з вином на п'ятницю» included. That looked like the intent
     * classifier guessing wrong; the classifier was never reached. Free text at this step belongs to it, and it has
     * a LIST_MODIFY intent that hands genuine edits straight back here.
     *
     * <p>The two steps that really did ask something — the opening «Що беремо на цей тиждень?» and the «Змінити»
     * prompt — still own the answer to their own question. Button taps are dispatched globally by the routing
     * layer and never reach this check.
     */
    public boolean awaitsAnAnswer(long chatId) {
        String step = stepOf(chatId);
        return STEP_AWAITING_INPUT.equals(step) || STEP_AWAITING_EDIT.equals(step);
    }

    /**
     * The opening question only — «Що беремо на цей тиждень?» — as opposed to the «Змінити» prompt.
     *
     * <p>The two differ in how sure the chat is about the next sentence. After a tap on «Змінити» the sentence is
     * the edit. After the opener, typed in answer to a button that merely offered to build a list, «що їмо в
     * середу?» or «замов усе для карбонари» are requests, not descriptions, and the routing layer offers them to
     * the intent router first (see {@link #stepAsideForARequest}).
     */
    public boolean awaitsFirstInput(long chatId) {
        return STEP_AWAITING_INPUT.equals(stepOf(chatId));
    }

    /** Closes the opening question so a request typed over it can own the chat; {@link #reopenQuestion} undoes it. */
    public void stepAsideForARequest(long chatId) {
        conversationStateService.save(chatId, ConversationFlow.NONE, null, Map.of());
    }

    public void reopenQuestion(long chatId) {
        conversationStateService.save(chatId, ConversationFlow.LIST_BUILDING, STEP_AWAITING_INPUT, Map.of());
    }

    /**
     * A tap on a list keyboard, from wherever the chat currently is.
     *
     * <p>Separate from {@link #handle} because these taps carry everything they need (see
     * {@link ShoppingListMessageService#isListCallback}) and must keep working when some other flow — a check-in
     * prompt that landed a second earlier, most often — happens to hold {@code conversation_state}.
     */
    public void handleButtonTap(User user, TelegramIncomingUpdate.ButtonTap tap) {
        telegramOutboundService.answerCallback(tap.callbackQueryId());
        handleTap(user, tap.data());
    }

    /** Everything a chat sitting in {@link ConversationFlow#LIST_BUILDING} can send. */
    public void handle(User user, TelegramIncomingUpdate incoming) {
        long chatId = incoming.chatId();

        switch (incoming) {
            case TelegramIncomingUpdate.ButtonTap tap -> handleButtonTap(user, tap);
            case TelegramIncomingUpdate.Text text -> buildAndShow(user, text.text(), null);
            case TelegramIncomingUpdate.Photo photo -> {
                telegramOutboundService.sendMessage(chatId, messages.buildingText());
                byte[] image = telegramOutboundService.downloadFile(photo.fileId());
                buildAndShow(user, "Ось фото. Склади список покупок на тиждень.", image);
            }
            case TelegramIncomingUpdate.Voice ignored ->
                telegramOutboundService.sendMessage(chatId, "Напиши текстом або надішли фото, будь ласка.");
            case TelegramIncomingUpdate.WebAppData ignored ->
                telegramOutboundService.sendMessage(chatId, "Напиши текстом або надішли фото, будь ласка.");
            // The group-chat shapes (task 68) never reach a household flow; the router splits them off first.
            default -> log.debug("ignoring a group update in a household flow for chat {}", incoming.chatId());
        }
    }

    private void handleTap(User user, String data) {
        long chatId = user.getTelegramChatId();
        if (data.startsWith(ShoppingListMessageService.CALLBACK_ITEM_DEC_PREFIX)) {
            adjustQuantity(
                    user,
                    UUID.fromString(data.substring(ShoppingListMessageService.CALLBACK_ITEM_DEC_PREFIX.length())),
                    new BigDecimal("-1"));
            return;
        }
        if (data.startsWith(ShoppingListMessageService.CALLBACK_ITEM_INC_PREFIX)) {
            adjustQuantity(
                    user,
                    UUID.fromString(data.substring(ShoppingListMessageService.CALLBACK_ITEM_INC_PREFIX.length())),
                    BigDecimal.ONE);
            return;
        }
        switch (data) {
            case ShoppingListMessageService.CALLBACK_ORDER -> order(user);
            case ShoppingListMessageService.CALLBACK_EDIT -> {
                conversationStateService.save(chatId, ConversationFlow.LIST_BUILDING, STEP_AWAITING_EDIT, Map.of());
                telegramOutboundService.sendMessage(chatId, messages.askForEditText());
            }
            case ShoppingListMessageService.CALLBACK_MANUAL_EDIT -> showManualEdit(user);
            case ShoppingListMessageService.CALLBACK_SHOW_FULL -> present(user, currentItems(user.getId()));
            case ShoppingListMessageService.CALLBACK_CANCEL -> {
                conversationStateService.save(chatId, ConversationFlow.NONE, null, Map.of());
                telegramOutboundService.sendMessage(chatId, messages.cancelledText());
            }
            default -> log.debug("ignoring unknown list callback {} in chat {}", data, chatId);
        }
    }

    /**
     * The deterministic, AI-free edit path: one message per item, each with its own −/+ row. Distinct from
     * {@link ShoppingListMessageService#CALLBACK_EDIT}, which free-texts the whole list to Claude — this one never
     * calls {@link ClaudeApiClient} at all, only {@link ShoppingListService}'s plain CRUD methods.
     */
    private void showManualEdit(User user) {
        long chatId = user.getTelegramChatId();
        List<ShoppingListItem> items = currentItems(user.getId());
        if (items.isEmpty()) {
            telegramOutboundService.sendMessage(chatId, messages.couldNotBuildText());
            return;
        }
        telegramOutboundService.sendMessage(chatId, messages.manualEditIntroText());
        items.forEach(item ->
                telegramOutboundService.sendMessageWithButtons(chatId, item.getName(), messages.itemButtons(item)));
    }

    private void adjustQuantity(User user, UUID itemId, BigDecimal delta) {
        long chatId = user.getTelegramChatId();
        currentItems(user.getId()).stream()
                .filter(item -> item.getId().equals(itemId))
                .findFirst()
                .ifPresent(item -> {
                    BigDecimal newQuantity =
                            (item.getQuantity() == null ? BigDecimal.ZERO : item.getQuantity()).add(delta);
                    if (newQuantity.signum() <= 0) {
                        shoppingListService.removeItem(user.getId(), itemId);
                        telegramOutboundService.sendMessage(chatId, "Прибрав.");
                    } else {
                        shoppingListService.updateQuantity(user.getId(), itemId, newQuantity);
                        telegramOutboundService.sendMessage(chatId, "Оновив: " + newQuantity + ".");
                    }
                });
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

        shoppingListService.keepOnly(user.getId(), List.of());
        List<ShoppingListItem> stored = shoppingListService.createAdHocList(user.getId(), withoutProductIds(draft));
        present(user, stored);
    }

    /**
     * Drops any {@code productId} the model filled in, exactly as {@code MealPlanService.withoutProductIds} does for
     * the weekly plan and for the same reason: {@link ShoppingListDraft} is a list of {@link PlannedIngredient}, so
     * the field is in the schema on this path too, and nothing in the shopping-list prompt tells the model to leave
     * it blank. A fabricated non-UUID id persisted here is what {@code silpo_add_or_update_cart_products} rejects the
     * whole cart over. Only the catalog may set this field, never the model.
     */
    private static List<PlannedIngredient> withoutProductIds(ShoppingListDraft draft) {
        return draft.items().stream()
                .map(ingredient -> new PlannedIngredient(
                        ingredient.name(), ingredient.quantity(), ingredient.unit(), ingredient.category(), null, null))
                .toList();
    }

    /**
     * Approval. From here on it is task 10's confirmation, unchanged.
     *
     * <p>Two things about the wait, both learned from a live account tapping «Замовити» three times in twenty
     * seconds. Building a cart is a whole-catalogue search, a product choice per line and several Silpo calls —
     * the better part of a minute — and until this said so, the tap looked like it had done nothing at all.
     *
     * <p>And a second tap must not start a second build: they share one Silpo cart, and the first thing a build
     * does is empty it, so two of them racing clear each other's work and leave the household with whichever
     * finished last. The guard lives in {@code conversation_state} rather than a field, because two updates can
     * land on different instances — the same reason nothing else in this application keeps memory in a field.
     */
    private void order(User user) {
        long chatId = user.getTelegramChatId();
        if (aCartIsStillBeingBuilt(chatId)) {
            log.info("ignoring a second «Замовити» for chat {}: a cart is already being built", chatId);
            telegramOutboundService.sendMessage(chatId, messages.stillBuildingCartText());
            return;
        }
        List<ShoppingListItem> items = currentItems(user.getId());
        if (items.isEmpty()) {
            telegramOutboundService.sendMessage(chatId, messages.couldNotBuildText());
            return;
        }
        // The first confirmed basket is what every later check-in is compared against; a later one is not.
        OrderType type = baselineBasketRepository
                        .findByUserIdAndIsCurrentTrue(user.getId())
                        .isPresent()
                ? OrderType.AD_HOC
                : OrderType.INITIAL;
        conversationStateService.save(chatId, ConversationFlow.LIST_BUILDING, STEP_BUILDING_CART, Map.of());
        telegramOutboundService.sendMessage(chatId, messages.buildingCartText());
        boolean presented = false;
        try {
            presented = cartConfirmationService.present(user, items, type);
        } finally {
            // present() takes the state over on success (CART_CONFIRMATION) and reports its own failures without
            // throwing. Either way, a chat still sitting on the guard is one whose build ended without a cart —
            // put the list back in front of them so «Замовити» works again rather than being ignored forever.
            if (STEP_BUILDING_CART.equals(stepOf(chatId))) {
                conversationStateService.save(chatId, ConversationFlow.LIST_BUILDING, STEP_AWAITING_APPROVAL, Map.of());
            }
        }
        if (!presented) {
            // The failure itself was already explained. This is the one thing the person can do about it, as a
            // button rather than a sentence telling them to scroll up and find the list.
            telegramOutboundService.sendMessageWithButtons(
                    chatId, messages.retryOrderText(), messages.retryOrderButtons());
        }
    }

    /**
     * Whatever list {@link #present} last put in front of this user — ad-hoc or derived from a weekly plan.
     * {@code order()} and the edit prompt both need "the list on screen right now", not one origin of it: a list
     * {@link com.silporestockai.service.MealPlanHandoffService} handed over carries a {@code mealPlanId}, so filtering
     * it out here made tapping «Замовити» right after a weekly plan fail with "could not build a list" — nothing was
     * wrong, the query just never looked at the right rows.
     */
    private List<ShoppingListItem> currentItems(UUID userId) {
        return shoppingListService.currentItems(userId);
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

    /**
     * Whether the build guard is genuinely live, as opposed to left behind.
     *
     * <p>The guard is cleared in a {@code finally}, which a JVM that dies mid-build never reaches — the tunnel
     * supervisor restarted this application under a live cart build once, and from then on every «Замовити» in
     * that chat was ignored for good. A build takes under two minutes; a guard older than {@link #BUILD_GUARD_TTL}
     * is a build that is not coming back, and the tap goes ahead.
     */
    private boolean aCartIsStillBeingBuilt(long chatId) {
        ConversationState state = conversationStateService.load(chatId);
        if (!STEP_BUILDING_CART.equals(state.getCurrentStep())) {
            return false;
        }
        Instant since = state.getUpdatedAt();
        boolean stale = since == null || since.plus(BUILD_GUARD_TTL).isBefore(Instant.now());
        if (stale) {
            log.warn("chat {} was left on the cart-build guard since {}; treating it as abandoned", chatId, since);
        }
        return !stale;
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
