package com.silporestockai.service;

import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.entity.GroupEvent;
import com.silporestockai.entity.GroupEventParticipant;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.model.CartContext;
import com.silporestockai.model.GroupDrinkLine;
import com.silporestockai.model.GroupDrinksProposal;
import com.silporestockai.model.GroupProposal;
import com.silporestockai.model.GroupProposalLine;
import com.silporestockai.model.GroupSignals;
import com.silporestockai.model.HistoricalLine;
import com.silporestockai.model.ParticipantPreference;
import com.silporestockai.model.ParticipantSignals;
import com.silporestockai.model.ProductResolution;
import com.silporestockai.model.ResolvedProduct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * The «Reason» half of a group round (task 68), and the second «Act» that grounds it.
 *
 * <p>Order matters and is the whole point: {@link GroupSignalService} gathers real facts first, the model
 * synthesises drink lines from those facts plus every raw reply, and then — before anyone is asked to approve —
 * the lines are resolved against the organizer's real Silpo catalog through the same search, second pass and
 * matcher every other cart uses. What the group approves is therefore a list of real products at real prices,
 * not a paragraph.
 *
 * <p>The task text suggested pricing through task 39's baseline estimate. A baseline is what a household eats in
 * a week and holds no drinks, so for this flow it would price nothing; the catalog does, at the cost of one search
 * batch and one fast-model matcher call per proposal.
 */
@Slf4j
@Service
public class GroupProposalService {

    /** No line may carry more than this many units per counted head — the model's own rule, enforced here too. */
    static final int MAX_UNITS_PER_HEAD = 3;

    static final int MAX_LINES = 12;

    private static final String CATEGORY = "Напої";
    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final ClaudeApiClient claudeApiClient;
    private final GroupSignalService groupSignalService;
    private final CartBuildingService cartBuildingService;
    private final SilpoAuthService silpoAuthService;
    private final String systemPrompt;

    public GroupProposalService(
            ClaudeApiClient claudeApiClient,
            GroupSignalService groupSignalService,
            CartBuildingService cartBuildingService,
            SilpoAuthService silpoAuthService,
            @Value("classpath:prompts/group-drinks-system.txt") Resource systemPromptResource) {
        this.claudeApiClient = claudeApiClient;
        this.groupSignalService = groupSignalService;
        this.cartBuildingService = cartBuildingService;
        this.silpoAuthService = silpoAuthService;
        this.systemPrompt = read(systemPromptResource);
    }

    /**
     * Gather → synthesise → clamp → ground. {@code version} is the number the caller has already assigned.
     *
     * @throws RuntimeException from the model call — the caller tells the group and leaves the round open
     */
    public GroupProposal propose(GroupEvent event, List<GroupEventParticipant> counted, Optional<User> organizer) {
        GroupSignals signals = groupSignalService.gather(event, counted);
        String prompt = renderPrompt(event, signals, event.revisionNotesOrEmpty());
        GroupDrinksProposal answer =
                claudeApiClient.completeStructured(systemPrompt, prompt, GroupDrinksProposal.class);
        int headcount = Math.max(counted.size(), 1);
        List<GroupDrinkLine> lines = sane(answer == null ? List.of() : answer.lines(), headcount);
        List<ParticipantPreference> preferences =
                answer == null || answer.participants() == null ? List.of() : answer.participants();
        String note = answer == null ? null : answer.note();
        log.info("group event {}: the model proposed {} lines for {} people", event.getId(), lines.size(), headcount);

        Optional<User> connected = organizer.filter(user -> silpoAuthService.isConnected(user.getId()));
        if (connected.isEmpty()) {
            return unpriced(event.getProposalVersion(), lines, note, preferences);
        }
        try {
            return ground(event.getProposalVersion(), connected.get().getId(), lines, note, preferences);
        } catch (RuntimeException e) {
            log.warn(
                    "group event {}: could not price the proposal in the catalog, posting it unpriced",
                    event.getId(),
                    e);
            // Live, a network blip here printed «у організатора ще не підключено «Сільпо»» to a group whose
            // organizer had been connected all evening. Say what actually happened.
            return unpriced(event.getProposalVersion(), lines, note, preferences)
                    .withCatalogUnavailable();
        }
    }

    /** The lines resolved through the organizer's session: real ids, catalog names, prices. */
    private GroupProposal ground(
            int version,
            UUID organizerId,
            List<GroupDrinkLine> lines,
            String note,
            List<ParticipantPreference> preferences) {
        List<ShoppingListItem> items = lines.stream()
                .map(line -> ShoppingListItem.builder()
                        .id(UUID.randomUUID())
                        .userId(organizerId)
                        .name(line.name())
                        .quantity(line.quantity())
                        .unit(line.unit())
                        .category(CATEGORY)
                        .silpoProductId(null)
                        .build())
                .toList();
        CartContext context = cartBuildingService.getOrCreateCartContext(organizerId);
        ProductResolution resolution = cartBuildingService.resolve(organizerId, context, items, false);
        Map<String, ResolvedProduct> byName = new LinkedHashMap<>();
        for (ResolvedProduct product : resolution.resolved()) {
            byName.putIfAbsent(product.requestedName(), product);
        }
        List<GroupProposalLine> proposed = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean anyPrice = false;
        for (GroupDrinkLine line : lines) {
            ResolvedProduct product = byName.get(line.name());
            if (product == null) {
                unresolved.add(line.name());
                continue;
            }
            GroupProposalLine proposedLine = new GroupProposalLine(
                    line.name(),
                    product.productId(),
                    product.catalogName() == null ? line.name() : product.catalogName(),
                    product.quantity(),
                    product.weighted() ? "кг" : (line.unit() == null ? "шт" : line.unit()),
                    product.unitPrice(),
                    line.forWhom());
            proposed.add(proposedLine);
            if (proposedLine.lineCost() != null) {
                total = total.add(proposedLine.lineCost());
                anyPrice = true;
            }
        }
        unresolved.addAll(resolution.skipped());
        return new GroupProposal(
                version,
                proposed,
                unresolved,
                anyPrice ? total.setScale(2, RoundingMode.HALF_UP) : null,
                true,
                note,
                preferences);
    }

