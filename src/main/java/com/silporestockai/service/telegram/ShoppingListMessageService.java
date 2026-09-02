package com.silporestockai.service.telegram;

import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.model.TelegramButton;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;

/** The list a person reads before anything is ordered, and the three things they can do about it. */
@Service
public class ShoppingListMessageService {

    public static final String CALLBACK_ORDER = "list:order";
    public static final String CALLBACK_EDIT = "list:edit";
    public static final String CALLBACK_CANCEL = "list:cancel";

    /** The opening ask. Three ways in, because people have different things to hand. */
    public String askForInputText() {
        return """
                Що беремо на цей тиждень? Обери, як тобі зручніше:

                — надішли фото холодильника чи полиці, і я подивлюсь, чого бракує;
                — надішли фото чека, і я зберу схожий набір;
                — або просто напиши, що потрібно чи якої дієти тримаєшся.""";
    }

    /** The list itself. Plain lines, because this is the message a person actually checks. */
    public String listText(List<ShoppingListItem> items) {
        StringBuilder text = new StringBuilder("Ось що пропоную взяти:\n");
        for (ShoppingListItem item : items) {
            text.append("\n— ").append(item.getName());
            if (item.getQuantity() != null) {
                text.append(" — ").append(amount(item.getQuantity()));
                if (item.getUnit() != null) {
                    text.append(' ').append(item.getUnit());
                }
            }
        }
        text.append("\n\nВсього ").append(items.size()).append(' ').append(positions(items.size()));
        text.append(".\nЯкщо все влаштовує — замовляю. Якщо ні — скажи, що змінити.");
        return text.toString();
    }

    public List<TelegramButton> listButtons() {
        return List.of(
                TelegramButton.callback("Замовити", CALLBACK_ORDER),
                TelegramButton.callback("Змінити", CALLBACK_EDIT),
                TelegramButton.callback("Скасувати", CALLBACK_CANCEL));
    }

    /** Editing is a sentence: twenty items would otherwise mean twenty buttons. */
    public String askForEditText() {
        return "Напиши, що змінити. Наприклад: «прибери банани, додай хліб і яйця, молока більше».";
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
