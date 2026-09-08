package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.GroupEventService;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Task 68: a group chat is routed to the group handler and never to the household path — no {@code users} row,
 * no onboarding greeting — and the record it receives says whether the message was aimed at the bot.
 */
@DisplayName("group chat updates reach the group handler, never a household")
class GroupEventRoutingIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "2020:stub-bot-token";
    private static final long GROUP = -100777L;
    private static final StubTelegramServer TELEGRAM = startTelegram();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @MockitoSpyBean
    private GroupEventService groupEventService;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
        registry.add("telegram.bot-username", () -> "komora_test_bot");
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        Mockito.clearInvocations(groupEventService);
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("the person who added the bot is the organizer, read from my_chat_member")
    void addIsReadFromMyChatMember() throws Exception {
        send("""
                {"update_id":1,"my_chat_member":{"chat":{"id":%d,"type":"supergroup","title":"Пʼятниця"},\
                "from":{"id":41,"is_bot":false,"first_name":"Олена","username":"olena"},"date":1,\
                "old_chat_member":{"status":"left","user":{"id":2020,"is_bot":true,"first_name":"Komora"}},\
                "new_chat_member":{"status":"member","user":{"id":2020,"is_bot":true,"first_name":"Komora"}}}}""".formatted(GROUP));

        TelegramIncomingUpdate incoming = received();
        assertThat(incoming).isEqualTo(new TelegramIncomingUpdate.BotAddedToGroup(GROUP, "Пʼятниця", 41L, "@olena"));
        assertThat(userRepository.findByTelegramChatId(GROUP)).isEmpty();
        assertThat(TELEGRAM.sentMessages()).isEmpty();
    }

    @Test
    @DisplayName("an ordinary group message is delivered as not addressed, and creates no household")
    void plainGroupTextIsNotAddressed() throws Exception {
        send(groupText(2, 42, "хто бере торт?", "", null));

        TelegramIncomingUpdate.GroupText text = (TelegramIncomingUpdate.GroupText) received();
        assertThat(text.addressedToBot()).isFalse();
        assertThat(text.displayName()).isEqualTo("Ігор");
        assertThat(userRepository.findAll()).isEmpty();
        assertThat(TELEGRAM.sentMessages()).isEmpty();
    }

    @Test
    @DisplayName("a reply to the bot's own message carries that message's id")
    void replyToBotIsRecognised() throws Exception {
        send(groupText(3, 42, "пиво світле", "", 7));

        TelegramIncomingUpdate.GroupText text = (TelegramIncomingUpdate.GroupText) received();
        assertThat(text.replyToBotMessageId()).isEqualTo(7);
        assertThat(text.addressedToBot()).isTrue();
    }

    @Test
    @DisplayName("a reply to another person is not a reply to the bot")
    void replyToPersonIsNotRecognised() throws Exception {
        send("""
                {"update_id":4,"message":{"message_id":40,"date":1,"chat":{"id":%d,"type":"group","title":"x"},\
                "from":{"id":42,"is_bot":false,"first_name":"Ігор"},"text":"+1",\
                "reply_to_message":{"message_id":9,"date":1,"chat":{"id":%d,"type":"group"},\
                "from":{"id":43,"is_bot":false,"first_name":"Марко"},"text":"вино"}}}""".formatted(GROUP, GROUP));

        TelegramIncomingUpdate.GroupText text = (TelegramIncomingUpdate.GroupText) received();
        assertThat(text.replyToBotMessageId()).isNull();
        assertThat(text.addressedToBot()).isFalse();
    }

    @Test
    @DisplayName("an @mention of this bot is recognised and stripped from the body")
    void mentionIsRecognised() throws Exception {
        send(groupText(
                5,
                42,
                "@komora_test_bot менше пива",
                ",\"entities\":[{\"type\":\"mention\",\"offset\":0,\"length\":16}]",
                null));

        TelegramIncomingUpdate.GroupText text = (TelegramIncomingUpdate.GroupText) received();
        assertThat(text.mentionsBot()).isTrue();
        assertThat(text.bodyWithoutAddress("komora_test_bot")).isEqualTo("менше пива");
    }

    @Test
    @DisplayName("a mention of some other bot is not a mention of this one")
    void otherMentionIsIgnored() throws Exception {
        send(groupText(
                6, 42, "@other_bot привіт", ",\"entities\":[{\"type\":\"mention\",\"offset\":0,\"length\":10}]", null));

        TelegramIncomingUpdate.GroupText text = (TelegramIncomingUpdate.GroupText) received();
        assertThat(text.mentionsBot()).isFalse();
        assertThat(text.addressedToBot()).isFalse();
    }

    @Test
    @DisplayName("a /command@thisbot is a command; one aimed at another bot is not")
    void commandsAreAttributed() throws Exception {
        send(groupText(
                7,
                41,
                "/drinks@komora_test_bot",
                ",\"entities\":[{\"type\":\"bot_command\",\"offset\":0,\"length\":23}]",
                null));
        TelegramIncomingUpdate.GroupText ours = (TelegramIncomingUpdate.GroupText) received();
        assertThat(ours.command()).isEqualTo("/drinks");
        assertThat(ours.bodyWithoutAddress("komora_test_bot")).isEmpty();

        Mockito.clearInvocations(groupEventService);
        send(groupText(
                8,
                41,
                "/drinks@other_bot",
                ",\"entities\":[{\"type\":\"bot_command\",\"offset\":0,\"length\":17}]",
                null));
        TelegramIncomingUpdate.GroupText theirs = (TelegramIncomingUpdate.GroupText) received();
        assertThat(theirs.command()).isNull();
        assertThat(theirs.addressedToBot()).isFalse();
    }

    @Test
    @DisplayName("a button tap on a group message is a group tap")
    void groupCallbackIsAGroupTap() throws Exception {
        send("""
                {"update_id":9,"callback_query":{"id":"cb-1","from":{"id":41,"is_bot":false,"first_name":"Олена"},\
                "chat_instance":"x","data":"grp:freeze:abc",\
                "message":{"message_id":12,"date":1,"chat":{"id":%d,"type":"supergroup","title":"x"},\
                "from":{"id":2020,"is_bot":true,"first_name":"Komora"},"text":"greeting"}}}""".formatted(GROUP));

        assertThat(received())
                .isEqualTo(
                        new TelegramIncomingUpdate.GroupButtonTap(GROUP, 41L, "Олена", "cb-1", "grp:freeze:abc", 12));
        assertThat(userRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("the bot being removed is delivered as such")
    void removalIsDelivered() throws Exception {
        send("""
                {"update_id":10,"my_chat_member":{"chat":{"id":%d,"type":"group","title":"x"},\
                "from":{"id":41,"is_bot":false,"first_name":"Олена"},"date":1,\
                "old_chat_member":{"status":"member","user":{"id":2020,"is_bot":true,"first_name":"Komora"}},\
                "new_chat_member":{"status":"kicked","user":{"id":2020,"is_bot":true,"first_name":"Komora"},"until_date":0}}}""".formatted(GROUP));

        assertThat(received()).isEqualTo(new TelegramIncomingUpdate.BotRemovedFromGroup(GROUP));
    }

    @Test
    @DisplayName("a private chat still goes down the household path")
    void privateChatIsUntouched() throws Exception {
        send("""
                {"update_id":11,"message":{"message_id":1,"date":1,"chat":{"id":5001,"type":"private"},\
                "from":{"id":5001,"is_bot":false,"first_name":"Тест"},"text":"/start"}}""");

        verify(groupEventService, never()).handle(any());
        assertThat(userRepository.findByTelegramChatId(5001L)).isPresent();
        assertThat(TELEGRAM.sentMessages()).isNotEmpty();
    }

    private TelegramIncomingUpdate received() {
        ArgumentCaptor<TelegramIncomingUpdate> captor = ArgumentCaptor.forClass(TelegramIncomingUpdate.class);
        verify(groupEventService, timeout(2000)).handle(captor.capture());
        return captor.getValue();
    }

    private static String groupText(int updateId, long fromId, String text, String extraJson, Integer replyToBot) {
        String reply = replyToBot == null
                ? ""
                : (",\"reply_to_message\":{\"message_id\":%d,\"date\":1,\"chat\":{\"id\":%d,\"type\":\"group\"},"
                                + "\"from\":{\"id\":2020,\"is_bot\":true,\"first_name\":\"Komora\"},\"text\":\"hi\"}")
                        .formatted(replyToBot, GROUP);
        return """
                {"update_id":%d,"message":{"message_id":%d,"date":1,"chat":{"id":%d,"type":"group","title":"x"},\
                "from":{"id":%d,"is_bot":false,"first_name":"Ігор"},"text":"%s"%s%s}}""".formatted(updateId, 100 + updateId, GROUP, fromId, text, extraJson, reply);
    }

    private void send(String body) throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}
