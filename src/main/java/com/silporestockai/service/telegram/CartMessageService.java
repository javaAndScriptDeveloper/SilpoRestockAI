package com.silporestockai.service.telegram;

import com.silporestockai.model.BasketItem;
import com.silporestockai.model.CartSummary;
import com.silporestockai.model.OfferedSlot;
import com.silporestockai.model.OrderType;
import com.silporestockai.model.TelegramButton;
import com.silporestockai.utils.DeliverySlots;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Everything a person actually reads about their cart: the wording and the buttons.
 *
 * <p>Kept apart from {@code CartConfirmationService} on purpose. The domain flow decides what happens to an order;
 * this decides how it is phrased, and the two change for entirely different reasons — a copy tweak should never touch
 * a service that writes to the database.
 *
 * <p>The callback payloads live here, next to the labels they belong to. Telegram caps callback data at 64 bytes,
 * which these are far inside.
 */
@Service
public class CartMessageService {

    public static final String CALLBACK_CONFIRM = "cart:confirm";
    public static final String CALLBACK_CONFIRM_BONUS = "cart:confirm-bonus";
    public static final String CALLBACK_SLOT_MENU = "cart:slotmenu";
    public static final String CALLBACK_SLOT_PREFIX = "cart:slot:";
    public static final String CALLBACK_CANCEL = "cart:cancel";
    public static final String CALLBACK_TOP_UP = "cart:topup";

    /**
     * The cart itself: what is in it, what could not be found, what Silpo warned about, what it costs.
     *
     * <p>The opening line depends on what kind of order this is. A blackout lunch or a Friday-night snack cart is
     * not "на тиждень", and a household reading that over an emergency lunch would rightly wonder what happened
     * to their week.
     *
     * <p>A line a partner placement won is rendered like any other line (task 62). The household asked for the
     * category and got the category; which brand answers it is our business, and a mid-cart «this one is paid for»
     * buys doubt about every other line rather than trust. The placement is still resolved, preferred and counted —
     * see {@code PartnerPromotionService} — just not announced.
     */
    public String cartText(CartSummary summary, OfferedSlot slot, OrderType type) {
        StringBuilder text =
                new StringBuilder(type == OrderType.AD_HOC ? "Зібрав кошик:\n" : "Зібрав кошик на тиждень:\n");
        text.append(cartLinesText(summary));
        if (!summary.unresolved().isEmpty()) {
            text.append("\n\nНе знайшов: ")
                    .append(String.join(", ", summary.unresolved()))
                    .append(" — можеш додати вручну пізніше.");
        }
        for (String validation : summary.validations()) {
            if (summary.belowMinimumOrder() && validation.startsWith("замовлення менше мінімальної суми")) {
                // Said in full, with the amounts and what to do about it, by the paragraph the below-minimum
                // message adds underneath; a warning line saying the same thing above it read as two problems.
                continue;
            }
            text.append("\n⚠ ").append(validation);
        }
        text.append("\n\nРазом: ").append(money(summary.total())).append(" грн");
        if (summary.hasSavings()) {
            // Silpo's own number: the sum its promotions took off these lines, not an estimate of ours.
            text.append("\nЕкономія за акціями: ")
                    .append(money(summary.savings()))
                    .append(" грн");
        }
        text.append("\n\nДоставка: ").append(DeliverySlots.describe(slot));
        if (summary.bonusDecisionPending()) {
            text.append("\nНа рахунку ")
                    .append(amount(summary.bonusAvailable()))
                    .append(" бонусів — можу списати їх на це замовлення.");
        }
        return text.toString();
    }