    private static GroupProposal unpriced(
            int version, List<GroupDrinkLine> lines, String note, List<ParticipantPreference> preferences) {
        List<GroupProposalLine> proposed = lines.stream()
                .map(line -> new GroupProposalLine(
                        line.name(), null, line.name(), line.quantity(), line.unit(), null, line.forWhom()))
                .toList();
        return new GroupProposal(version, proposed, List.of(), null, false, note, preferences);
    }

    /** Drop empty lines, clamp quantities, cap the count. */
    private static List<GroupDrinkLine> sane(List<GroupDrinkLine> lines, int headcount) {
        List<GroupDrinkLine> kept = new ArrayList<>();
        for (GroupDrinkLine line : lines) {
            if (line == null || line.name() == null || line.name().isBlank()) {
                continue;
            }
            BigDecimal quantity = clampQuantity(line.quantity(), headcount);
            if (quantity.compareTo(line.quantity() == null ? BigDecimal.ZERO : line.quantity()) != 0) {
                log.info("clamped «{}» from {} to {} for {} people", line.name(), line.quantity(), quantity, headcount);
            }
            kept.add(new GroupDrinkLine(
                    line.name().strip(),
                    quantity,
                    line.unit() == null || line.unit().isBlank()
                            ? "шт"
                            : line.unit().strip(),
                    line.forWhom(),
                    line.reason()));
            if (kept.size() == MAX_LINES) {
                break;
            }
        }
        return kept;
    }

    /** At least one; at most {@link #MAX_UNITS_PER_HEAD} per counted head. */
    public static BigDecimal clampQuantity(BigDecimal quantity, int headcount) {
        BigDecimal ceiling = BigDecimal.valueOf((long) MAX_UNITS_PER_HEAD * Math.max(headcount, 1));
        if (quantity == null || quantity.compareTo(BigDecimal.ONE) < 0) {
            return BigDecimal.ONE;
        }
        return quantity.min(ceiling);
    }

    /** An even split, two decimals — informational arithmetic only. */
    public static BigDecimal perHead(BigDecimal total, int headcount) {
        if (total == null || headcount <= 0) {
            return null;
        }
        return total.divide(BigDecimal.valueOf(headcount), 2, RoundingMode.HALF_UP);
    }

    /** The user message: event facts, revision notes, tier-labelled facts per participant, group-level tiers. */
    String renderPrompt(GroupEvent event, GroupSignals signals, List<String> revisionNotes) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Подія: ")
                .append(
                        event.getEventTag() == null || event.getEventTag().isBlank()
                                ? "без приводу"
                                : event.getEventTag())
                .append(", дата ")
                .append(event.getEventDate() == null ? "не вказана" : DATE.format(event.getEventDate()))
                .append(", бюджет ")
                .append(
                        event.getBudget() == null
                                ? "не вказано"
                                : event.getBudget().stripTrailingZeros().toPlainString() + " грн")
                .append(", людей: ")
                .append(signals.participants().size())
                .append(".\n");
        if (!revisionNotes.isEmpty()) {
            prompt.append("Правки від компанії (кожна наступна важливіша за попередню):");
            for (int i = 0; i < revisionNotes.size(); i++) {
                prompt.append(' ').append(i + 1).append(") ").append(revisionNotes.get(i));
            }
            prompt.append('\n');
        }
        if (!signals.sameGroupLastTime().isEmpty()) {
            prompt.append("[Тир 2] Ця ж компанія минулого разу")
                    .append(signals.sameGroupLastTimeTag() == null ? "" : " (" + signals.sameGroupLastTimeTag() + ")")
                    .append(" брала:");
            appendLines(prompt, signals.sameGroupLastTime());
            prompt.append('\n');
        }
        prompt.append("Учасники:\n");
        for (ParticipantSignals person : signals.participants()) {
            prompt.append("• ")
                    .append(person.displayName() == null ? "учасник" : person.displayName())
                    .append(" (id ")
                    .append(person.telegramUserId())
                    .append(")\n");
            prompt.append("  [Тир 1] Відповідь зараз: «")
                    .append(person.rawReply())
                    .append("»\n");
            if (!person.personalHistory().isEmpty()) {
                prompt.append("  [Тир 3] В інших компаніях пив: ")
                        .append(String.join("; ", person.personalHistory()))
                        .append('\n');
            }
            if (person.needsSeasonal() && !signals.seasonal().isEmpty()) {
                prompt.append("  [Тир 4] (тільки коли тирів 1–3 немає) Сезонний середній для компаній такого розміру:");
                appendLines(prompt, signals.seasonal());
                prompt.append('\n');
            }
        }
        return prompt.toString();
    }

    private static void appendLines(StringBuilder prompt, List<HistoricalLine> lines) {
        for (HistoricalLine line : lines) {
            prompt.append(" — ")
                    .append(line.productName())
                    .append(" ")
                    .append(
                            line.quantity() == null
                                    ? ""
                                    : line.quantity().stripTrailingZeros().toPlainString())
                    .append(line.unit() == null ? "" : " " + line.unit());
            if (line.perHead() != null) {
                prompt.append(" (≈").append(line.perHead().toPlainString()).append(" на людину)");
            }
            prompt.append(';');
        }
    }

    private static String read(Resource resource) {
        try (var stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the group drinks prompt", e);
        }
    }
}
