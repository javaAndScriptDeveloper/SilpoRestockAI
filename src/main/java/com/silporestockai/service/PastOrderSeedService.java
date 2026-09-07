package com.silporestockai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.entity.ConversationState;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.PastOrderLine;
import com.silporestockai.model.PastOrderSummary;
import com.silporestockai.model.PlannedIngredient;
import com.silporestockai.model.ShoppingListSourceType;
import com.silporestockai.model.TelegramButton;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * «Зроби список як минулого разу» (task 35): the household's real Silpo orders, one tap, and the picked order's
 * lines become the live list — product ids, quantities and prices copied, nothing generated, nothing searched.
 *
 * <p>This is strictly better than any receipt photo: the ids are Silpo's own, so {@code CartBuildingService}
 * skips {@code silpo_find_products_batch} entirely (task 22's pre-resolved path) and the cart is the order,
 * not a best-effort match of it. The list then goes through the ordinary Замовити → cart → confirm steps and
 * becomes the baseline like any first order — this class only changes where the list came from.
 *
 * <p>The person always picks; the newest order is never assumed. Reading the history itself belongs to
 * {@link OrderHistoryService} — task 56 asks the same two tools a different question, and one reader keeps
 * the response-shape guessing in a single place. An order the tools return without line items is said to be
 * exactly that rather than padded.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PastOrderSeedService {

    public static final String CALLBACK_PREFIX = "past:";
    public static final String CALLBACK_CANCEL = "past:cancel";

    private static final String STEP_AWAITING_PICK = "AWAITING_PICK";
    private static final String KEY_ORDERS = "orders";

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final OrderHistoryService orderHistoryService;
    private final SilpoAuthService silpoAuthService;
    private final ShoppingListService shoppingListService;
    private final ShoppingListBuilderService shoppingListBuilderService;
    private final ConversationStateService conversationStateService;
    private final TelegramOutboundService telegramOutboundService;

    /** Lists recent orders as buttons, newest first, and waits for a pick. */
    public void offer(User user) {
        long chatId = user.getTelegramChatId();
        if (!silpoAuthService.isConnected(user.getId())) {
            telegramOutboundService.sendMessage(
                    chatId, "Спершу під'єднай акаунт «Сільпо» — без нього я не бачу твоїх замовлень.");
            return;
        }
        List<PastOrderSummary> orders = orderHistoryService.read(user.getId()).orders();
        if (orders.isEmpty()) {
            telegramOutboundService.sendMessage(
                    chatId,
                    "Не бачу минулих замовлень в акаунті «Сільпо». Можу скласти список сам — натисни «Список».");
            return;
        }
        List<TelegramButton> buttons = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            buttons.add(TelegramButton.callback(OrderHistoryService.label(orders.get(i)), CALLBACK_PREFIX + i));
        }
        buttons.add(TelegramButton.callback("Скасувати", CALLBACK_CANCEL));
        conversationStateService.save(
                chatId,
                ConversationFlow.PAST_ORDER_PICK,
                STEP_AWAITING_PICK,
                Map.of(
                        KEY_ORDERS,
                        orders.stream().map(PastOrderSeedService::asMap).toList()));
        telegramOutboundService.sendMessageWithButtons(
                chatId, "Ось твої останні замовлення в «Сільпо». Яке взяти за основу?", buttons);
    }

    /** Everything a chat sitting in {@link ConversationFlow#PAST_ORDER_PICK} can send. */
    public void handle(User user, TelegramIncomingUpdate incoming) {
        long chatId = user.getTelegramChatId();
        if (!(incoming instanceof TelegramIncomingUpdate.ButtonTap tap)) {
            telegramOutboundService.sendMessage(chatId, "Обери замовлення кнопкою вище або натисни «Скасувати».");
            return;
        }
        telegramOutboundService.answerCallback(tap.callbackQueryId());
        if (CALLBACK_CANCEL.equals(tap.data())) {
            conversationStateService.save(chatId, ConversationFlow.NONE, null, Map.of());
            telegramOutboundService.sendMessageWithMainMenu(chatId, "Гаразд, список лишаю як є.");
            return;
        }
        if (!tap.data().startsWith(CALLBACK_PREFIX)) {
            log.debug("ignoring callback {} while awaiting a past-order pick in chat {}", tap.data(), chatId);
            return;
        }
        ConversationState state = conversationStateService.load(chatId);
        List<PastOrderSummary> orders = ordersOf(state);
        int index;
        try {
            index = Integer.parseInt(tap.data().substring(CALLBACK_PREFIX.length()));
        } catch (NumberFormatException e) {
            return;
        }
        if (index < 0 || index >= orders.size()) {
            return;
        }
        seed(user, orders.get(index));
    }

    private void seed(User user, PastOrderSummary order) {
        long chatId = user.getTelegramChatId();
        conversationStateService.save(chatId, ConversationFlow.NONE, null, Map.of());
        if (order.lines() == null || order.lines().isEmpty()) {
            // The listing tool answered with summaries only. Saying so beats a search by name that would turn a
            // known order into a guessed one.
            telegramOutboundService.sendMessage(
                    chatId,
                    "«Сільпо» віддало це замовлення без переліку позицій, тож зібрати з нього список не можу. "
                            + "Спробуй інше або натисни «Список».");
            log.warn("past order {} for user {} carried no line items", order.orderId(), user.getId());
            return;
        }
        List<PlannedIngredient> ingredients = order.lines().stream()
                .map(line -> new PlannedIngredient(
                        line.name(), line.quantity(), line.unit(), null, line.productId(), unitPrice(line)))
                .toList();
        shoppingListService.keepOnly(user.getId(), List.of());
        List<ShoppingListItem> items =
                shoppingListService.createAdHocList(user.getId(), ingredients, ShoppingListSourceType.PAST_ORDER);
        telegramOutboundService.sendMessage(
                chatId,
                "Взяв за основу замовлення " + OrderHistoryService.label(order) + " — " + items.size() + " "
                        + OrderHistoryService.positions(items.size())
                        + ", ті самі товари, що й тоді.");
        shoppingListBuilderService.present(user, items);
        log.info("seeded a {}-line list for user {} from past order {}", items.size(), user.getId(), order.orderId());
    }

    /** The list stores a unit price (task 39); an order line carries what the line cost. */
    private static BigDecimal unitPrice(PastOrderLine line) {
        if (line.price() == null) {
            return null;
        }
        if (line.quantity() == null || line.quantity().signum() <= 0) {
            return line.price();
        }
        return line.price().divide(line.quantity(), 2, RoundingMode.HALF_UP);
    }

    private static List<PastOrderSummary> ordersOf(ConversationState state) {
        Object raw = state.getContext().get(KEY_ORDERS);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(node -> MAPPER.convertValue(node, PastOrderSummary.class))
                .toList();
    }

    // convertValue to a raw Map rather than a TypeReference: an anonymous TypeReference subclass is a class in
    // this package, and ArchUnit requires every one of those to be named ...Service.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return MAPPER.convertValue(value, Map.class);
    }
}
