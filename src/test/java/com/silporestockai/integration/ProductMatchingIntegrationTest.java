package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.silporestockai.exception.ProductMatchException;
import com.silporestockai.model.ProductCandidate;
import com.silporestockai.model.ProductMatchRequest;
import com.silporestockai.service.ProductMatchingService;
import com.silporestockai.support.StubAnthropicServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Silpo's own top hit is not the product a household meant. Every candidate list here is real — taken from one
 * live {@code silpo_find_products_batch} call for an ordinary weekly list.
 */
@DisplayName("the product a household meant is chosen from what Silpo returned, not whatever it ranked first")
class ProductMatchingIntegrationTest extends AbstractIntegrationTest {

    private static final StubAnthropicServer CLAUDE = startClaude();

    @Autowired
    private ProductMatchingService productMatchingService;

    private static StubAnthropicServer startClaude() {
        try {
            return new StubAnthropicServer();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Anthropic stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("claude.api-key", () -> "sk-ant-stub-key");
        registry.add("claude.base-url", CLAUDE::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        CLAUDE.close();
    }

    @BeforeEach
    void clean() {
        CLAUDE.reset();
    }

    private static ProductCandidate packaged(String name, String price, String ratio, String stock) {
        return new ProductCandidate(name, new BigDecimal(price), ratio, false, new BigDecimal(stock));
    }

    private static ProductCandidate byWeight(String name, String price, String stock) {
        return new ProductCandidate(name, new BigDecimal(price), "100г", true, new BigDecimal(stock));
    }

    /** «Спагеті» as Silpo really answered it: konjac noodles first, a serving spoon fourth, pasta in between. */
    private static ProductMatchRequest spaghetti() {
        return new ProductMatchRequest(
                "Спагеті",
                new BigDecimal("500"),
                "г",
                List.of(
                        packaged("Спагеті Yumart Shirataki", "62.99", "200г", "11"),
                        packaged("Вироби макаронні «Чумак» спагеті", "55.99", "400г", "96"),
                        packaged("Вироби макаронні La Pasta спагеті", "43.49", "400г", "228"),
                        packaged("Паста Morelli «Спагеті» 8 хвилин", "279", "500г", "1"),
                        packaged("Ложка для спагеті Excellent Houseware Forres чорна", "349", "шт", "4")));
    }

    /** «Банан» as Silpo really answered it: thirty processed products, no fresh banana anywhere in the list. */
    private static ProductMatchRequest banana() {
        return new ProductMatchRequest(
                "Банан",
                BigDecimal.ONE,
                "шт",
                List.of(
                        packaged("Банан чіпси смажені", "149", "200г", "9"),
                        packaged("Банан Arkmen натурально сушений в шоколаді", "229", "200г", "5"),
                        packaged("Банани сушені", "75.59", "200г", "18"),
                        packaged("Іграшка Банан антистрес D-1", "34.99", "шт", "5")));
    }

    /**
     * Task 72: the rules a ₴1034 hangover cart needed are in the prompt the matcher actually loads, not only in a
     * plan — the cheap pharmacy staples by name, the water rule, «найдешевший» as a rule rather than an aside, and
     * the brand the person named beating all of it.
     */
    @Test
    void theMatcherPromptNamesTheCheapStaplesTheWaterRuleAndTheBrandOverride() {
        CLAUDE.respondWithText("{\"choices\":[]}");

        productMatchingService.choose(List.of(new ProductMatchRequest(
                "регідрон", BigDecimal.ONE, "шт", List.of(packaged("Регідрон Оптім", "32", "18.9г", "5")))));

        String system = CLAUDE.requests().getFirst().path("system").toString();
        assertThat(system)
                .contains("регідрон")
                .contains("Активоване вугілля")
                .contains("Evian")
                .contains("НАЙДЕШЕВШИЙ")
                .contains("назвала конкретний товар чи бренд");
    }

    @Test
    void picksThePastaRatherThanSilposTopHitOrTheServingSpoon() {
        CLAUDE.respondWithText("""
                {"choices":[{"lineIndex":0,"candidateIndex":2,"reason":"звичайні пшеничні спагеті, дешевші за 100 г"}]}""");

        List<Integer> chosen = productMatchingService.choose(List.of(spaghetti()));

        assertThat(chosen).containsExactly(2);
    }

    /**
     * The case that matters most: when the thing the household wrote down is simply not among the candidates,
     * saying so beats ordering the nearest-looking snack. An unresolved line is already reported honestly.
     */
    @Test
    void answersNoneWhenSilpoReturnedOnlyProcessedVersionsOfTheThing() {
        CLAUDE.respondWithText("""
                {"choices":[{"lineIndex":0,"candidateIndex":-1,"reason":"свіжих бананів немає, лише снеки"}]}""");

        List<Integer> chosen = productMatchingService.choose(List.of(banana()));

        assertThat(chosen).containsExactly(ProductMatchingService.NONE);
    }

    @Test
    void decidesEveryLineOfACartInOneCall() {
        CLAUDE.respondWithText("""
                {"choices":[{"lineIndex":0,"candidateIndex":2,"reason":"звичайні спагеті"},\
                {"lineIndex":1,"candidateIndex":-1,"reason":"свіжих бананів немає"}]}""");

        List<Integer> chosen = productMatchingService.choose(List.of(spaghetti(), banana()));

        assertThat(chosen).containsExactly(2, ProductMatchingService.NONE);
        assertThat(CLAUDE.callCount()).isEqualTo(1);
    }

    /**
     * The same rule that governs a {@code productId}: the model may only pick from what Silpo actually sent.
     * An index nobody offered is refused rather than trusted into a cart.
     */
    @Test
    void refusesACandidateIndexThatWasNeverOffered() {
        CLAUDE.respondWithText("""
                {"choices":[{"lineIndex":0,"candidateIndex":99,"reason":"вигаданий номер"}]}""");

        List<Integer> chosen = productMatchingService.choose(List.of(spaghetti()));

        assertThat(chosen).containsExactly(0);
    }

    @Test
    void ignoresAChoiceForALineThatWasNeverAskedAbout() {
        CLAUDE.respondWithText("""
                {"choices":[{"lineIndex":7,"candidateIndex":1,"reason":"рядка 7 не існує"}]}""");

        List<Integer> chosen = productMatchingService.choose(List.of(spaghetti()));

        assertThat(chosen).containsExactly(0);
    }

    /**
     * A failed call is a failed cart, not a silently worse one. This used to degrade to Silpo's own ranking, and
     * the one time it did so for real — an API-credit outage — the household was shown ₴7549 of konjac noodles,
     * seventeen packets of jerky and a ₴1399 cheese, with a «Підтвердити» button under it.
     */
    @Test
    void failsTheCartRatherThanMatchingBySilposOwnRankingWhenTheCallFails() {
        CLAUDE.injectStatus(500);
        CLAUDE.injectStatus(500);
        CLAUDE.injectStatus(500);

        assertThatThrownBy(() -> productMatchingService.choose(List.of(spaghetti(), banana())))
                .isInstanceOf(ProductMatchException.class);
    }

    /** The choice runs on the fast model: the flagship one took 88 seconds for a 25-line cart on a live account. */
    @Test
    void asksTheFastModel() {
        CLAUDE.respondWithText("{\"choices\":[{\"lineIndex\":0,\"candidateIndex\":2,\"reason\":\"паста\"}]}");

        productMatchingService.choose(List.of(spaghetti()));

        assertThat(CLAUDE.requests().getFirst().path("model").asText()).isEqualTo("claude-haiku-4-5-20251001");
    }

    /**
     * The second search pass: other names the product might carry on a shelf, for lines whose first search found
     * nothing usable. Keyed by the line's position; the original term and blanks are dropped; at most two each.
     */
    @Test
    void suggestsOtherShelfNamesForLinesTheFirstSearchMissed() {
        CLAUDE.respondWithText("""
                {"suggestions":[\
                {"lineIndex":0,"terms":["Вівсяні пластівці","Пластівці вівсяні","Геркулес"]},\
                {"lineIndex":1,"terms":["Яйця курячі","Яйця"," "]},\
                {"lineIndex":7,"terms":["зайве"]}]}""");

        Map<Integer, List<String>> terms = productMatchingService.alternativeTerms(List.of(
                new ProductMatchRequest("Вівсянка", BigDecimal.ONE, "уп", List.of()),
                new ProductMatchRequest("Яйця курячі", BigDecimal.TEN, "шт", List.of())));

        assertThat(terms).containsOnlyKeys(0, 1);
        assertThat(terms.get(0)).containsExactly("Вівсяні пластівці", "Пластівці вівсяні");
        assertThat(terms.get(1)).containsExactly("Яйця");
        assertThat(CLAUDE.requests().getFirst().path("model").asText()).isEqualTo("claude-haiku-4-5-20251001");
    }

    /** A second pass is a bonus: a failed suggestion call costs nothing but the lines it would have found. */
    @Test
    void aFailedSuggestionCallMeansNoAlternativesNotAnException() {
        CLAUDE.injectStatus(500);
        CLAUDE.injectStatus(500);
        CLAUDE.injectStatus(500);

        assertThat(productMatchingService.alternativeTerms(
                        List.of(new ProductMatchRequest("Вівсянка", BigDecimal.ONE, "уп", List.of()))))
                .isEmpty();
    }

    /** The UA-only preference reaches the model as a note on the line; the search term itself stays plain. */
    @Test
    void marksTheUkrainianProducerPreference() {
        CLAUDE.respondWithText("{\"choices\":[{\"lineIndex\":0,\"candidateIndex\":1,\"reason\":\"український\"}]}");
        ProductMatchRequest milk = new ProductMatchRequest(
                "Молоко",
                new BigDecimal("1"),
                "л",
                List.of(
                        packaged("Молоко Parmalat 3.5%", "89", "1л", "20"),
                        packaged("Молоко Ферма 2.5%", "46", "900г", "30")),
                false,
                true);

        List<Integer> chosen = productMatchingService.choose(List.of(milk));

        assertThat(chosen).containsExactly(1);
        assertThat(CLAUDE.requests().getFirst().toString()).contains("УКРАЇНСЬКОГО ВИРОБНИКА");
    }

    /** «По знижці» reaches the model as a note on the line, and a promoted candidate is marked with its old price. */
    @Test
    void marksPromotedCandidatesAndTheDiscountPreference() {
        CLAUDE.respondWithText("{\"choices\":[{\"lineIndex\":0,\"candidateIndex\":1,\"reason\":\"акція\"}]}");
        ProductMatchRequest cheese = new ProductMatchRequest(
                "Сир твердий",
                new BigDecimal("300"),
                "г",
                List.of(
                        packaged("Сир Плай Бердо", "149", "150г", "12"),
                        new ProductCandidate(
                                "Сир Пирятин",
                                new BigDecimal("88.9"),
                                "150г",
                                false,
                                new BigDecimal("40"),
                                new BigDecimal("108.9"))),
                true);

        List<Integer> chosen = productMatchingService.choose(List.of(cheese));

        assertThat(chosen).containsExactly(1);
        String prompt = CLAUDE.requests().getFirst().toString();
        assertThat(prompt).contains("ПО ЗНИЖЦІ").contains("АКЦІЯ, було 108.9");
        assertThat(prompt).doesNotContain("Плай Бердо — 149 грн (АКЦІЯ");
    }

    /** A line Silpo returned nothing for needs no opinion from anyone. */
    @Test
    void aLineWithNoCandidatesIsUnresolvedWithoutAsking() {
        CLAUDE.respondWithText("{\"choices\":[]}");

        List<Integer> chosen = productMatchingService.choose(
                List.of(new ProductMatchRequest("Хамон", BigDecimal.ONE, "шт", List.of())));

        assertThat(chosen).containsExactly(ProductMatchingService.NONE);
    }

    @Test
    void asksNobodyWhenThereIsNothingToDecide() {
        assertThat(productMatchingService.choose(List.of())).isEmpty();
        assertThat(CLAUDE.callCount()).isZero();
    }

    /** What the model is shown has to carry the shelf tags the decision needs — price, package, stock. */
    @Test
    void showsThePriceThePackageAndTheStockOfEveryCandidate() {
        CLAUDE.respondWithText("{\"choices\":[{\"lineIndex\":0,\"candidateIndex\":2,\"reason\":\"ok\"}]}");

        productMatchingService.choose(List.of(spaghetti()));

        String sent = CLAUDE.requests().getFirst().toString();
        assertThat(sent)
                .contains("Спагеті")
                .contains("Ложка для спагеті")
                .contains("43.49")
                .contains("400г")
                .contains("228");
    }

    /** A weighted candidate's price is per kilogram, and the model is told so — 100 г packs price very differently. */
    @Test
    void saysWhenACandidatesPriceIsPerKilogram() {
        CLAUDE.respondWithText("{\"choices\":[{\"lineIndex\":0,\"candidateIndex\":0,\"reason\":\"ok\"}]}");

        productMatchingService.choose(List.of(new ProductMatchRequest(
                "Яловичина", new BigDecimal("850"), "г", List.of(byWeight("Фарш телячий", "208.49", "2.5")))));

        assertThat(CLAUDE.requests().getFirst().toString()).contains("за кг");
    }
}
