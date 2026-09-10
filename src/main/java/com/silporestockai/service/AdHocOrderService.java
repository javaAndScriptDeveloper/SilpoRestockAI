package com.silporestockai.service;

import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.model.MatchingHints;
import com.silporestockai.model.OrderTrigger;
import com.silporestockai.model.OrderType;
import com.silporestockai.model.ShoppingListDraft;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * A one-off request outside the weekly cycle: «закажи сир та вино по знижці до п'ятниці», «щось смачне під фільм».
 *
 * <p>Not a second ordering pipeline: building a cart is task 09's job and confirming one is task 10's. What this
 * class owns is turning the person's own words into a short shop list — a couple of cheeses and a wine, not a
 * balanced week — and passing along whether they asked for a discount, so the product choice prefers what is on
 * promotion. Classifying free text into a call to {@link #buildAdHocOrder} is task 31's job.
 *
 * <p>The first version ignored the theme entirely. It listed Silpo's promotions, kept the ones whose names
 * contained a snack keyword, and built a cart from those — so «сир та вино» would have come back as chips and
 * chocolate, and on the live account came back as nothing at all, because the promotions tool answers with campaign
 * codes rather than products and had been refusing the call's arguments all along.
 */
@Slf4j
@Service
public class AdHocOrderService {

    /** Words that mean the person wants the promotional version of the thing, not just the thing. */
    private static final Pattern WANTS_A_DISCOUNT =
            Pattern.compile("зниж|акці|дешев|розпродаж|sale|discount", Pattern.CASE_INSENSITIVE);

    /**
     * Task 32: one line per thing a hangover kit needs, not one per word for it.
     *
     * <p>The first version searched seven overlapping terms — «електроліти», «регідрон» and «ізотонік» for the same
     * need, «сорбент», «активоване вугілля» and «ентеросгель» for the other — and on a live account that came back
     * as the same ₴329 electrolyte drink twice (two lines merged into one quantity), two Atoxil gels and a ₴464
     * imported charcoal: ₴1514 for a hangover. Water, one rehydration drink, one sorbent. Silpo is a grocery, not a
     * pharmacy, and whichever of these a branch does not stock is reported as unfound like any other line.
     *
     * <p>Task 72: still one line per need, but each is searched under every name that need goes by, cheap staple
     * first. A category word alone is answered with the category's dearest members — that is what put Elekta Mix
     * at ₴309 a pack (twice), Atoxil and Evian in a live cart, ₴1034 for three basic items. A staple word alone is
     * no better: Silpo is a grocery, and live it has no ₴30 charcoal tablets at all, only a ₴464 imported
     * supplement whose name happens to be «Активоване вугілля», while Атоксіл sits on the same shelf at ₴119.
     * Searched together, the whole shelf is one pool and the cheapest thing that actually serves the need wins.
     */
    private static final List<HangoverLine> HANGOVER_RELIEF_LINES = List.of(
            new HangoverLine("вода мінеральна", new BigDecimal("2"), List.of()),
            new HangoverLine("регідрон", BigDecimal.ONE, List.of("електроліти", "ізотонік")),
            new HangoverLine("активоване вугілля", BigDecimal.ONE, List.of("сорбент", "ентеросорбент")));

    /**
     * @param alsoKnownAs the other shelf names this same need goes by, searched in the same pass as the staple so
     *     that the cheapest suitable one wins — see {@link com.silporestockai.model.MatchingHints}
     */
    private record HangoverLine(String name, BigDecimal quantity, List<String> alsoKnownAs) {}

    private final ClaudeApiClient claudeApiClient;
    private final UserProfileRepository userProfileRepository;
    private final CartConfirmationService cartConfirmationService;
    private final TelegramOutboundService telegramOutboundService;
    private final String themeSystemPrompt;

    public AdHocOrderService(
            ClaudeApiClient claudeApiClient,
            UserProfileRepository userProfileRepository,
            CartConfirmationService cartConfirmationService,
            TelegramOutboundService telegramOutboundService,
            @Value("classpath:prompts/ad-hoc-theme-system.txt") Resource themeSystemPromptResource) {
        this.claudeApiClient = claudeApiClient;
        this.userProfileRepository = userProfileRepository;
        this.cartConfirmationService = cartConfirmationService;
        this.telegramOutboundService = telegramOutboundService;
        this.themeSystemPrompt = read(themeSystemPromptResource);
    }

    /**
     * Turns the theme into a short shop list and hands it to the usual confirmation as an {@link OrderType#AD_HOC}
     * cart. {@code targetDateTime} is accepted for task 31's contract; scheduling already decided when to run this.
     */
    /**
     * @param trigger which intent asked for this and when (task 75) — for a purchase the sweep fires, the sweep
     *     moment, since «до п'ятниці» is a deadline the person chose, not latency
     */
    public void buildAdHocOrder(User user, String themeDescription, Instant targetDateTime, OrderTrigger trigger) {
        long chatId = user.getTelegramChatId();
        String theme = themeDescription == null || themeDescription.isBlank() ? "щось смачне" : themeDescription.trim();
        List<ShoppingListItem> items = linesFor(user.getId(), theme);
        if (items.isEmpty()) {
            telegramOutboundService.sendMessage(
                    chatId, "Не зрозумів, що саме купити на «%s». Напиши конкретніше — що і скільки.".formatted(theme));
            return;
        }
        boolean preferDiscounted = WANTS_A_DISCOUNT.matcher(theme).find();
        telegramOutboundService.sendMessage(
                chatId,
                "На «%s» беру: %s%s — збираю кошик."
                        .formatted(
                                theme,
                                String.join(
                                        ", ",
                                        items.stream()
                                                .map(AdHocOrderService::describe)
                                                .toList()),
                                preferDiscounted ? " (де є акція — беру акційне)" : ""));
        cartConfirmationService.present(user, items, OrderType.AD_HOC, preferDiscounted, trigger);
        log.info("presented an ad-hoc cart of {} lines for «{}» to user {}", items.size(), theme, user.getId());
    }

    /**
     * "Голова після вчорашнього, привезіть мінералку і щось від інтоксикації якнайшвидше" (task 32). Not
     * promotion-driven — availability, and since task 72 the cheap staple, is the point — and the earliest offered
     * delivery slot is the standard flow's own default.
     *
     * @param personsWords the sentence that asked for the kit. The kit's lines are fixed, so they carry no brand of
     *     their own; «привези Evian» can only beat the cheap default if the words reach the matcher, which is the
     *     one step that sees both the sentence and what the catalog offered.
     */
    public void buildHangoverReliefOrder(User user, String personsWords, OrderTrigger trigger) {
        List<ShoppingListItem> items = HANGOVER_RELIEF_LINES.stream()
                .map(line -> ShoppingListItem.builder()
                        .id(UUID.randomUUID())
                        .userId(user.getId())
                        .name(line.name())
                        .quantity(line.quantity())
                        .unit("шт")
                        .build())
                .toList();
        Map<String, List<String>> alsoSearch = HANGOVER_RELIEF_LINES.stream()
                .filter(line -> !line.alsoKnownAs().isEmpty())
                .collect(Collectors.toMap(HangoverLine::name, HangoverLine::alsoKnownAs));
        cartConfirmationService.present(
                user, items, OrderType.AD_HOC, false, trigger, new MatchingHints(personsWords, alsoSearch, false));
        log.info("presented a hangover-relief cart to user {}", user.getId());
    }

    /** The theme as one to six shop lines. A failed model call is an empty list, which the caller explains. */
    private List<ShoppingListItem> linesFor(UUID userId, String theme) {
        StringBuilder prompt = new StringBuilder();
        userProfileRepository.findByUserId(userId).ifPresent(profile -> {
            if (profile.getDietaryRestrictions() != null
                    && !profile.getDietaryRestrictions().isEmpty()) {
                prompt.append("Обмеження та алергії: «")
                        .append(String.join(", ", profile.getDietaryRestrictions()))
                        .append("».\n");
            }
            if (profile.getHouseholdSize() != null && profile.getHouseholdSize() > 0) {
                prompt.append("Людей удома: ")
                        .append(profile.getHouseholdSize())
                        .append(".\n");
            }
        });
        prompt.append("Побажання: «").append(theme).append("».");
        ShoppingListDraft draft;
        try {
            draft = claudeApiClient.completeStructuredFast(
                    themeSystemPrompt, prompt.toString(), ShoppingListDraft.class);
        } catch (RuntimeException e) {
            log.error("could not turn «{}» into a shop list for user {}", theme, userId, e);
            return List.of();
        }
        if (draft == null || draft.items() == null) {
            return List.of();
        }
        return draft.items().stream()
                .filter(line ->
                        line != null && line.name() != null && !line.name().isBlank())
                .limit(6)
                .map(line -> ShoppingListItem.builder()
                        .id(UUID.randomUUID())
                        .userId(userId)
                        .name(line.name().trim())
                        .quantity(line.quantity() == null ? BigDecimal.ONE : line.quantity())
                        .unit(line.unit() == null ? "шт" : line.unit())
                        .category(line.category())
                        // Never a product id from the model — task 09's search resolves these.
                        .silpoProductId(null)
                        .build())
                .toList();
    }

    private static String describe(ShoppingListItem item) {
        String quantity = item.getQuantity().stripTrailingZeros().toPlainString();
        return item.getName().toLowerCase(Locale.ROOT) + " " + quantity + " " + item.getUnit();
    }

    private static String read(Resource resource) {
        try (var stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the ad-hoc theme prompt", e);
        }
    }
}
