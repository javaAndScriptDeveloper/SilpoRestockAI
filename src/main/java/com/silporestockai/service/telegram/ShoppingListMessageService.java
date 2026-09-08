package com.silporestockai.service.telegram;

import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.model.PriceEstimate;
import com.silporestockai.model.ShoppingListDelta;
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
    public static final String CALLBACK_SHOW_FULL = "list:full";

    private static final String LIST_CALLBACK_PREFIX = "list:";
    private static final String ITEM_CALLBACK_PREFIX = "sli:";

    private static final String UNCATEGORIZED = "Інше";

    /**
     * Whether a tap belongs to a shopping list keyboard.
     *
     * <p>Every one of these is self-contained: {@code list:*} acts on whichever list is live for that user right
     * now, and {@code sli:*} carries its own item id. None of them needs {@code conversation_state} to be
     * interpreted, which is what lets the routing layer dispatch them globally rather than only while the list
     * flow happens to be the active one.
     */
    public static boolean isListCallback(String data) {
        return data != null && (data.startsWith(LIST_CALLBACK_PREFIX) || data.startsWith(ITEM_CALLBACK_PREFIX));
    }

    /** The opening ask. Three ways in, because people have different things to hand. */
    public String askForInputText() {
        return """
                Що беремо на цей тиждень? Обери, як тобі зручніше:

                — надішли фото холодильника чи полиці, і я подивлюсь, чого бракує;
                — надішли фото чека з будь-якого магазину — зберу схожий набір (приблизно: назви підбираю по каталогу «Сільпо», точні товари з чужого чека не переносяться);
                — напиши «зроби список як минулого разу» — візьму за основу твоє реальне замовлення в «Сільпо», товар у товар;
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

    /**
     * −/+ for one item, wired to {@code ShoppingListBuilderService}'s manual-edit handler. No explicit delete
     * button — reducing quantity to zero removes the item.
     */
    public List<TelegramButton> itemButtons(ShoppingListItem item) {
        return List.of(
                TelegramButton.callback("−", CALLBACK_ITEM_DEC_PREFIX + item.getId()),
                TelegramButton.callback("+", CALLBACK_ITEM_INC_PREFIX + item.getId()));
    }

    /**
     * The list itself, grouped by category rather than as one flat block — one section per category, in the order
     * each was first seen.
     *
     * <p>The price line (task 39) is a rough number from data already in hand, said as such: a person deciding
     * whether to tap «Замовити» deserves a sense of the bill before a cart exists. When only some lines could be
     * priced the message says how many, rather than presenting a partial sum as the whole.
     */
    public String listText(List<ShoppingListItem> items, PriceEstimate estimate) {
        StringBuilder text = new StringBuilder("Ось що пропоную взяти:\n");
        categorized(items).forEach((category, categoryItems) -> {
            text.append('\n').append(categoryText(category, categoryItems)).append('\n');
        });
        text.append("\nВсього ")
                .append(items.size())
                .append(' ')
                .append(positions(items.size()))
                .append('.');
        if (estimate != null && estimate.hasPrices()) {
            text.append('\n').append(estimateLine(estimate));
        }
        text.append("\nЯкщо все влаштовує — замовляю. Якщо ні — скажи, що змінити, або зміни вручну.");
        return text.toString();
    }

    /** «Орієнтовно ~1234.50 грн — точну суму покажу в кошику.», with «за 5 з 12 позицій» when it is partial. */
    public String estimateLine(PriceEstimate estimate) {
        StringBuilder line = new StringBuilder("Орієнтовно ~")
                .append(money(estimate.total()))
                .append(" грн");
        if (estimate.isPartial()) {
            // «з 23 позицій» — genitive plural after «з», whatever the number; positions() declines a nominative.
            line.append(" за ")
                    .append(estimate.pricedCount())
                    .append(" з ")
                    .append(estimate.lineCount())
                    .append(" позицій");
        }
        return line.append(" — точну суму покажу в кошику.").toString();
    }

    /** Two decimals, always, as the cart message does — a price with one reads as a typo. */
    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    /**
     * A bulk AI-triggered change (task 21): what changed, not the whole list again. {@code
     * "Показати весь список"} is the only button — the reader hasn't seen the full current list yet, so
     * letting them order/edit straight from this view would mean agreeing to something they haven't seen.
     */
    public String deltaText(ShoppingListDelta delta) {
        StringBuilder text =
                new StringBuilder("Оновив раціон: ").append(summaryLine(delta)).append('.');
        if (!delta.added().isEmpty()) {
            text.append("\n\nДодано:");
            delta.added()
                    .forEach(line -> text.append("\n+ ").append(lineText(line.name(), line.quantity(), line.unit())));
        }
        if (!delta.removed().isEmpty()) {
            text.append("\n\nПрибрано:");
            delta.removed()
                    .forEach(line -> text.append("\n− ").append(lineText(line.name(), line.quantity(), line.unit())));
        }
        if (!delta.quantityChanged().isEmpty()) {
            text.append("\n\nЗмінено кількість:");
            delta.quantityChanged()
                    .forEach(change -> text.append("\n— ")
                            .append(change.name())
                            .append(": ")
                            .append(amount(change.oldQuantity()))
                            .append(" → ")
                            .append(amount(change.newQuantity()))
                            .append(change.unit() == null ? "" : " " + change.unit()));
        }
        return text.toString();
    }

    public List<TelegramButton> deltaButtons() {
        return List.of(TelegramButton.callback("Показати весь список", CALLBACK_SHOW_FULL));
    }

    /** 1-2 total changes (task 21): shown inline, no summary header, no "show full list" button. */
    public String trivialDeltaText(ShoppingListDelta delta) {
        List<String> parts = new ArrayList<>();
        delta.added().forEach(line -> parts.add("+ " + lineText(line.name(), line.quantity(), line.unit())));
        delta.removed().forEach(line -> parts.add("− " + lineText(line.name(), line.quantity(), line.unit())));
        delta.quantityChanged()
                .forEach(change -> parts.add(change.name() + ": " + amount(change.oldQuantity()) + " → "
                        + amount(change.newQuantity()) + (change.unit() == null ? "" : " " + change.unit())));
        return "Оновив: " + String.join("; ", parts) + ".";
    }

    private static String summaryLine(ShoppingListDelta delta) {
        List<String> parts = new ArrayList<>();
        if (!delta.added().isEmpty()) {
            parts.add("+" + delta.added().size() + " " + positions(delta.added().size()));
        }
        if (!delta.removed().isEmpty()) {
            parts.add("-" + delta.removed().size() + " "
                    + positions(delta.removed().size()));
        }
        if (!delta.quantityChanged().isEmpty()) {
            parts.add("змінено кількість у " + delta.quantityChanged().size());
        }
        String summary = String.join(", ", parts);
        return delta.unchangedCount() == 0 ? summary : summary + " (" + delta.unchangedCount() + " без змін)";
    }

    private static String lineText(String name, BigDecimal quantity, String unit) {
        if (quantity == null) {
            return name;
        }
        return name + " — " + amount(quantity) + (unit == null ? "" : " " + unit);
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

    /** Precedes the per-item −/+ messages of the manual-edit view. No AI call happens past this point. */
    public String manualEditIntroText() {
        return "Онови кожну позицію окремо:";
    }

    public String buildingText() {
        return "Хвилинку, складаю список.";
    }

    /**
     * Said the moment «Замовити» is tapped, before any of the work starts.
     *
     * <p>Building a cart is a search of the whole catalogue, a product choice per line and several Silpo calls —
     * up to a minute. Without this the tap looked like it had done nothing at all, and people tapped again, which
     * started a second cart build over the top of the first. The honest "about a minute" is the point.
     */
    public String buildingCartText() {
        return "Збираю кошик у «Сільпо» — шукаю кожну позицію в каталозі. Це займе до хвилини.";
    }

    /** Under a failed cart build: the list is still there, and this is the tap that tries again. */
    public String retryOrderText() {
        return "Список я зберіг. Спробувати зібрати кошик ще раз?";
    }

    public List<TelegramButton> retryOrderButtons() {
        return List.of(
                TelegramButton.callback("Спробувати ще раз", CALLBACK_ORDER),
                TelegramButton.callback("Змінити список", CALLBACK_EDIT));
    }

    /** A second «Замовити» while the first is still working. Silence here read as a dead button. */
    public String stillBuildingCartText() {
        return "Ще збираю попередній кошик — зачекай хвилинку, він зараз буде.";
    }

    public String couldNotBuildText() {
        return "Не вдалось скласти список. Спробуй описати інакше або надішли фото.";
    }

    public String cancelledText() {
        return "Скасував. Натисни «Список», коли будемо збирати новий.";
    }

    /** «1 позиція», «3 позиції», «12 позицій» — the wrong one reads like a machine wrote it. */
    /** «23 позиції», «11 позицій», «1 позиція» — the nominative that follows a bare count. */
    public static String positions(int count) {
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
