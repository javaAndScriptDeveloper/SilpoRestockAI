package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.model.ProductCandidate;
import com.silporestockai.model.ProductMatchRequest;
import com.silporestockai.service.ProductMatchingService;
import com.silporestockai.support.StubAnthropicServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
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
     * A failed call degrades to Silpo's own ranking — the behaviour before this service existed — rather than
     * failing the cart. The household gets a worse-matched cart, not no cart, and the log says which happened.
     */
    @Test
    void fallsBackToSilposOwnRankingWhenTheCallFails() {
        CLAUDE.injectStatus(500);

        List<Integer> chosen = productMatchingService.choose(List.of(spaghetti(), banana()));

        assertThat(chosen).containsExactly(0, 0);
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
