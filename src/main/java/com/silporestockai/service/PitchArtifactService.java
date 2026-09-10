package com.silporestockai.service;

import com.silporestockai.entity.IntentClassification;
import com.silporestockai.entity.McpToolCall;
import com.silporestockai.repository.IntentClassificationRepository;
import com.silporestockai.repository.McpToolCallRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The pitch artifact (task 55): one self-contained page proving the two judging criteria that cannot be proven
 * by talking — «Якість використання MCP» and «Агентність рішення».
 *
 * <p>Every number on it is counted off a row some real run wrote: {@code mcp_tool_call} (task 37) for the tool
 * matrix, {@code intent_classification} for the distribution. The only hand-written part is {@link #FLOW_NOTES}
 * — which flow reaches for a tool is knowledge, not data, and a tool the codebase has never heard of renders
 * with an empty note rather than an invented purpose.
 *
 * <p>The page is not served publicly from here. {@code make pitch-artifact} pulls it off a running app and
 * writes {@code src/main/resources/static/pitch.html}, which is committed and baked into the image: a snapshot
 * by construction rather than by promise, because during the demo window other people are testing the bot and
 * a page that re-counted on every request would move under the jury's eyes.
 */
@Service
@RequiredArgsConstructor
public class PitchArtifactService {

    /** Which flow reaches for each tool, and why. The one part of the page a human wrote. */
    public static final Map<String, String> FLOW_NOTES = flowNotes();

    /** What a person actually types to make each intent fire — so a judge reads speech, not an enum. */
    private static final Map<String, String> INTENT_EXAMPLES = intentExamples();

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm").withZone(ZoneId.of("Europe/Kyiv"));

    private final McpToolCallRepository mcpToolCallRepository;
    private final IntentClassificationRepository intentClassificationRepository;
    private final Clock clock;

    public String html() {
        return render(mcpToolCallRepository.findAll(), intentClassificationRepository.findAll(), clock.instant());
    }

    /**
     * The page, from two lists. Static for the same reason {@code MetricsService.compute} is: the counting is
     * then testable without a database, and the page is a pure function of what the run left behind.
     */
    public static String render(List<McpToolCall> calls, List<IntentClassification> intents, Instant generatedAt) {
        List<ToolRow> tools = toolRows(calls);
        List<IntentRow> distribution = intentRows(intents);
        long failedCalls = calls.stream().filter(McpToolCall::isError).count();
        long routed = intents.stream()
                .filter(i -> IntentLogService.ROUTED.equals(i.getOutcome()))
                .count();
        long unclassified = intents.stream()
                .filter(i -> IntentLogService.UNCLASSIFIED.equals(i.getOutcome()))
                .count();
        long failedIntents = intents.stream()
                .filter(i -> IntentLogService.FAILED.equals(i.getOutcome()))
                .count();

        StringBuilder page = new StringBuilder(16_384);
        page.append("""
                <!doctype html>
                <html lang="uk">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="robots" content="noindex">
                <title>Комора — що агент справді викликає</title>
                """);
        page.append("<style>").append(STYLE).append("</style>\n</head>\n<body>\n");

        page.append("<header>\n")
                .append("<p class=\"eyebrow\">Комора · Silpo AI Factory</p>\n")
                .append("<h1>Що агент справді викликає</h1>\n")
                .append("<p class=\"lede\">Знімок одного реального прогону через усі сценарії. Кожне число тут — ")
                .append("порахований рядок у базі, не цифра зі слайда: виклики MCP пише ")
                .append("<code>mcp_tool_call</code>, класифікації — <code>intent_classification</code>.</p>\n")
                .append("</header>\n");

        page.append("<section class=\"stats\">\n");
        stat(
                page,
                tools.size() + " з " + MetricsService.SILPO_MCP_TOOL_COUNT,
                "інструментів Silpo MCP задіяно",
                calls.size() + " " + plural(calls.size(), "виклик", "виклики", "викликів") + ", " + failedCalls
                        + " з помилкою");
        stat(
                page,
                String.valueOf(distribution.size()),
                "інтентів справді спрацювало",
                routed + " з " + intents.size() + " "
                        + plural(intents.size(), "класифікації", "класифікацій", "класифікацій") + " розпізнано");
        page.append("</section>\n");

        page.append("<section>\n<h2>MCP-інструменти</h2>\n")
                .append("<p class=\"note\">Порядок — за кількістю викликів. Нотатка каже, який флоу тягне ")
                .append("інструмент і навіщо.</p>\n")
                .append("<div class=\"scroll\"><table>\n")
                .append("<thead><tr><th>Інструмент</th><th class=\"num\">Викликів</th>")
                .append("<th class=\"num\">З помилкою</th><th>Де використовується</th></tr></thead>\n<tbody>\n");
        if (tools.isEmpty()) {
            page.append("<tr><td colspan=\"4\" class=\"empty\">Жодного виклику — прогону ще не було.</td></tr>\n");
        }
        for (ToolRow tool : tools) {
            page.append("<tr><td><code>")
                    .append(escape(tool.tool()))
                    .append("</code></td><td class=\"num\">")
                    .append(tool.calls())
                    .append("</td><td class=\"num")
                    .append(tool.failures() > 0 ? " bad" : "")
                    .append("\">")
                    .append(tool.failures())
                    .append("</td><td>")
                    .append(escape(FLOW_NOTES.getOrDefault(tool.tool(), "")))
                    .append("</td></tr>\n");
        }
        page.append("</tbody>\n</table></div>\n</section>\n");

        page.append("<section>\n<h2>Розподіл інтентів</h2>\n")
                .append("<p class=\"note\">Вільний текст у чаті класифікується і йде у той сервіс, який уже вміє ")
                .append("це робити. Нерозпізнані спроби — теж рядки: сторінка відповідає на питання про точність ")
                .append("до того, як його поставлять.</p>\n")
                .append("<div class=\"scroll\"><table>\n")
                .append("<thead><tr><th>Інтент</th><th class=\"num\">Разів</th><th class=\"num\">Частка</th>")
                .append("<th>Як це звучить у чаті</th></tr></thead>\n<tbody>\n");
        if (distribution.isEmpty()) {
            page.append("<tr><td colspan=\"4\" class=\"empty\">Жодної класифікації — прогону ще не було.</td></tr>\n");
        }
        for (IntentRow row : distribution) {
            page.append("<tr><td><code>")
                    .append(escape(row.intent()))
                    .append("</code></td><td class=\"num\">")
                    .append(row.count())
                    .append("</td><td class=\"num\">")
                    .append(share(row.count(), intents.size()))
                    .append("</td><td>")
                    .append(escape(INTENT_EXAMPLES.getOrDefault(row.intent(), "")))
                    .append("</td></tr>\n");
        }
        page.append("</tbody>\n</table></div>\n")
                .append("<p class=\"note\">Розпізнано: ")
                .append(routed)
                .append(" з ")
                .append(intents.size())
                .append(" · не розпізнано: ")
                .append(unclassified)
                .append(" · збій класифікатора: ")
                .append(failedIntents)
                .append("</p>\n</section>\n");

        page.append("<footer>\n<p>Знімок за ")
                .append(STAMP.format(generatedAt))
                .append(" (Київ). Це не живий лог: сторінка перегенеровується вручну перед демо і публікується ")
                .append("як статичний файл. Тут немає жодного повідомлення, товару чи ідентифікатора людини — ")
                .append("тільки імена інструментів, лічильники та імена інтентів.</p>\n</footer>\n");

        page.append("</body>\n</html>\n");
        return page.toString();
    }

    private static void stat(StringBuilder page, String value, String label, String detail) {
        page.append("<div class=\"stat\"><p class=\"value\">")
                .append(escape(value))
                .append("</p><p class=\"label\">")
                .append(escape(label))
                .append("</p><p class=\"detail\">")
                .append(escape(detail))
                .append("</p></div>\n");
    }

    private static List<ToolRow> toolRows(List<McpToolCall> calls) {
        Map<String, long[]> counts = new TreeMap<>();
        for (McpToolCall call : calls) {
            if (call.getToolName() == null) {
                continue;
            }
            long[] slot = counts.computeIfAbsent(call.getToolName(), name -> new long[2]);
            slot[0]++;
            if (call.isError()) {
                slot[1]++;
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new ToolRow(entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                .sorted(Comparator.comparingLong(ToolRow::calls).reversed().thenComparing(ToolRow::tool))
                .toList();
    }

    private static List<IntentRow> intentRows(List<IntentClassification> intents) {
        Map<String, Long> counts = new TreeMap<>();
        for (IntentClassification classification : intents) {
            if (classification.getIntent() == null) {
                continue;
            }
            counts.merge(classification.getIntent(), 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .map(entry -> new IntentRow(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(IntentRow::count).reversed().thenComparing(IntentRow::intent))
                .toList();
    }

    private static String share(long count, int total) {
        return total == 0 ? "—" : String.format(Locale.ROOT, "%.0f %%", 100.0 * count / total);
    }

    /** «1 виклик», «3 виклики», «12 викликів» — the rule Ukrainian actually uses, not a bare «(s)». */
    private static String plural(long count, String one, String few, String many) {
        long tens = Math.abs(count) % 100;
        long units = count % 10;
        if (tens >= 11 && tens <= 14) {
            return many;
        }
        if (units == 1) {
            return one;
        }
        if (units >= 2 && units <= 4) {
            return few;
        }
        return many;
    }

    /**
     * Tool names come off a remote server and land on a public URL. A page that interpolates them unescaped is a
     * stored-XSS hole waiting for the day Silpo ships a tool with an angle bracket in its name.
     */
    private static String escape(String text) {
        return Objects.requireNonNullElse(text, "")
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private record ToolRow(String tool, long calls, long failures) {}

    private record IntentRow(String intent, long count) {}

    private static Map<String, String> flowNotes() {
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put(
                "silpo_find_products_batch",
                "Збірка кошика (#13): кожен рядок списку шукається в каталозі одним "
                        + "батчем; ним же добираються готові страви й товари партнерських розміщень.");
        notes.put("silpo_create_shopping_cart", "Збірка кошика (#13): новий кошик під конкретне замовлення.");
        notes.put("silpo_get_my_shopping_cart", "Збірка кошика (#13): що зараз у кошику домогосподарства.");
        notes.put(
                "silpo_get_shopping_cart_by_id",
                "Збірка кошика (#13): перечитати кошик після змін — звідти "
                        + "беруться суми, яких немає у відповіді на додавання.");
        notes.put(
                "silpo_update_shopping_cart",
                "Налаштування доставки (#34): адреса, тип доставки і слот "
                        + "проставляються в кошик до генерації посилання на оплату.");
        notes.put(
                "silpo_add_or_update_cart_products",
                "Збірка кошика (#13) і дельта-дозамовлення (#14): " + "додавання позицій та зміна кількостей.");
        notes.put("silpo_remove_cart_products", "Правки списку (#12): позиція, яку прибрали з кошика.");
        notes.put(
                "silpo_clear_shopping_cart",
                "Збірка кошика (#50): кошик очищується перед кожною новою збіркою, "
                        + "щоб замовлення не успадкувало вчорашнє.");
        notes.put(
                "silpo_get_available_delivery_types",
                "Налаштування доставки (#34): які способи доставки взагалі " + "доступні цьому кошику.");
        notes.put("silpo_get_my_delivery_addresses", "Налаштування доставки (#34): збережені адреси гостя.");
        notes.put(
                "silpo_get_time_slots",
                "Налаштування доставки (#34) і автопідбір слота (#76): вільні вікна "
                        + "доставки, у тому числі коли попередній слот прострочився.");
        notes.put(
                "silpo_list_branches",
                "Налаштування доставки (#34): магазин самовивозу, коли доставка не " + "підходить.");
        notes.put(
                "silpo_get_replacements",
                "Дельта-дозамовлення (#14): чим замінити позицію, якої немає в " + "наявності.");
        notes.put(
                "silpo_get_my_family",
                "Онбординг (#09): склад домогосподарства з профілю «Сільпо» — щоб не " + "питати те, що вже відоме.");
        notes.put("silpo_get_my_food_restrictions", "Онбординг (#09): харчові обмеження з профілю «Сільпо».");
        notes.put("silpo_get_my_favorites", "Онбординг (#09): улюблені товари як стартовий базис раціону.");
        notes.put(
                "silpo_get_my_online_orders",
                "Онбординг (#35) і статус замовлення (#56): минулі онлайн-покупки "
                        + "як базис, і «де моє замовлення» — читання без жодної зміни.");
        notes.put(
                "silpo_get_my_offline_orders",
                "Статус замовлення (#56): покупки в магазині, коли онлайн-історії " + "ще немає.");
        notes.put(
                "silpo_get_loyalty_info",
                "Вигоди гостя (#78): скільки бонусів на рахунку і скільки з них можна " + "списати на цей кошик.");
        notes.put("silpo_get_my_certificates", "Вигоди гостя (#78): подарункові сертифікати домогосподарства.");
        notes.put(
                "silpo_add_or_update_certificates",
                "Вигоди гостя (#78): сертифікат застосовується до кошика "
                        + "автоматично — єдина дія з бонусного блоку, яку API справді дозволяє.");
        notes.put(
                "silpo_get_my_coupons",
                "Вигоди гостя (#79): купони гостя — показуються, бо застосувати їх "
                        + "може тільки застосунок «Сільпо».");
        notes.put(
                "silpo_get_coupon_details",
                "Вигоди гостя (#79): умови конкретного купона, щоб не обіцяти " + "знижку, якої не буде.");
        notes.put("silpo_get_my_promos", "Вигоди гостя (#79): персональні промо-пропозиції гостя.");
        notes.put("silpo_get_promo_codes", "Вигоди гостя (#79): промокоди, які кошик може прийняти.");
        notes.put(
                "silpo_get_my_premium_subscription",
                "Вигоди гостя (#79): статус Premium — інформаційно, "
                        + "оскільки керувати підпискою через API не можна.");
        return Map.copyOf(notes);
    }

    private static Map<String, String> intentExamples() {
        Map<String, String> examples = new LinkedHashMap<>();
        examples.put("AD_HOC_SCHEDULED_PURCHASE", "«замов до п'ятниці вино та сир по знижці»");
        examples.put("REORDER", "«дозамов те, що закінчилось»");
        examples.put("SPECIAL_MODE_MEDICAL_GASTRITIS", "«у мене загострився гастрит»");
        examples.put("SPECIAL_MODE_LEANER", "«хочу мінус 200 ккал на день»");
        examples.put("SPECIAL_MODE_MASS_GAIN", "«набираю масу»");
        examples.put("SPECIAL_MODE_END", "«все, повертаємось до звичайного»");
        examples.put("FILTER_UA_PRODUCER_ONLY", "«бери тільки українських виробників»");
        examples.put("HANGOVER_RELIEF", "«вчора перебрав, треба щось відпоїтись»");
        examples.put("BLACKOUT", "«світла не буде до вечора»");
        examples.put("LIST_VIEW", "«покажи список»");
        examples.put("LIST_MODIFY", "«додай протеїн і прибери печиво»");
        examples.put("CALENDAR_VIEW", "«що їмо в середу?»");
        examples.put("CALENDAR_CONNECT", "«підключи мій календар»");
        examples.put("PAST_ORDER_SEED", "«візьми за основу мою минулу покупку»");
        examples.put("WHERE_IS_MY_ORDER", "«де моє замовлення?»");
        examples.put("DISH_INGREDIENTS_ORDER", "«замов усе на карбонару» або фото страви");
        examples.put("MY_BENEFITS", "«які в мене вигоди?»");
        examples.put("HELP", "«що ти вмієш?»");
        examples.put(IntentLogService.UNCLASSIFIED, "фраза, якої класифікатор не впізнав — бот перепитує");
        examples.put(IntentLogService.FAILED, "класифікатор не відповів — модель була недоступна");
        return Map.copyOf(examples);
    }

    private static final String STYLE = """
            :root { color-scheme: light dark;
              --bg:#fbfaf7; --fg:#1b1a17; --muted:#6b6760; --line:#e3ded4; --card:#fff; --accent:#0f7a3d;
              --bad:#a3341f; }
            @media (prefers-color-scheme: dark) { :root {
              --bg:#16161a; --fg:#eceae4; --muted:#9c968c; --line:#2e2e34; --card:#1e1e23; --accent:#6cc48d;
              --bad:#e08d76; } }
            * { box-sizing: border-box; }
            body { margin:0; padding:2.5rem 1.25rem 4rem; background:var(--bg); color:var(--fg);
              font:16px/1.55 -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif; }
            header, section, footer { max-width: 62rem; margin: 0 auto; }
            .eyebrow { margin:0 0 .35rem; font-size:.8rem; letter-spacing:.09em; text-transform:uppercase;
              color:var(--accent); font-weight:600; }
            h1 { margin:0 0 .6rem; font-size:clamp(1.7rem, 4vw, 2.5rem); line-height:1.15; letter-spacing:-.02em; }
            .lede { margin:0 0 2rem; max-width:46rem; color:var(--muted); }
            h2 { margin:2.75rem 0 .4rem; font-size:1.15rem; letter-spacing:-.01em; }
            .note { margin:.2rem 0 1rem; max-width:46rem; color:var(--muted); font-size:.9rem; }
            .stats { display:grid; gap:.9rem; grid-template-columns:repeat(auto-fit, minmax(15rem, 1fr));
              margin-bottom:.5rem; }
            .stat { background:var(--card); border:1px solid var(--line); border-radius:.7rem; padding:1.1rem 1.2rem; }
            .stat .value { margin:0; font-size:2rem; font-weight:650; letter-spacing:-.03em; color:var(--accent); }
            .stat .label { margin:.15rem 0 0; font-weight:600; }
            .stat .detail { margin:.15rem 0 0; color:var(--muted); font-size:.88rem; }
            .scroll { overflow-x:auto; border:1px solid var(--line); border-radius:.7rem; background:var(--card); }
            table { border-collapse:collapse; width:100%; font-size:.92rem; }
            th, td { text-align:left; padding:.6rem .85rem; border-bottom:1px solid var(--line);
              vertical-align:top; }
            thead th { font-size:.78rem; letter-spacing:.06em; text-transform:uppercase; color:var(--muted);
              font-weight:600; white-space:nowrap; }
            tbody tr:last-child td { border-bottom:0; }
            td.num, th.num { text-align:right; font-variant-numeric:tabular-nums; white-space:nowrap; }
            td.bad { color:var(--bad); font-weight:600; }
            td.empty { color:var(--muted); text-align:center; padding:1.6rem; }
            code { font:.88em ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
            footer { margin-top:3rem; padding-top:1.2rem; border-top:1px solid var(--line); color:var(--muted);
              font-size:.85rem; }
            footer p { max-width:46rem; margin:0; }
            """;
}