    /**
     * The lines of the cart, one per product, with what each costs — shared by the cart and the reorder messages.
     *
     * <p>A line the top-up added from the household's own baseline is marked «+» in place, and one sentence
     * underneath says what «+» means. The top-up used to be listed a second time under the cart — on a live
     * reorder that was thirteen lines the reader had just read, and a wall of text for a two-line delta. Every
     * added line still stands out, and the right to take one out still ends the sentence: that right is the whole
     * difference between a helpful top-up and an upsell.
     */
    public String cartLinesText(CartSummary summary) {
        StringBuilder text = new StringBuilder();
        java.util.Set<String> toppedUp = new java.util.HashSet<>();
        for (String line : summary.toppedUpLines()) {
            int cut = line.indexOf(" — ");
            toppedUp.add(cut < 0 ? line : line.substring(0, cut));
        }
        java.util.Set<String> marked = new java.util.HashSet<>();
        for (BasketItem item : summary.items()) {
            boolean added = item.name() != null && toppedUp.contains(item.name());
            if (added) {
                marked.add(item.name());
            }
            text.append(added ? "\n+ " : "\n— ").append(item.name());
            if (item.quantity() != null) {
                text.append(" — ").append(amount(item.quantity()));
                if (item.unit() != null) {
                    text.append(' ').append(item.unit());
                }
            }
            if (item.price() != null) {
                // What this line costs, not the price per kilogram beside a fraction of one: «0.1 — 1399.00 грн»
                // read as a ₴1399 cheese to the person who complained about it, when the line was ₴139.90.
                BigDecimal lineCost =
                        item.quantity() == null ? item.price() : item.price().multiply(item.quantity());
                text.append(" — ").append(money(lineCost)).append(" грн");
            }
        }
        if (!summary.toppedUpLines().isEmpty()) {
            text.append("\n\nРядки з «+» доклав із твого звичайного набору — саме замовлення було менше за мінімум ")
                    .append("доставки «Сільпо».");
            // A top-up line the cart read-back does not carry under that name is still named, so nothing added
            // on the household's behalf goes unsaid.
            summary.toppedUpLines().stream()
                    .filter(line -> {
                        int cut = line.indexOf(" — ");
                        return !marked.contains(cut < 0 ? line : line.substring(0, cut));
                    })
                    .forEach(line -> text.append("\n+ ").append(line));
            text.append("\nНе треба — скажи, що прибрати.");
        }
        if (!summary.skippedLines().isEmpty()) {
            // Held back on purpose, and said so with the number: a line that would have cost a small fortune is
            // worse in the cart than out of it, but hiding that it was dropped would be worse still.
            text.append("\n\nНе поклав, бо виглядає неправильно:");
            summary.skippedLines().forEach(line -> text.append("\n— ").append(line));
        }
        return text.toString();
    }

    /**
     * Confirm, cancel, and — only when there is a decision to make — a confirm that spends the bonuses.
     *
     * <p>The bonus question is asked by offering a second confirm rather than by sending a separate message: one tap
     * answers both questions, and there is only one state to make idempotent instead of two.
     */
    public List<TelegramButton> cartButtons(CartSummary summary, boolean hasAlternativeSlots) {
        List<TelegramButton> buttons = new ArrayList<>();
        buttons.add(TelegramButton.callback("Підтвердити", CALLBACK_CONFIRM));
        if (summary.bonusDecisionPending()) {
            buttons.add(TelegramButton.callback(
                    "Підтвердити + %s бонусів".formatted(amount(summary.bonusAvailable())), CALLBACK_CONFIRM_BONUS));
        }
        if (hasAlternativeSlots) {
            buttons.add(TelegramButton.callback("Інший час", CALLBACK_SLOT_MENU));
        }
        buttons.add(TelegramButton.callback("Скасувати", CALLBACK_CANCEL));
        return buttons;
    }

    /**
     * The same cart, plus the one fact that stops it: how far the goods are from Silpo's minimum order, and the two
     * ways out. Nothing has been added on the household's behalf at this point — that is what the button is for.
     */
    public String belowMinimumText(CartSummary summary, OfferedSlot slot, OrderType type, boolean hasBaseline) {
        StringBuilder text = new StringBuilder(cartText(summary, slot, type));
        text.append("\n\nТоварів тут на ")
                .append(money(summary.goodsTotal()))
                .append(" грн, а «Сільпо» доставляє замовлення від ")
                .append(amount(summary.minimumOrder()))
                .append(" грн — бракує ")
                .append(money(summary.shortfall()))
                .append(" грн.");
        if (hasBaseline) {
            text.append("\nМожу докласти з твого звичайного набору — або скасуй і докинь щось сам у застосунку")
                    .append(" «Сільпо», кошик уже там.");
        } else {
            text.append("\nКошик уже в застосунку «Сільпо» — докинь щось там, або скасуй.");
        }
        return text.toString();
    }

