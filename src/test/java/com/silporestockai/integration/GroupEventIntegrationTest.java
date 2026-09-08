package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.silporestockai.entity.GroupEvent;
import com.silporestockai.entity.GroupEventParticipant;
import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.model.GroupEventStatus;
import com.silporestockai.model.OrderConfirmedEvent;
import com.silporestockai.repository.CustomerOrderRepository;
import com.silporestockai.repository.GroupEventApprovalRepository;
import com.silporestockai.repository.GroupEventItemRepository;
import com.silporestockai.repository.GroupEventParticipantRepository;
import com.silporestockai.repository.GroupEventRepository;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.support.StubTelegramServer;
import com.silporestockai.utils.TokenCipher;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Task 68 end to end through the webhook: greeting, replies, the organizer's freeze, a proposal grounded in the
 * catalog, approvals, a revision that empties the vote, consensus handing the lines to the organizer's private
 * cart confirmation, and the group being told when the organizer confirms.
 */
@DisplayName("a group drinks round: collect, freeze, propose, revise, agree, hand over")
class GroupEventIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "2020:stub-bot-token";
    private static final String BOT_USERNAME = "komora_test_bot";
    private static final long GROUP = -100777L;
    private static final long ORGANIZER = 41L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubMcpServer MCP = startMcp();
    private static final StubAnthropicServer CLAUDE = startClaude();

    private static final String WINE_ID = "00000000-0000-4000-8000-00000000005b";
    private static final String BEER_ID = "00000000-0000-4000-8000-00000000005c";

    private static final String PROPOSAL_V1 = """
            {"lines":[{"name":"Вино червоне сухе","quantity":2,"unit":"шт","forWhom":"Олена","reason":"попросила вино"},\
            {"name":"Пиво світле","quantity":6,"unit":"шт","forWhom":"Ігор, Марко","reason":"світле на двох"}],\
            "participants":[{"telegramUserId":41,"preferenceSummary":"червоне вино"},\
            {"telegramUserId":42,"preferenceSummary":"світле пиво"},{"telegramUserId":43,"preferenceSummary":"без вподобань"}],\
            "note":"Пиво порахував по три пляшки на людину."}""";

    private static final String PROPOSAL_V2 = """
            {"lines":[{"name":"Вино червоне сухе","quantity":3,"unit":"шт","forWhom":"Олена, Марко","reason":"більше вина"},\
            {"name":"Пиво світле","quantity":3,"unit":"шт","forWhom":"Ігор","reason":"менше пива"}],\
            "participants":[{"telegramUserId":41,"preferenceSummary":"червоне вино"},\
            {"telegramUserId":42,"preferenceSummary":"світле пиво"},{"telegramUserId":43,"preferenceSummary":"вино"}],\
            "note":"Урахував правку."}""";

    private static final String MATCH_BOTH = """
            {"choices":[{"lineIndex":0,"candidateIndex":0,"reason":"сухе червоне"},\
            {"lineIndex":1,"candidateIndex":0,"reason":"світле"}]}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SilpoOAuthTokenRepository tokenRepository;

    @Autowired
    private TokenCipher tokenCipher;

    @Autowired
    private GroupEventRepository events;

    @Autowired
    private GroupEventParticipantRepository participants;

    @Autowired
    private GroupEventItemRepository items;

    @Autowired
    private GroupEventApprovalRepository approvals;

    @Autowired
    private CustomerOrderRepository customerOrderRepository;

    @Autowired
    private ApplicationEventPublisher publisher;

    private int updateId = 1;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of(
                    "silpo_get_my_shopping_cart",
                    "silpo_get_shopping_cart_by_id",
                    "silpo_clear_shopping_cart",
                    "silpo_get_time_slots",
                    "silpo_find_products_batch",
                    "silpo_add_or_update_cart_products",
                    "silpo_update_shopping_cart"));
        } catch (IOException e) {
            throw new IllegalStateException("could not start the MCP stub", e);
        }
    }

    private static StubAnthropicServer startClaude() {
        try {
            return new StubAnthropicServer();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Anthropic stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
        registry.add("telegram.bot-username", () -> BOT_USERNAME);
        registry.add("silpo.mcp.endpoint", MCP::endpoint);
        registry.add("claude.api-key", () -> "sk-ant-stub-key");
        registry.add("claude.base-url", CLAUDE::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
        MCP.close();
        CLAUDE.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        CLAUDE.reset();
        MCP.reset();
        events.deleteAll();
        customerOrderRepository.deleteAll();
        tokenRepository.deleteAll();
        userRepository.deleteAll();
        scriptSilpo();
    }

    private User connectedOrganizer() {
        User organizer = userAccountService.findOrCreate(ORGANIZER);
        tokenRepository.save(SilpoOAuthToken.builder()
                .userId(organizer.getId())
                .accessToken(tokenCipher.encrypt("stub-access-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        return organizer;
    }

    private void scriptSilpo() {
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"cartId\":\"cart-g\"}");
        MCP.respondToTool("silpo_clear_shopping_cart", "{\"ok\":true}");
        MCP.respondToTool(
                "silpo_get_time_slots", "{\"timeSlots\":[{\"id\":\"slot-1\",\"from\":\"2026-09-12T18:00:00Z\"}]}");
        MCP.respondToTool("silpo_add_or_update_cart_products", "{\"ok\":true}");
        MCP.respondToTool("silpo_update_shopping_cart", "{\"ok\":true}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-g","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
                "items":[{"productId":"%s","name":"Вино Los Cardos","quantity":3,"price":329,"weighted":false},\
                {"productId":"%s","name":"Пиво Львівське світле 0.5","quantity":3,"price":42,"weighted":false}],\
                "calculation":{"total":1113,"productsTotal":1113,"subDiscount":0},\
                "validations":[],\
                "checkoutWebLink":"https://silpo.ua/checkout/cart-g",\
                "checkoutMobileLink":"silpo://checkout/cart-g"}""".formatted(WINE_ID, BEER_ID));
        MCP.respondToTool("silpo_find_products_batch", """
                {"queries":[\
                {"query":"Вино червоне сухе","products":[\
                {"name":"Вино Los Cardos","productId":"%s","step":1,"displayRatio":"750мл","price":329}]},\
                {"query":"Пиво світле","products":[\
                {"name":"Пиво Львівське світле 0.5","productId":"%s","step":1,"displayRatio":"500мл","price":42}]}]}""".formatted(WINE_ID, BEER_ID));
    }

    // ---- the round -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("collect, freeze, propose from real signals, revise, agree, hand the cart to the organizer")
    void fullRound() throws Exception {
        User organizer = connectedOrganizer();
        CLAUDE.respondWithTexts(PROPOSAL_V1, MATCH_BOTH, PROPOSAL_V2, MATCH_BOTH);

        // 1. The organizer adds the bot.
        addBot();
        GroupEvent event = events.findAll().getFirst();
        int greetingId = TELEGRAM.lastMessageId();
        assertThat(event.getOrganizerTelegramUserId()).isEqualTo(ORGANIZER);
        assertThat(event.getOrganizerUserId()).isEqualTo(organizer.getId());
        assertThat(event.getGreetingMessageId()).isEqualTo(greetingId);
        JsonNode greeting = TELEGRAM.sentMessages().getLast();
        assertThat(greeting.path("chat_id").asLong()).isEqualTo(GROUP);
        assertThat(greeting.path("text").asText()).contains("«.»").contains("@olena");
        assertThat(callbackOf(greeting)).isEqualTo("grp:freeze:" + event.getId());
        assertThat(userRepository.findByTelegramChatId(GROUP)).isEmpty();

        // 2. Replies to the greeting; an unaddressed message; a second reply from the same person.
        reply(41, "Олена", "olena", "вино червоне", greetingId);
        reply(42, "Ігор", null, "пиво світле, це на ДР", greetingId);
        int before = TELEGRAM.sentMessages().size();
        plain(43, "Марко", "хто бере торт?");
        assertThat(TELEGRAM.sentMessages()).hasSize(before);
        assertThat(CLAUDE.callCount()).isZero();
        reply(43, "Марко", null, ".", greetingId);
        assertThat(lastText()).isEqualTo("Записав, Марко. Відповіли: 3.");
        reply(42, "Ігор", null, "темне пиво", greetingId);
        assertThat(participants.findByGroupEventId(event.getId())).hasSize(3);
        assertThat(participants
                        .findByGroupEventIdAndTelegramUserId(event.getId(), 42L)
                        .orElseThrow()
                        .getRawReplyText())
                .isEqualTo("темне пиво");
        reply(42, "Ігор", null, "пиво світле, це на ДР", greetingId);

        // 3. The organizer sets a budget by replying; a mention is not addressed to the bot; a non-organizer cannot
        // freeze.
        before = TELEGRAM.sentMessages().size();
        mention(41, "Олена", "olena", "бюджет 9999");
        assertThat(TELEGRAM.sentMessages()).hasSize(before);
        assertThat(events.findById(event.getId()).orElseThrow().getBudget()).isNull();
        reply(41, "Олена", "olena", "бюджет 1500", greetingId);
        assertThat(lastText()).isEqualTo("Прийняв: бюджет 1500 грн");
        assertThat(events.findById(event.getId()).orElseThrow().getBudget()).isEqualByComparingTo("1500");
        tap(42, "grp:freeze:" + event.getId(), greetingId);
        assertThat(lastToast()).isEqualTo("Це кнопка організатора.");
        assertThat(events.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(GroupEventStatus.COLLECTING_REPLIES);

        // 4. The organizer freezes: the denominator is fixed and the proposal is generated and grounded.
        tap(41, "grp:freeze:" + event.getId(), greetingId);
        event = events.findById(event.getId()).orElseThrow();
        assertThat(event.getStatus()).isEqualTo(GroupEventStatus.PROPOSED);
        assertThat(event.getFrozenAt()).isNotNull();
        assertThat(participants.countByGroupEventIdAndCountedInDenominatorTrue(event.getId()))
                .isEqualTo(3);
        String prompt = userPrompt(0);
        assertThat(prompt)
                .contains("Відповідь зараз: «пиво світле, це на ДР»")
                .contains("бюджет 1500 грн")
                .contains("людей: 3");
        assertThat(prompt.indexOf("[Тир 1]")).isLessThan(prompt.indexOf("Учасники:") + 200);
        assertThat(prompt).doesNotContain("[Тир 4]");
        assertThat(MCP.callArguments("silpo_find_products_batch").getFirst().path("products"))
                .extracting(JsonNode::asText)
                .containsExactly("Вино червоне сухе", "Пиво світле");
        JsonNode proposal = TELEGRAM.sentMessages().getLast();
        int proposalId = TELEGRAM.lastMessageId();
        assertThat(proposal.path("text").asText())
                .contains("Пропозиція №1 на 3 людини (кількості орієнтовні)")
                .contains("Вино Los Cardos — 2 шт — 658.00 грн")
                .contains("Пиво Львівське світле 0.5 — 6 шт — 252.00 грн")
                .contains("Разом орієнтовно ~910.00 грн")
                .contains("бюджет 1500 грн, вкладаємось")
                .contains("відповідай реплаєм на це повідомлення, що прибрати чи додати")
                // Short on purpose: no split, no model note, no drink-specific example in the group message.
                .doesNotContain("з людини")
                .doesNotContain("Пиво порахував")
                .doesNotContain("менше пива");
        assertThat(callbackOf(proposal)).isEqualTo("grp:ok:" + event.getId() + ":1");
        assertThat(event.getProposalMessageId()).isEqualTo(proposalId);
        assertThat(participants
                        .findByGroupEventIdAndTelegramUserId(event.getId(), 41L)
                        .orElseThrow()
                        .getPreferenceSummary())
                .isEqualTo("червоне вино");
        assertThat(MCP.callArguments("silpo_add_or_update_cart_products").size())
                .isZero();

        // 5. A late reply is kept, acknowledged as late, and not counted.
        reply(44, "Настя", null, "сидр", greetingId);
        assertThat(lastText()).contains("цей раунд уже закрито");
        GroupEventParticipant late = participants
                .findByGroupEventIdAndTelegramUserId(event.getId(), 44L)
                .orElseThrow();
        assertThat(late.isCountedInDenominator()).isFalse();
        assertThat(participants.countByGroupEventIdAndCountedInDenominatorTrue(event.getId()))
                .isEqualTo(3);

        // 6. Two approvals, one from an uncounted person.
        tap(41, "grp:ok:" + event.getId() + ":1", proposalId);
        assertThat(lastToast()).isEqualTo("Погодились: 1 з 3.");
        tap(42, "grp:ok:" + event.getId() + ":1", proposalId);
        assertThat(lastToast()).isEqualTo("Погодились: 2 з 3.");
        tap(42, "grp:ok:" + event.getId() + ":1", proposalId);
        assertThat(lastToast()).isEqualTo("Ти вже погодився. Погодились: 2 з 3.");
        tap(44, "grp:ok:" + event.getId() + ":1", proposalId);
        assertThat(lastToast()).contains("не у списку цього раунду");
        assertThat(approvals.countByGroupEventIdAndProposalVersion(event.getId(), 1))
                .isEqualTo(2);
        assertThat(MCP.callArguments("silpo_add_or_update_cart_products").size())
                .isZero();

        // 7. A revision from an uncounted person is refused; from a counted one it resets every approval.
        reply(44, "Настя", null, "додай сидр", proposalId);
        assertThat(lastText()).contains("Правки приймаю лише від тих, хто в цьому раунді");
        assertThat(events.findById(event.getId()).orElseThrow().getProposalVersion())
                .isEqualTo(1);
        mention(43, "Марко", null, "менше пива, більше вина");
        assertThat(events.findById(event.getId()).orElseThrow().getProposalVersion())
                .isEqualTo(1);
        reply(43, "Марко", null, "менше пива, більше вина", proposalId);
        event = events.findById(event.getId()).orElseThrow();
        assertThat(event.getProposalVersion()).isEqualTo(2);
        assertThat(event.getRevisionNotes()).containsExactly("менше пива, більше вина");
        assertThat(userPrompt(2)).contains("Правки від компанії").contains("1) менше пива, більше вина");
        JsonNode second = TELEGRAM.sentMessages().getLast();
        int secondId = TELEGRAM.lastMessageId();
        assertThat(second.path("text").asText()).contains("Пропозиція №2").contains("Вино Los Cardos — 3 шт");
        assertThat(callbackOf(second)).isEqualTo("grp:ok:" + event.getId() + ":2");
        assertThat(approvals.countByGroupEventIdAndProposalVersion(event.getId(), 2))
                .isZero();
        tap(41, "grp:ok:" + event.getId() + ":1", proposalId);
        assertThat(lastToast()).contains("стара пропозиція");

        // 8. Everyone approves version 2: the cart is built through the organizer's ordinary confirmation.
        tap(41, "grp:ok:" + event.getId() + ":2", secondId);
        tap(42, "grp:ok:" + event.getId() + ":2", secondId);
        assertThat(MCP.callArguments("silpo_add_or_update_cart_products").size())
                .isZero();
        tap(43, "grp:ok:" + event.getId() + ":2", secondId);
        assertThat(lastToast()).isEqualTo("Погодились: 3 з 3.");
        event = events.findById(event.getId()).orElseThrow();
        assertThat(event.getStatus()).isEqualTo(GroupEventStatus.APPROVED);
        assertThat(event.getApprovedAt()).isNotNull();
        assertThat(MCP.callArguments("silpo_add_or_update_cart_products").size())
                .isEqualTo(1);
        assertThat(MCP.callArguments("silpo_add_or_update_cart_products")
                        .getFirst()
                        .path("products"))
                .extracting(product -> product.path("productId").asText())
                .containsExactly(WINE_ID, BEER_ID);
        // The search was not run again: the approved ids went straight to the cart.
        assertThat(MCP.callArguments("silpo_find_products_batch").size()).isEqualTo(2);
        JsonNode privateCart = TELEGRAM.sentMessages().stream()
                .filter(message -> message.path("chat_id").asLong() == ORGANIZER)
                .reduce((first, last) -> last)
                .orElseThrow();
        assertThat(privateCart.path("text").asText()).contains("Зібрав кошик");
        assertThat(privateCart.path("reply_markup").toString()).contains("Підтвердити");
        JsonNode groupDone = TELEGRAM.sentMessages().getLast();
        assertThat(groupDone.path("chat_id").asLong()).isEqualTo(GROUP);
        assertThat(groupDone.path("text").asText())
                .contains("Усі 3 погодились")
                .contains("@olena")
                .contains("з людини")
                .contains("платить організатор");
        assertThat(items.findByGroupEventId(event.getId()))
                .extracting(item -> item.getResolvedSilpoProductId())
                .containsExactlyInAnyOrder(WINE_ID, BEER_ID);
        assertThat(customerOrderRepository.findAll()).hasSize(1);
        assertThat(callbackOf(groupDone)).isEqualTo("grp:new");

        // 9. The organizer confirms in private: the group hears about it and the round is closed.
        publisher.publishEvent(new OrderConfirmedEvent(organizer.getId(), UUID.randomUUID(), null, "сб", 2));
        assertThat(events.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(GroupEventStatus.ORDERED);
        assertThat(lastText()).contains("@olena підтвердив замовлення");
        int orderedId = TELEGRAM.lastMessageId();

        // 10. «Новий збір» under the summary opens the next round; whoever tapped is its organizer.
        tap(43, "grp:new", orderedId);
        UUID finishedId = event.getId();
        List<GroupEvent> all = events.findAll();
        assertThat(all).hasSize(2);
        GroupEvent next = all.stream()
                .filter(e -> !e.getId().equals(finishedId))
                .findFirst()
                .orElseThrow();
        assertThat(next.getOrganizerTelegramUserId()).isEqualTo(43L);
        assertThat(next.getStatus()).isEqualTo(GroupEventStatus.COLLECTING_REPLIES);
        assertThat(lastText()).contains("u43, ти організатор");
    }

    @Test
    @DisplayName("an organizer without Silpo gets an unpriced proposal and a hint, and no cart is built")
    void organizerWithoutSilpo() throws Exception {
        CLAUDE.respondWithTexts(PROPOSAL_V1);
        addBot();
        GroupEvent event = events.findAll().getFirst();
        int greetingId = TELEGRAM.lastMessageId();
        reply(41, "Олена", "olena", "вино", greetingId);
        reply(42, "Ігор", null, "пиво", greetingId);
        reply(43, "Марко", null, ".", greetingId);

        tap(41, "grp:freeze:" + event.getId(), greetingId);

        assertThat(MCP.callArguments("silpo_find_products_batch").size()).isZero();
        assertThat(TELEGRAM.sentMessages().stream().map(m -> m.path("text").asText()))
                .anyMatch(text -> text.contains("підключи «Сільпо»") && text.contains("t.me/" + BOT_USERNAME));
        String proposal = TELEGRAM.sentMessages().getLast().path("text").asText();
        assertThat(proposal).contains("Без цін").doesNotContain("грн з людини");
        int proposalId = TELEGRAM.lastMessageId();
        tap(41, "grp:ok:" + event.getId() + ":1", proposalId);
        tap(42, "grp:ok:" + event.getId() + ":1", proposalId);
        tap(43, "grp:ok:" + event.getId() + ":1", proposalId);
        assertThat(events.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(GroupEventStatus.APPROVED);
        assertThat(MCP.callArguments("silpo_add_or_update_cart_products").size())
                .isZero();
        assertThat(lastText()).contains("підключи «Сільпо»");
    }

    @Test
    @DisplayName("a one-evening exception reaches the proposal without rewriting the person's history")
    void exceptionDoesNotTouchHistory() throws Exception {
        connectedOrganizer();
        // An earlier round where 41 was summarised as a whisky drinker.
        GroupEvent past = events.save(GroupEvent.builder()
                .id(UUID.randomUUID())
                .telegramGroupChatId(-999L)
                .organizerTelegramUserId(41L)
                .eventDate(LocalDate.of(2026, 3, 8))
                .status(GroupEventStatus.ORDERED)
                .approvedAt(Instant.now().minusSeconds(86400))
                .createdAt(Instant.now().minusSeconds(86400 * 2))
                .build());
        participants.save(GroupEventParticipant.builder()
                .id(UUID.randomUUID())
                .groupEventId(past.getId())
                .telegramUserId(41L)
                .displayName("@olena")
                .rawReplyText("віскі")
                .preferenceSummary("віскі")
                .repliedAt(Instant.now())
                .countedInDenominator(true)
                .build());
        participants.save(GroupEventParticipant.builder()
                .id(UUID.randomUUID())
                .groupEventId(past.getId())
                .telegramUserId(77L)
                .displayName("x")
                .rawReplyText("пиво")
                .repliedAt(Instant.now())
                .countedInDenominator(true)
                .build());
        CLAUDE.respondWithTexts(PROPOSAL_V1, MATCH_BOTH);

        addBot();
        GroupEvent event = events.findAll().stream()
                .filter(e -> e.getTelegramGroupChatId() == GROUP)
                .findFirst()
                .orElseThrow();
        int greetingId = TELEGRAM.lastMessageId();
        reply(41, "Олена", "olena", "сьогодні не п'ю віскі, візьму вино", greetingId);
        reply(42, "Ігор", null, "пиво", greetingId);
        reply(43, "Марко", null, ".", greetingId);
        tap(41, "grp:freeze:" + event.getId(), greetingId);

        String prompt = userPrompt(0);
        assertThat(prompt)
                .contains("Відповідь зараз: «сьогодні не п'ю віскі, візьму вино»")
                .contains("[Тир 3] В інших компаніях пив: віскі");
        assertThat(participants
                        .findByGroupEventIdAndTelegramUserId(past.getId(), 41L)
                        .orElseThrow()
                        .getPreferenceSummary())
                .isEqualTo("віскі");
        assertThat(participants
                        .findByGroupEventIdAndTelegramUserId(event.getId(), 41L)
                        .orElseThrow()
                        .getPreferenceSummary())
                .isEqualTo("червоне вино");
    }

    // ---- webhook fixtures -----------------------------------------------------------------------------------

    private void addBot() throws Exception {
        send("""
                {"update_id":%d,"my_chat_member":{"chat":{"id":%d,"type":"supergroup","title":"Пʼятниця"},\
                "from":{"id":41,"is_bot":false,"first_name":"Олена","username":"olena"},"date":1,\
                "old_chat_member":{"status":"left","user":{"id":2020,"is_bot":true,"first_name":"Komora"}},\
                "new_chat_member":{"status":"member","user":{"id":2020,"is_bot":true,"first_name":"Komora"}}}}""".formatted(updateId++, GROUP));
    }

    private void reply(long fromId, String firstName, String username, String text, int replyToBotMessageId)
            throws Exception {
        send("""
                {"update_id":%d,"message":{"message_id":%d,"date":1,"chat":{"id":%d,"type":"supergroup","title":"x"},\
                "from":{"id":%d,"is_bot":false,"first_name":"%s"%s},"text":"%s",\
                "reply_to_message":{"message_id":%d,"date":1,"chat":{"id":%d,"type":"supergroup"},\
                "from":{"id":2020,"is_bot":true,"first_name":"Komora"},"text":"hi"}}}""".formatted(
                        updateId,
                        500 + updateId++,
                        GROUP,
                        fromId,
                        firstName,
                        usernameJson(username),
                        text,
                        replyToBotMessageId,
                        GROUP));
    }

    private void plain(long fromId, String firstName, String text) throws Exception {
        send("""
                {"update_id":%d,"message":{"message_id":%d,"date":1,"chat":{"id":%d,"type":"supergroup","title":"x"},\
                "from":{"id":%d,"is_bot":false,"first_name":"%s"},"text":"%s"}}""".formatted(updateId, 500 + updateId++, GROUP, fromId, firstName, text));
    }

    private void mention(long fromId, String firstName, String username, String body) throws Exception {
        String text = "@" + BOT_USERNAME + " " + body;
        send("""
                {"update_id":%d,"message":{"message_id":%d,"date":1,"chat":{"id":%d,"type":"supergroup","title":"x"},\
                "from":{"id":%d,"is_bot":false,"first_name":"%s"%s},"text":"%s",\
                "entities":[{"type":"mention","offset":0,"length":%d}]}}""".formatted(
                        updateId,
                        500 + updateId++,
                        GROUP,
                        fromId,
                        firstName,
                        usernameJson(username),
                        text,
                        BOT_USERNAME.length() + 1));
    }

    private void tap(long fromId, String data, int messageId) throws Exception {
        send("""
                {"update_id":%d,"callback_query":{"id":"cb-%d","chat_instance":"ci",\
                "from":{"id":%d,"is_bot":false,"first_name":"u%d"},"data":"%s",\
                "message":{"message_id":%d,"date":1,"chat":{"id":%d,"type":"supergroup","title":"x"},\
                "from":{"id":2020,"is_bot":true,"first_name":"Komora"},"text":"m"}}}""".formatted(updateId, updateId++, fromId, fromId, data, messageId, GROUP));
    }

    private static String usernameJson(String username) {
        return username == null ? "" : ",\"username\":\"" + username + "\"";
    }

    private void send(String body) throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    /** The user message of the n-th Claude request — the system prompt names every tier, the user message only the ones that exist. */
    private static String userPrompt(int index) {
        return CLAUDE.requests()
                .get(index)
                .path("messages")
                .get(0)
                .path("content")
                .asText();
    }

    private static String lastText() {
        return TELEGRAM.sentMessages().getLast().path("text").asText();
    }

    private static String lastToast() {
        return TELEGRAM.callbackAnswers().getLast().path("text").asText();
    }

    private static String callbackOf(JsonNode message) {
        return message.path("reply_markup")
                .path("inline_keyboard")
                .get(0)
                .get(0)
                .path("callback_data")
                .asText();
    }
}
