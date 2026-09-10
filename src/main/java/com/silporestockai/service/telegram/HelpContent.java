package com.silporestockai.service.telegram;

/**
 * The one place the bot's «що я вмію» copy lives.
 *
 * <p>It is rendered twice: in full by the «❓ Інструкція» button (task 31), and as a five-line teaser pushed once
 * right after a household's first plan (task 70). Both read the same constants on purpose — a capability whose
 * wording is maintained in two files is a capability that will eventually be described two different ways.
 */
public final class HelpContent {

    private static final String BUTTONS = """
            Кнопки внизу:
            📝 Список — поточний список покупок: замовити або змінити.
            📦 Замовлення — що з останнім замовленням: статус, сума, час доставки.
            🗓 Заплановані — разові замовлення, які ще не виконав: змінити або скасувати.
            🧾 Анкета — склад сім'ї, дієта, бюджет.
            ❓ Інструкція — це повідомлення.
            💬 Фідбек — напиши нам, що не так або що покращити; одне повідомлення, без обробки.""";

    public static final String EXAMPLE_AD_HOC_ORDER =
            "— «Замов до п'ятниці вино та сир зі знижкою» — разове замовлення поза тижневим планом.";
    public static final String EXAMPLE_TOP_UP = "— «Що треба докупити?» — зберу дозамовлення того, що закінчується.";
    public static final String EXAMPLE_LIST_EDIT =
            "— «Прибери молоко зі списку, додай яйця» — правка поточного списку.";
    public static final String EXAMPLE_LIKE_LAST_TIME =
            "— «Зроби список як минулого разу» — покажу твої останні замовлення в «Сільпо», візьму обране за основу.";
    public static final String EXAMPLE_ORDER_STATUS =
            "— «Де моє замовлення?» — статус і час доставки останнього замовлення (те саме, що кнопка «Замовлення»).";
    public static final String EXAMPLE_DISH =
            "— «Замов усе для карбонари» (або фото готової страви з таким підписом) — зберу інгредієнти на одну страву.";
    public static final String EXAMPLE_SPECIAL_MODE =
            "— «Я захворів, гастрит» — тимчасово щадне харчування, потім сам поверну звичайне.";
    public static final String EXAMPLE_LOWER_CALORIES = "— «Зроби менш калорійним» — той самий раціон, менше калорій.";
    public static final String EXAMPLE_BULK = "— «Хочу набрати масу» — план під набір маси.";
    /** Task 67: the mode is only reachable by saying it, so the instruction has to be where a person looks. */
    public static final String EXAMPLE_CRUNCH_WEEK = "— «Цей тиждень нема часу готувати» — на тиждень переходжу "
            + "на готову їжу, потім сам повертаю як було. Анкету не чіпаю.";

    public static final String EXAMPLE_BACK_TO_NORMAL =
            "— «Повертаємось до звичайного раціону» — вимкнути будь-який спецрежим.";
    public static final String EXAMPLE_UA_ONLY =
            "— «Шукай тільки українського виробника» — фільтр на всі наступні пошуки.";
    public static final String EXAMPLE_HANGOVER =
            "— «Голова після вчорашнього» — мінералка й сорбенти, найближча доставка.";
    public static final String EXAMPLE_BLACKOUT = "— «Світло вимкнули» — їжа без плити й холодильника.";
    public static final String EXAMPLE_WEEKDAY = "— «Що їмо в середу?» — раціон по днях.";
    public static final String EXAMPLE_CALENDAR = "— «Підключи Google Календар» — вноситиму доставки в календар.";
    public static final String EXAMPLE_BENEFITS = "— «Які в мене купони й бонуси?» — покажу баланс, сертифікати та "
            + "промокоди (їх застосую сам при замовленні) і купони «Сільпо» (їх вмикають у застосунку).";

    /**
     * Task 74: the headline sells the mechanism, and the mechanism is a company agreeing on one order together.
     * The live copy said «зберу напої на всіх» and read back as «режим бухати» — a consensus feature undersold
     * as a party trick. What the round actually resolves gets a sentence of its own rather than the headline:
     * the limit is real (task 68 stopped at drinks deliberately — what a company may eat is an allergy question,
     * and a group chat is not where that consent can be taken), and leaving it out of the pitch would be the
     * other kind of dishonesty.
     */
    private static final String GROUP = """
            Компанією: додай мене в груповий чат — зберу спільну закупку на всіх за згодою кожного. Кожен пише \
            реплаєм, що йому взяти, організатор закриває список, я пропоную, усі тиснуть 👍 — і кошик у «Сільпо» \
            організатора. Поки що збираю напої; їжу на компанію — ще ні.""";

    /** Everything, behind the «❓ Інструкція» button. */
    public static final String FULL = BUTTONS
            + "\n\nУсе інше — просто напиши. Наприклад:\n"
            + String.join(
                    "\n",
                    EXAMPLE_AD_HOC_ORDER,
                    EXAMPLE_TOP_UP,
                    EXAMPLE_LIST_EDIT,
                    EXAMPLE_LIKE_LAST_TIME,
                    EXAMPLE_ORDER_STATUS,
                    EXAMPLE_DISH,
                    EXAMPLE_SPECIAL_MODE,
                    EXAMPLE_LOWER_CALORIES,
                    EXAMPLE_BULK,
                    EXAMPLE_CRUNCH_WEEK,
                    EXAMPLE_BACK_TO_NORMAL,
                    EXAMPLE_UA_ONLY,
                    EXAMPLE_HANGOVER,
                    EXAMPLE_BLACKOUT,
                    EXAMPLE_WEEKDAY,
                    EXAMPLE_CALENDAR,
                    EXAMPLE_BENEFITS)
            + "\n\n"
            + GROUP;

    /**
     * The teaser pushed once after the first plan. Five lines, not fifteen: it is read at a moment nobody asked
     * for it, so it has to be skimmable, and the reference stays one button away.
     */
    public static final String REVEAL = "Поки що ти бачив тільки тижневий план. Я розумію й звичайні прохання — "
            + "просто напиши:\n"
            + String.join(
                    "\n", EXAMPLE_AD_HOC_ORDER, EXAMPLE_LIST_EDIT, EXAMPLE_DISH, EXAMPLE_SPECIAL_MODE, EXAMPLE_BLACKOUT)
            + "\n\nПовний список — кнопка «❓ Інструкція» внизу.";

    private HelpContent() {}
}
