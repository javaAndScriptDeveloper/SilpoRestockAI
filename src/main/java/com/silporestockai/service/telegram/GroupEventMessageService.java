package com.silporestockai.service.telegram;

import com.silporestockai.entity.GroupEvent;
import com.silporestockai.model.GroupProposal;
import com.silporestockai.model.GroupProposalLine;
import com.silporestockai.model.TelegramButton;
import com.silporestockai.service.GroupProposalService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Every string a group chat reads during a drinks round (task 68), and the callback payloads under its buttons.
 *
 * <p>Plain text, no parse mode: names come from people and a stray underscore in a username must not break a
 * message. Money and counts follow the same honesty as the cart message — a split is called arithmetic, never a
 * payment, and quantities are called approximate.
 */
@Service
public class GroupEventMessageService {

    public static final String CALLBACK_FREEZE_PREFIX = "grp:freeze:";
    public static final String CALLBACK_APPROVE_PREFIX = "grp:ok:";
    /** «🔄 Новий збір» under a finished round — the only way to open the next one without re-adding the bot. */
    public static final String CALLBACK_NEW_ROUND = "grp:new";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Said once, when the bot is added. Opens nothing: the round starts on the first tag. */
    /**
     * Said once, when the bot is added.
     *
     * @param seesEveryMessage whether Telegram will show this bot a plain mention at all — with privacy mode on
     *     (the default) it will not, and the one thing the person who just added the bot can do about it is
     *     make it an administrator, so the intro asks for exactly that
     */
    public String intro(boolean seesEveryMessage) {
        // Task 74: the ask is a shared order for the company, not a drinks run. Any mention with no round open
        // starts one, so the example phrase is free to be the general one.
        String tag = "Коли треба зібрати спільну закупку на компанію — тегни мене й попроси: «@бот збери на "
                + "п'ятницю, бюджет 2000». Хто попросить, той і організатор. Далі читаю лише реплаї на свої "
                + "повідомлення й свої кнопки.";
        if (seesEveryMessage) {
            return "Привіт! " + tag;
        }
        return "Привіт! Спершу зроби мене адміністратором групи — інакше Telegram не показує мені повідомлення з "
                + "тегом, і я не побачу прохання. " + tag;
    }

    public String intro() {
        return intro(true);
    }

    /** After an unhandled failure: the one action that works in this state, not a generic «try again». */
    public String recovery(boolean roundOpen) {
        if (roundOpen) {
            return "Щось пішло не так на моєму боці. Відповідай реплаєм на мою останню пропозицію (або на "
                    + "привітання, якщо пропозиції ще нема) «спробуй ще» — перерахую.";
        }
        return "Щось пішло не так на моєму боці. Тегни мене ще раз за хвилину.";
    }

    public String greeting(String organizerName) {
        return """
                Збираю спільну закупку на компанію — в кошик «Сільпо» організатора, оплата як зазвичай. \
                Поки що це напої: їжу на всіх ще не вмію.

                Кожен — відповідай реплаєм на це повідомлення, що п'єш: «пиво світле», «червоне вино», «не п'ю — сік». \
                Можна з поясненням: «сьогодні за кермом», «це на ДР». Крапка «.» — на мій розсуд.

                %s, ти організатор: коли всі відповіли — тисни кнопку нижче. Бюджет і привід (не обов'язково) — теж \
                реплаєм: «бюджет 2000, привід: новий рік, дата 31.12».

                Читаю тільки реплаї на свої повідомлення й свої кнопки. Решту розмови не чіпаю.""".formatted(organizerName);
    }

    public List<TelegramButton> greetingButtons(UUID eventId) {
        return List.of(TelegramButton.callback("✅ Всі відповіли", CALLBACK_FREEZE_PREFIX + eventId));
    }

    public String replyAck(String name, long count) {
        return "Записав, %s. Відповіли: %d.".formatted(name, count);
    }

    /**
     * Task 74: said to the person who just asked for food, in the moment they asked. The alternative was silence
     * until a proposal arrived with no crisps in it, which reads as the bot having ignored them.
     */
    public String drinksOnlyAck(String name, long count) {
        return "Записав, %s. Відповіли: %d. Тільки скажу чесно: поки що я збираю на компанію лише напої — їжу "
                        .formatted(name, count)
                + "доведеться взяти окремо.";
    }

