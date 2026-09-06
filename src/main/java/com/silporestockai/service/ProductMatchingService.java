package com.silporestockai.service;

import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.config.ClaudeProperties;
import com.silporestockai.exception.ProductMatchException;
import com.silporestockai.model.ProductCandidate;
import com.silporestockai.model.ProductMatchRequest;
import com.silporestockai.model.SearchTermSuggestions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Picks which of the products Silpo returned is the one the household actually asked for.
 *
 * <p>This exists because of a spoon. Taking {@code products[0]} — Silpo's own top hit — put beef jerky in the cart
 * for «Яловичина», konjac noodles for «Спагеті», truffle rice at ₴949 for «Рис», banana chips for «Банан», and had
 * «Ложка для спагеті» sitting four places above the pasta. Silpo's ranking is not "the most ordinary version of
 * this thing first", and no stop-word list fixes it: the same «Яловичина» search also returns cat food and dog
 * treats, and knowing that Shirataki is konjac rather than pasta is not a keyword question.
 *
 * <p>So the model chooses, from the candidates Silpo really returned — the shape task 22 already proved for ready
 * meals. Two rules make that safe: the answer is a <em>position</em> in the candidate list, never a product id the
 * model could invent, and {@code -1} ("none of these is that product") is a legitimate answer. The second is the
 * important one: «Банан» comes back as chips, purées and two anti-stress toys with no fresh banana anywhere in it,
 * and reporting that line as unresolved — which the product already does honestly — beats ordering the chips.
 *
 * <p>Two things changed after the first live runs. The choice runs on the fast model: the flagship one took 88
 * seconds for a 25-line cart, with the person waiting on every one of them, and the rules are spelled out in the
 * prompt. And a failed call is a failed cart, not a silently worse one — see {@link ProductMatchException}.
 *
 * <p>See {@code docs/superpowers/plans/2026-09-06-product-matching.md}.
 */
@Slf4j
@Service
public class ProductMatchingService {

    /**
     * How many of Silpo's candidates the model is shown per line.
     *
     * <p>Started at 25 and cost a live account 100 seconds for one call on a 30-line list — a tap that looked like
     * it had done nothing. Every choice that mattered in that same run came from the first handful (the pasta at
     * index 2, the plain milk at 0, the potato at 1); the one case that needed depth, «Яловичина», was answered
     * «none» regardless because the real beef was short on stock. Twelve keeps the decisions and halves the prompt.
     */
    private static final int MAX_CANDIDATES_SHOWN = 12;

    /** The answer for a line nothing was chosen for. */
    public static final int NONE = -1;

    private final ClaudeApiClient claudeApiClient;
    private final ClaudeProperties claudeProperties;
    private final String systemPrompt;
    private final String searchTermsSystemPrompt;

    public ProductMatchingService(
            ClaudeApiClient claudeApiClient,
            ClaudeProperties claudeProperties,
            @Value("classpath:prompts/product-match-system.txt") Resource systemPromptResource,
            @Value("classpath:prompts/search-terms-system.txt") Resource searchTermsSystemPromptResource) {
        this.claudeApiClient = claudeApiClient;
        this.claudeProperties = claudeProperties;
        this.systemPrompt = read(systemPromptResource);
        this.searchTermsSystemPrompt = read(searchTermsSystemPromptResource);
    }

    /**
     * The candidate to use for each request, as a position in that request's own candidate list, or {@link #NONE}.
     *
     * <p>The returned list is always the same size as {@code requests} and in the same order, so a caller can zip
     * the two without checking. A line with no candidates at all is {@link #NONE} without asking anyone.
     *
     * @throws ProductMatchException when the model call fails — the cart is not built on Silpo's ranking instead
     */
    public List<Integer> choose(List<ProductMatchRequest> requests) {
        if (requests.isEmpty()
                || requests.stream().allMatch(request -> request.candidates().isEmpty())) {
            return silpoRanking(requests);
        }
        if (!claudeProperties.apiKeyConfigured()) {
            // A supported configuration, not a failure: say plainly what the cart is being matched with.
            log.info(
                    "ANTHROPIC_API_KEY is not set — matching {} shopping list lines by Silpo's own ranking",
                    requests.size());
            return silpoRanking(requests);
        }
        Choices answer;
        try {
            answer = claudeApiClient.completeStructuredFast(systemPrompt, describe(requests), Choices.class);
        } catch (RuntimeException e) {
            // Loud, and never a wrong cart: the one time this fell back to Silpo's own ranking for real, the
            // household was shown ₴7549 of jerky and konjac with a «Підтвердити» button under it.
            throw new ProductMatchException(
                    "could not choose products for %d shopping list lines".formatted(requests.size()), e);
        }
        return applied(requests, answer);
    }

