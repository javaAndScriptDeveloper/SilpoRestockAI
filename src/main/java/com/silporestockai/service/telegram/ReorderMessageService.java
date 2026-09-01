package com.silporestockai.service.telegram;

import com.silporestockai.model.CartSummary;
import com.silporestockai.model.DeltaOrder;
import com.silporestockai.model.OfferedSlot;
import com.silporestockai.model.ReplacementOption;
import com.silporestockai.model.ReplacementSuggestion;
import com.silporestockai.model.TelegramButton;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * How a delta reorder reads, and which buttons come with it.
 *
 * <p>Substitutes get a pair of buttons each rather than one accept-all: the point of asking is that somebody can take
 * the different bread and refuse the different milk.
 */
@Service
public class ReorderMessageService {

    public static final String CALLBACK_ACCEPT_PREFIX = "re:acc:";
    public static final String CALLBACK_REJECT_PREFIX = "re:rej:";
    public static final String CALLBACK_SLOT_MENU = "re:slot";
    public static final String CALLBACK_SLOT_PREFIX = "re:slot:";
    public static final String CALLBACK_CONFIRM = "re:confirm";
    public static final String CALLBACK_CANCEL = "re:cancel";

    /** The order as it currently stands, decisions included. */
    public String orderText(DeltaOrder order, OfferedSlot slot, Map<Integer, Boolean> decisions) {
        StringBuilder text = new StringBuilder("Час докупити. Ось що зібрав:\n");
        for (String name : order.reordered()) {
            text.append("\n— ").append(name);
        }
        if (!order.excluded().isEmpty()) {
            text.append("\n\nНе беру, бо їх стабільно не їдять: ")
                    .append(String.join(", ", order.excluded()))
                    .append('.');
        }
        List<ReplacementSuggestion> suggestions = order.pendingReplacements();
        for (int i = 0; i < suggestions.size(); i++) {
            ReplacementSuggestion suggestion = suggestions.get(i);
            text.append("\n\n").append(suggestion.requestedName()).append(" — немає. ");
            if (suggestion.options().isEmpty()) {
                text.append("Заміни теж немає.");
                continue;
            }
            ReplacementOption option = suggestion.options().getFirst();
            text.append("Можу взяти: ").append(option.name());
            if (option.price() != null) {
                text.append(", ").append(money(option.price())).append(" грн");
            }
            Boolean decision = decisions.get(i);
            if (decision != null) {
                text.append(decision ? " — беру." : " — не беру.");
            }
        }
        if (order.estimatedSavings() != null && order.estimatedSavings().signum() > 0) {
            text.append("\n\nНа акціях економимо приблизно ")
                    .append(money(order.estimatedSavings()))
                    .append(" грн.");
        }
        text.append("\n\nДоставка: ").append(slot == null ? "слот ще не обрано" : slot.label());
        return text.toString();
    }

    /**
     * A pair of buttons per undecided substitute, then the slot and the decision.
     *
     * <p>Confirm stays available the whole time. An undecided substitute is simply not bought, which is the safe
     * reading of silence.
     */
    public List<TelegramButton> orderButtons(DeltaOrder order, Map<Integer, Boolean> decisions) {
        List<TelegramButton> buttons = new ArrayList<>();
        List<ReplacementSuggestion> suggestions = order.pendingReplacements();
        for (int i = 0; i < suggestions.size(); i++) {
            if (decisions.containsKey(i) || suggestions.get(i).options().isEmpty()) {
                continue;
            }
            String name = suggestions.get(i).requestedName();
            buttons.add(TelegramButton.callback("Взяти замість «%s»".formatted(name), CALLBACK_ACCEPT_PREFIX + i));
            buttons.add(TelegramButton.callback("Без «%s»".formatted(name), CALLBACK_REJECT_PREFIX + i));
        }
        buttons.add(TelegramButton.callback("Інший слот", CALLBACK_SLOT_MENU));
        buttons.add(TelegramButton.callback("Підтвердити", CALLBACK_CONFIRM));
        buttons.add(TelegramButton.callback("Скасувати", CALLBACK_CANCEL));
        return buttons;
    }

    public String slotMenuText() {
        return "Коли зручно прийняти доставку?";
    }

    public List<TelegramButton> slotButtons(List<OfferedSlot> slots) {
        List<TelegramButton> buttons = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            buttons.add(TelegramButton.callback(slots.get(i).label(), CALLBACK_SLOT_PREFIX + i));
        }
        return buttons;
    }

    /** Said once the reorder is stored. Payment stays on Silpo's own page, as with the first order. */
    public String confirmedText(CartSummary cart, BigDecimal savings, boolean slotFixed, boolean baselineUpdated) {
        StringBuilder text = new StringBuilder("Підтвердив. ")
                .append(cart.items().size())
                .append(" позицій, разом ")
                .append(money(cart.total()))
                .append(" грн.");
        if (savings != null && savings.signum() > 0) {
            text.append("\nЗаощадили на акціях приблизно ")
                    .append(money(savings))
                    .append(" грн.");
        }
        if (!slotFixed) {
            text.append("\nСлот доставки зафіксувати не вдалось — оберіть його на сторінці оформлення.");
        }
        if (baselineUpdated) {
            text.append("\nВаші правки врахував — далі орієнтуюсь на цей набір.");
        }
        text.append("\n\nОплата — на боці «Сільпо».");
        if (cart.checkoutMobileLink() != null) {
            text.append("\nУ застосунку: ").append(cart.checkoutMobileLink());
        }
        return text.toString();
    }

    public String cancelledText() {
        return "Скасував. Нічого не замовляю — скажи, коли буде треба.";
    }

    public String nothingToOrderText() {
        return "Поки нічого докуповувати — за останнім чек-іном усе на місці.";
    }

    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
    }
}