    /** Top up from the baseline when there is one to draw on; cancel either way. No confirm: there is nothing to confirm yet. */
    public List<TelegramButton> belowMinimumButtons(CartSummary summary, boolean hasBaseline) {
        List<TelegramButton> buttons = new ArrayList<>();
        if (hasBaseline) {
            buttons.add(TelegramButton.callback(
                    "Докласти з мого набору (~%s грн)"
                            .formatted(summary.shortfall()
                                    .setScale(0, RoundingMode.CEILING)
                                    .toPlainString()),
                    CALLBACK_TOP_UP));
        }
        buttons.add(TelegramButton.callback("Скасувати", CALLBACK_CANCEL));
        return buttons;
    }

    public String slotMenuText() {
        return "Коли зручно прийняти доставку?";
    }

    public List<TelegramButton> slotButtons(List<OfferedSlot> slots) {
        List<TelegramButton> buttons = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            buttons.add(TelegramButton.callback(DeliverySlots.describe(slots.get(i)), CALLBACK_SLOT_PREFIX + i));
        }
        return buttons;
    }

    /**
     * Said once the order is stored. Payment is Silpo's page, not ours — there is no MCP payment tool.
     *
     * <p>Only an {@link OrderType#INITIAL} order becomes the baseline ({@code CartConfirmationService.confirm}), so
     * only that one may say so. Every other order used to make the same claim, which was simply untrue — and a
     * person who then reported «шпроти закінчились» at the next check-in would have been told the bot didn't know
     * that item.
     */
    public String confirmedText(CartSummary summary, boolean bonusesApplied, OrderType type) {
        StringBuilder text = new StringBuilder("Підтвердив. ");
        if (type == OrderType.INITIAL) {
            text.append("Зберіг цей кошик як еталонний набір — далі буду порівнювати з ним, коли питатиму, ")
                    .append("що закінчилось.");
        } else {
            text.append("Еталонний набір лишаю як був.");
        }
        if (bonusesApplied) {
            text.append("\nСписав бонусів: ")
                    .append(amount(summary.bonusAvailable()))
                    .append('.');
        }
        text.append("\n\nОплата — на боці «Сільпо».");
        text.append(checkoutFallbackLine(summary));
        return text.toString();
    }

    /**
     * A link button straight to checkout — the mobile deep link, which opens the Silpo app directly rather than a
     * browser, so it never hits the in-app-browser session friction a web link opened inside Telegram's own
     * browser can. Shared by every order-confirmation flow (task 10, 15, 19).
     */
    public List<TelegramButton> checkoutButtons(CartSummary summary) {
        return List.of(TelegramButton.link("Перейти до оплати", summary.checkoutMobileLink()));
    }

    /**
     * The web link, mentioned as a text fallback — the button above already covers the mobile case. Pulled out on
     * its own so every confirmation message (task 10, 15) uses identical wording instead of each spelling it out
     * separately.
     */
    public String checkoutFallbackLine(CartSummary summary) {
        return "\nАбо в браузері: " + summary.checkoutWebLink();
    }

    /** Said instead of the cart when the bonus call failed but the order went through anyway. */
    public String bonusesUnavailableText() {
        return "Бонуси списати не вдалось — оформив без них.";
    }

    /** Two decimals, always: a price with one is a typo to the eye. */
    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    /** Quantities and bonus counts read better without trailing zeros: {@code 1} rather than {@code 1.00}. */
    private static String amount(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.UNNECESSARY) : stripped).toPlainString();
    }
}