    /**
     * Other things each of these products might be called on a shelf — the second search pass for lines whose
     * first search found nothing usable (task 09's «Яйця курячі» and «Йогурт натуральний» came back with zero
     * candidates; «Вівсянка» with flavoured porridge cups only). Silpo's search is a plain text match, so the term
     * is the problem, not the ranking. Keyed by position in {@code unresolved}; a line the model had no idea for is
     * absent. Empty, never an exception, when the call fails: a second pass is a bonus, not a requirement.
     */
    public Map<Integer, List<String>> alternativeTerms(List<ProductMatchRequest> unresolved) {
        if (unresolved.isEmpty() || !claudeProperties.apiKeyConfigured()) {
            return Map.of();
        }
        StringBuilder text = new StringBuilder("Рядки, для яких пошук не дав нічого придатного:\n");
        for (int i = 0; i < unresolved.size(); i++) {
            text.append(i)
                    .append(". «")
                    .append(unresolved.get(i).requestedName())
                    .append("»\n");
        }
        SearchTermSuggestions answer;
        try {
            answer = claudeApiClient.completeStructuredFast(
                    searchTermsSystemPrompt, text.toString(), SearchTermSuggestions.class);
        } catch (RuntimeException e) {
            log.warn("could not get alternative search terms for {} lines", unresolved.size(), e);
            return Map.of();
        }
        Map<Integer, List<String>> terms = new LinkedHashMap<>();
        if (answer == null || answer.suggestions() == null) {
            return terms;
        }
        for (SearchTermSuggestions.Suggestion suggestion : answer.suggestions()) {
            if (suggestion == null
                    || suggestion.lineIndex() < 0
                    || suggestion.lineIndex() >= unresolved.size()
                    || suggestion.terms() == null) {
                continue;
            }
            String original = unresolved.get(suggestion.lineIndex()).requestedName();
            List<String> cleaned = suggestion.terms().stream()
                    .filter(term -> term != null && !term.isBlank())
                    .map(String::trim)
                    .filter(term -> !term.equalsIgnoreCase(original))
                    .distinct()
                    .limit(2)
                    .toList();
            if (!cleaned.isEmpty()) {
                terms.put(suggestion.lineIndex(), cleaned);
                log.info("«{}»: will also search {}", original, cleaned);
            }
        }
        return terms;
    }

    /** Silpo's own ranking: whatever it put first. The only matching left when no model is configured. */
    private static List<Integer> silpoRanking(List<ProductMatchRequest> requests) {
        return requests.stream()
                .map(request -> request.candidates().isEmpty() ? NONE : 0)
                .toList();
    }

    /**
     * The model's answer, checked against what it was actually offered.
     *
     * <p>A {@code candidateIndex} outside the line's own list is refused exactly the way a fabricated
     * {@code productId} is — the model may only pick from what Silpo sent. An unanswered line keeps Silpo's first
     * match rather than being dropped: silence is not the same as "none of these".
     */
    private static List<Integer> applied(List<ProductMatchRequest> requests, Choices answer) {
        List<Integer> chosen = new ArrayList<>(silpoRanking(requests));
        List<Choice> choices = answer == null || answer.choices() == null ? List.of() : answer.choices();
        for (Choice choice : choices) {
            if (choice == null || choice.lineIndex() < 0 || choice.lineIndex() >= requests.size()) {
                log.warn("ignoring a product choice for line {}, which was never asked about", choice);
                continue;
            }
            ProductMatchRequest request = requests.get(choice.lineIndex());
            int shown = Math.min(request.candidates().size(), MAX_CANDIDATES_SHOWN);
            if (choice.candidateIndex() == NONE) {
                log.info(
                        "«{}»: none of Silpo's {} matches is that product — {}",
                        request.requestedName(),
                        shown,
                        choice.reason());
                chosen.set(choice.lineIndex(), NONE);
                continue;
            }
            if (choice.candidateIndex() < 0 || choice.candidateIndex() >= shown) {
                log.warn(
                        "«{}»: candidate {} was never offered (only {} were) — keeping Silpo's first match",
                        request.requestedName(),
                        choice.candidateIndex(),
                        shown);
                continue;
            }
            log.info(
                    "«{}» -> {} ({})",
                    request.requestedName(),
                    request.candidates().get(choice.candidateIndex()).name(),
                    choice.reason());
            chosen.set(choice.lineIndex(), choice.candidateIndex());
        }
        return List.copyOf(chosen);
    }

    /** What the model is shown: the line, and the shelf tags of everything Silpo offered for it. */
    private static String describe(List<ProductMatchRequest> requests) {
        StringBuilder text = new StringBuilder("Рядки списку покупок і кандидати з каталогу «Сільпо».\n");
        for (int line = 0; line < requests.size(); line++) {
            ProductMatchRequest request = requests.get(line);
            text.append("\nРядок ")
                    .append(line)
                    .append(": «")
                    .append(request.requestedName())
                    .append("», потрібно ")
                    .append(
                            request.quantity() == null
                                    ? "?"
                                    : request.quantity().toPlainString())
                    .append(' ')
                    .append(request.unit() == null ? "" : request.unit())
                    .append('\n');
            List<ProductCandidate> candidates = request.candidates();
            if (candidates.isEmpty()) {
                text.append("  (кандидатів немає)\n");
                continue;
            }
            for (int i = 0; i < Math.min(candidates.size(), MAX_CANDIDATES_SHOWN); i++) {
                ProductCandidate candidate = candidates.get(i);
                text.append("  ")
                        .append(i)
                        .append(". ")
                        .append(candidate.name())
                        .append(" — ")
                        .append(
                                candidate.price() == null
                                        ? "ціна невідома"
                                        : candidate.price().toPlainString() + " грн")
                        .append(candidate.weighted() ? " за кг" : "")
                        .append(", пакування ")
                        .append(candidate.displayRatio() == null ? "невідоме" : candidate.displayRatio())
                        .append(", на складі ")
                        .append(
                                candidate.stock() == null
                                        ? "невідомо"
                                        : candidate.stock().toPlainString())
                        .append('\n');
            }
        }
        return text.toString();
    }

    private static String read(Resource resource) {
        try (var stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read a product matching prompt", e);
        }
    }

    private record Choice(int lineIndex, int candidateIndex, String reason) {}

    private record Choices(List<Choice> choices) {}

    static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