    public String lateReplyAck(String name) {
        return "Записав, %s, але цей раунд уже закрито — у підрахунок не потрапить.".formatted(name);
    }

    public String settingsAck(GroupEvent event) {
        StringBuilder text = new StringBuilder("Прийняв:");
        if (event.getBudget() != null) {
            text.append(" бюджет ")
                    .append(event.getBudget().stripTrailingZeros().toPlainString())
                    .append(" грн ·");
        }
        if (event.getEventTag() != null) {
            text.append(" привід: ").append(event.getEventTag()).append(" ·");
        }
        if (event.getEventDate() != null) {
            text.append(" дата ").append(DATE.format(event.getEventDate())).append(" ·");
        }
        return text.substring(0, text.length() - 2);
    }

    public String freezeNotOrganizer() {
        return "Це кнопка організатора.";
    }

    public String nobodyReplied() {
        return "Поки ніхто не відповів — нема з чого рахувати.";
    }

    public String frozen(long headcount) {
        return "Закрив список: %d %s. Рахую пропозицію — хвилинку.".formatted(headcount, people(headcount));
    }

    public String alreadyClosed() {
        return "Список уже закрито.";
    }

    public String connectHint(String organizerName, Optional<String> botUsername) {
        String where = botUsername
                .map(name -> "у приваті зі мною: https://t.me/" + name)
                .orElse("у приваті зі мною");
        return "%s, щоб я зібрав кошик, підключи «Сільпо» %s — потім відповідай реплаєм на це повідомлення «збери кошик»."
                .formatted(organizerName, where);
    }

    /**
     * The proposal: the lines, the total against the budget, one line of rules. Short on purpose — the group reads
     * it on a phone between its own messages. The per-head split waits for the consensus message, where it is a
     * fact about a cart rather than a guess about a proposal; the model's note goes to the log; and the hint for a
     * revision names no drink, because a non-alcoholic round would make «менше пива» read as a joke.
     */
    public String proposal(GroupEvent event, GroupProposal proposal, int headcount, Optional<String> botUsername) {
        StringBuilder text = new StringBuilder();
        text.append("Пропозиція №")
                .append(proposal.version())
                .append(" на ")
                .append(headcount)
                .append(' ')
                .append(people(headcount))
                .append(" (кількості орієнтовні):\n");
        for (GroupProposalLine line : proposal.lines()) {
            text.append("\n— ").append(line.catalogName());
            if (line.quantity() != null) {
                text.append(" — ").append(amount(line.quantity()));
                if (line.unit() != null) {
                    text.append(' ').append(line.unit());
                }
            }
            if (line.lineCost() != null) {
                text.append(" — ").append(money(line.lineCost())).append(" грн");
            }
            if (line.forWhom() != null && !line.forWhom().isBlank()) {
                text.append(" (").append(line.forWhom()).append(')');
            }
        }
        if (!proposal.unresolved().isEmpty()) {
            text.append("\n\nНе знайшов у «Сільпо»: ").append(String.join(", ", proposal.unresolved()));
        }
        if (proposal.priced() && proposal.estimatedTotal() != null) {
            text.append("\n\nРазом орієнтовно ~")
                    .append(money(proposal.estimatedTotal()))
                    .append(" грн");
            if (event.getBudget() != null) {
                BigDecimal over = proposal.estimatedTotal().subtract(event.getBudget());
                text.append(" — бюджет ").append(amount(event.getBudget())).append(" грн, ");
                text.append(
                        over.signum() > 0
                                ? "на " + money(over) + " грн більше за бюджет — скажи, що прибрати"
                                : "вкладаємось");
            }
        } else if (!proposal.priced() && proposal.catalogUnavailable()) {
            text.append("\n\nБез цін: «Сільпо» щойно не відповів. Відповідай реплаєм «спробуй ще» — перерахую з ")
                    .append("цінами, або погоджуйся так: ціни будуть у кошику організатора.");
        } else if (!proposal.priced()) {
            text.append("\n\nБез цін: у організатора ще не підключено «Сільпо». Ціни з'являться, щойно підключить.");
        }
        text.append("\n\n👍 — згоден. Змінити — відповідай реплаєм на це повідомлення, що прибрати чи додати ")
                .append("(усі 👍 обнуляться).");
        return text.toString();
    }

