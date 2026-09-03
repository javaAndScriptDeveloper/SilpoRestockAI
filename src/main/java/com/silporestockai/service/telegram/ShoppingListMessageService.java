package com.silporestockai.service.telegram;

import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.model.TelegramButton;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** The list a person reads before anything is ordered, and the four things they can do about it. */
@Service
public class ShoppingListMessageService {

    public static final String CALLBACK_ORDER = "list:order";
    public static final String CALLBACK_EDIT = "list:edit";
    public static final String CALLBACK_CANCEL = "list:cancel";
    public static final String CALLBACK_MANUAL_EDIT = "list:manual";
    public static final String CALLBACK_ITEM_DEC_PREFIX = "sli:dec:";
    public static final String CALLBACK_ITEM_INC_PREFIX = "sli:inc:";
    public static final String CALLBACK_ITEM_DEL_PREFIX = "sli:del:";

    private static final String UNCATEGORIZED = "Інше";

    /** The opening ask. Three ways in, because people have different things to hand. */
    public String askForInputText() {
        return """
                Що беремо на цей тиждень? Обери, як тобі зручніше:

                — надішли фото холодильника чи полиці, і я подивлюсь, чого бракує;
                — надішли фото чека, і я зберу схожий набір;
                — або просто напиши, що потрібно чи якої дієти тримаєшся.""";
    }

    /** Items grouped by category, in the order each category was first seen. */
    public Map<String, List<ShoppingListItem>> categorized(List<ShoppingListItem> items) {
        Map<String, List<ShoppingListItem>> grouped = new LinkedHashMap<>();
        for (ShoppingListItem item : items) {
            String category =
                    item.getCategory() == null || item.getCategory().isBlank() ? UNCATEGORIZED : item.getCategory();
            grouped.computeIfAbsent(category, ignored -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    /** One category's block: a heading line, then one line per item. */
    public String categoryText(String category, List<ShoppingListItem> items) {
        StringBuilder text = new StringBuilder(category).append(':');
        for (ShoppingListItem item : items) {
            text.append("\n— ").append(item.getName());
            if (item.getQuantity() != null) {
                text.append(" — ").append(amount(item.getQuantity()));
                if (item.getUnit() != null) {
                    text.append(' ').append(item.getUnit());
                }
            }
        }
        return text.toString();
    }

    /** −/+/✕ for one item, wired to {@code ShoppingListBuilderService}'s manual-edit handler. */
    public List<TelegramButton> itemButtons(ShoppingListItem item) {
        return List.of(
                TelegramButton.callback("−", CALLBACK_ITEM_DEC_PREFIX + item.getId()),
                TelegramButton.callback("+", CALLBACK_ITEM_INC_PREFIX + item.getId()),
                TelegramButton.callback("✕", CALLBACK_ITEM_DEL_PREFIX + item.getId()));
    }

    /**
     * The list itself, grouped by category rather than as one flat block — one section per category, in the order
     * each was first seen.
     */
    public String listText(List<ShoppingListItem> items) {
        StringBuilder text = new StringBuilder("Ось що пропоную взяти:\n");
        categorized(items).forEach((category, categoryItems) -> {
            text.append('\n').append(categoryText(category, categoryItems)).append('\n');
        });
        text.append("\nВсього ").append(items.size()).append(' ').append(positions(items.size()));
        text.append(".\nЯкщо все влаштовує — замовляю. Якщо ні — скажи, що змінити, або зміни вручну.");
        return text.toString();
    }

    public List<TelegramButton> listButtons() {
        return List.of(
                TelegramButton.callback("Замовити", CALLBACK_ORDER),
                TelegramButton.callback("Змінити", CALLBACK_EDIT),
                TelegramButton.callback("Змінити вручну", CALLBACK_MANUAL_EDIT),
                TelegramButton.callback("Скасувати", CALLBACK_CANCEL));
    }

    /** Editing is a sentence: twenty items would otherwise mean twenty buttons. */
    public String askForEditText() {
        return "Напиши, що змінити. Наприклад: «прибери банани, додай хліб і яйця, молока більше».";
    }

    /** Precedes the per-item −/+/✕ messages of the manual-edit view. No AI call happens past this point. */
    public String manualEditIntroText() {
        return "Онови кожну позицію окремо:";
    }

    public String buildingText() {
        return "Хвилинку, складаю список.";
    }

    public String couldNotBuildText() {
        return "Не вдалось скласти список. Спробуй описати інакше або надішли фото.";
    }

    public String cancelledText() {
        return "Скасував. Напиши /list, коли будемо збирати список.";
    }

    /** «1 позиція», «3 позиції», «12 позицій» — the wrong one reads like a machine wrote it. */
    private static String positions(int count) {
        int lastTwo = count % 100;
        int last = count % 10;
        if (lastTwo >= 11 && lastTwo <= 14) {
            return "позицій";
        }
        if (last == 1) {
            return "позиція";
        }
        if (last >= 2 && last <= 4) {
            return "позиції";
        }
        return "позицій";
    }

    private static String amount(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.UNNECESSARY) : stripped).toPlainString();
    }
}
