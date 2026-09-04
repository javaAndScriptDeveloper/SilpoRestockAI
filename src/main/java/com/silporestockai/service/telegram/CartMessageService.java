package com.silporestockai.service.telegram;

import com.silporestockai.model.BasketItem;
import com.silporestockai.model.CartSummary;
import com.silporestockai.model.TelegramButton;
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
    public static final String CALLBACK_CANCEL = "cart:cancel";

    /** The cart itself: what is in it, what could not be found, what Silpo warned about, what it costs. */
    public String cartText(CartSummary summary) {
        StringBuilder text = new StringBuilder("Зібрав кошик на тиждень:\n");
        for (BasketItem item : summary.items()) {
            text.append("\n— ").append(item.name());
            if (item.quantity() != null) {
                text.append(" — ").append(amount(item.quantity()));
                if (item.unit() != null) {
                    text.append(' ').append(item.unit());
                }
            }
            if (item.price() != null) {
                text.append(" — ").append(money(item.price())).append(" грн");
            }
        }
        if (!summary.unresolved().isEmpty()) {
            text.append("\n\nНе знайшов: ")
                    .append(String.join(", ", summary.unresolved()))
                    .append(" — можете додати вручну пізніше.");
        }
        for (String validation : summary.validations()) {
            text.append("\n⚠ ").append(validation);
        }
        text.append("\n\nРазом: ").append(money(summary.total())).append(" грн");
        if (summary.bonusDecisionPending()) {
            text.append("\nНа рахунку ")
                    .append(amount(summary.bonusAvailable()))
                    .append(" бонусів — можу списати їх на це замовлення.");
        }
        return text.toString();
    }

    /**
     * Confirm, cancel, and — only when there is a decision to make — a confirm that spends the bonuses.
     *
     * <p>The bonus question is asked by offering a second confirm rather than by sending a separate message: one tap
     * answers both questions, and there is only one state to make idempotent instead of two.
     */
    public List<TelegramButton> cartButtons(CartSummary summary) {
        List<TelegramButton> buttons = new ArrayList<>();
        buttons.add(TelegramButton.callback("Підтвердити", CALLBACK_CONFIRM));
        if (summary.bonusDecisionPending()) {
            buttons.add(TelegramButton.callback(
                    "Підтвердити + %s бонусів".formatted(amount(summary.bonusAvailable())), CALLBACK_CONFIRM_BONUS));
        }
        buttons.add(TelegramButton.callback("Скасувати", CALLBACK_CANCEL));
        return buttons;
    }

    /** Said once the order is stored. Payment is Silpo's page, not ours — there is no MCP payment tool. */
    public String confirmedText(CartSummary summary, boolean bonusesApplied) {
        StringBuilder text = new StringBuilder("Підтвердив. Зберіг цей кошик як еталонний набір — далі буду ")
                .append("порівнювати з ним, коли питатиму, що закінчилось.");
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