    public List<TelegramButton> proposalButtons(UUID eventId, int version) {
        return List.of(TelegramButton.callback("👍 Погоджуюсь", CALLBACK_APPROVE_PREFIX + eventId + ":" + version));
    }

    public String proposalFailed() {
        return "Не зміг скласти пропозицію. Відповідай реплаєм на це повідомлення «спробуй ще» — перерахую.";
    }

    /** A reply to the proposal from somebody outside the frozen set. */
    public String revisionNotCounted() {
        return "Правки приймаю лише від тих, хто в цьому раунді — ти відповів після закриття списку.";
    }

    public String revising(String name) {
        return "Прийняв правку від %s. Перераховую — усі 👍 обнулено.".formatted(name);
    }

    public String approvalToast(long approved, long total) {
        return "Погодились: %d з %d.".formatted(approved, total);
    }

    public String alreadyApproved(long approved, long total) {
        return "Ти вже погодився. Погодились: %d з %d.".formatted(approved, total);
    }

    public String notCounted() {
        return "Ти не у списку цього раунду — відповідь прийшла після закриття.";
    }

    public String staleProposal() {
        return "Це стара пропозиція — нижче є новіша.";
    }

    public String consensus(GroupProposal proposal, String organizerName, long headcount) {
        StringBuilder text = new StringBuilder();
        text.append("✅ Усі ")
                .append(headcount)
                .append(" погодились. Поклав у кошик «Сільпо» ")
                .append(organizerName)
                .append(':');
        for (GroupProposalLine line : proposal.resolvedLines()) {
            text.append("\n— ").append(line.catalogName());
            if (line.quantity() != null) {
                text.append(" — ").append(amount(line.quantity()));
                if (line.unit() != null) {
                    text.append(' ').append(line.unit());
                }
            }
        }
        if (proposal.estimatedTotal() != null) {
            text.append("\n\nРазом ~").append(money(proposal.estimatedTotal())).append(" грн");
            BigDecimal split = GroupProposalService.perHead(proposal.estimatedTotal(), (int) headcount);
            if (split != null) {
                text.append(" — це ~")
                        .append(money(split))
                        .append(" грн з людини, якщо ділити на ")
                        .append(headcount)
                        .append(" порівну. Просто арифметика: платить організатор, я нічого не збираю.");
            }
        }
        text.append("\n\n").append(organizerName).append(", кошик і оплата — у нас у приваті.");
        return text.toString();
    }

    public String cartFailed(String organizerName) {
        return ("✅ Усі погодились, але кошик не зібрався — %s, деталі у приваті зі мною. "
                        + "Спробувати ще: відповідай реплаєм на це повідомлення «збери кошик».")
                .formatted(organizerName);
    }

    /** Under the consensus, the ordered and the failed-cart messages: the next round is one tap away. */
    public List<TelegramButton> newRoundButtons() {
        return List.of(TelegramButton.callback("🔄 Новий збір", CALLBACK_NEW_ROUND));
    }

    public String nothingResolved(String organizerName) {
        return "✅ Усі погодились, але жодної позиції не знайшлось у «Сільпо» — %s, напиши мені у приваті, що взяти."
                .formatted(organizerName);
    }

    public String ordered(String organizerName) {
        return "🎉 %s підтвердив замовлення в «Сільпо». Напої їдуть.".formatted(organizerName);
    }

    public String noActiveRound() {
        return "Зараз немає відкритого збору — тегни мене й попроси, або тисни «🔄 Новий збір» під останнім підсумком.";
    }

    public String alreadyAgreed(String organizerName) {
        return "Усе вже погоджено — %s оформлює замовлення у приваті зі мною.".formatted(organizerName);
    }

    private static String people(long n) {
        long tail = n % 10;
        long hundred = n % 100;
        if (hundred >= 11 && hundred <= 14) {
            return "людей";
        }
        if (tail == 1) {
            return "людину";
        }
        if (tail >= 2 && tail <= 4) {
            return "людини";
        }
        return "людей";
    }

    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static String amount(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.UNNECESSARY) : stripped).toPlainString();
    }
}
