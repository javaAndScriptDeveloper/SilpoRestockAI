package com.silporestockai.service.telegram;

import com.silporestockai.config.TelegramProperties;
import com.silporestockai.entity.User;
import com.silporestockai.exception.TelegramApiFailureException;
import com.silporestockai.model.TelegramButton;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.utils.SecretRedactor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * The only way anything in this application talks to Telegram.
 *
 * <p>Domain services depend on this class, never on the Telegram SDK — an ArchUnit rule keeps SDK types inside
 * {@code controller.telegram} and {@code service.telegram}. A concrete class rather than an interface plus an
 * implementation: {@code ...Impl} would fail the {@code servicesAreNamedProperly} ArchUnit rule, and the tests drive a
 * stub Bot API over real HTTP instead of mocking this away.
 *
 * <p>The bot token is never logged. It appears only in outbound URL paths, which is where the Bot API puts it.
 */
@Slf4j
@Service
public class TelegramOutboundService {

    private final TelegramClient client;
    private final HttpClient fileDownloader = HttpClient.newHttpClient();
    private final String apiUrl;
    private final String botToken;
    private final String configuredBotUsername;

    /** Null until the first lookup; then whatever getMe answered, empty included, for the rest of the process. */
    private volatile Optional<String> lookedUpBotUsername;

    private final VoiceReplyService voiceReplyService;
    private final UserRepository userRepository;

    public TelegramOutboundService(
            TelegramProperties properties, VoiceReplyService voiceReplyService, UserRepository userRepository) {
        this.apiUrl = stripTrailingSlash(properties.apiUrl());
        this.botToken = properties.botToken();
        this.configuredBotUsername = properties.botUsername();
        this.client = new OkHttpTelegramClient(botToken, telegramUrl(apiUrl));
        this.voiceReplyService = voiceReplyService;
        this.userRepository = userRepository;
    }

    /**
     * Sends a message, and says it too when this chat asked for that with {@code /voice}.
     *
     * <p>Only plain messages are spoken. A message with buttons is a thing you tap, and reading a cart aloud two
     * items at a time would be worse than not speaking at all.
     */
    public void sendMessage(long chatId, String text) {
        logOutbound(chatId, text, List.of());
        SendMessage message = SendMessage.builder().chatId(chatId).text(text).build();
        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            throw failure("sendMessage", e);
        }
        speakIfWanted(chatId, text);
    }

    private void speakIfWanted(long chatId, String text) {
        if (!voiceReplyService.enabled()) {
            return;
        }
        boolean wanted = userRepository
                .findByTelegramChatId(chatId)
                .map(User::isVoiceRepliesEnabled)
                .orElse(false);
        if (wanted) {
            voiceReplyService.speak(text).ifPresent(wav -> sendAudioReply(chatId, wav));
        }
    }

    /**
     * Sends synthesised speech.
     *
     * <p>Respeecher answers with WAV, which Telegram's {@code sendVoice} does not accept — its documentation says
     * other formats "may be sent as Audio or Document", so that is what happens here, in that order. Transcoding to
     * Opus would mean a native encoder for a stretch feature.
     *
     * <p>Failures are logged and swallowed: the written message has already been delivered.
     */
    public void sendAudioReply(long chatId, byte[] wav) {
        InputFile audio = new InputFile(new ByteArrayInputStream(wav), "komora.wav");
        try {
            client.execute(SendAudio.builder().chatId(chatId).audio(audio).build());
            return;
        } catch (TelegramApiException e) {
            log.debug("Telegram refused the audio, falling back to a document: {}", e.getMessage());
        }
        try {
            client.execute(SendDocument.builder()
                    .chatId(chatId)
                    .document(new InputFile(new ByteArrayInputStream(wav), "komora.wav"))
                    .build());
        } catch (TelegramApiException e) {
            log.warn("could not deliver a voice reply to chat {}: {}", chatId, e.getMessage());
        }
    }

    /**
     * Sends a message and, with it, the persistent bottom keyboard — Telegram keeps that keyboard showing under the
     * text box for the rest of the chat from here on, so this is meant to be called once, right when the commands it
     * offers first become usable, not on every message.
     */
    public void sendMessageWithMainMenu(long chatId, String text) {
        logOutbound(chatId, text, List.of());
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(MainMenuKeyboard.markup())
                .build();
        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            throw failure("sendMessage", e);
        }
        speakIfWanted(chatId, text);
    }

    /**
     * Sends a message with an inline keyboard.
     *
     * @return the id Telegram gave the sent message — a group flow (task 68) keeps it, so a later reply can be
     *     recognised as an answer to this exact message
     */
    public int sendMessageWithButtons(long chatId, String text, List<TelegramButton> buttons) {
        logOutbound(chatId, text, buttons.stream().map(TelegramButton::label).toList());
        InlineKeyboardRow row = new InlineKeyboardRow(
                buttons.stream().map(TelegramOutboundService::toInlineButton).toList());
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(InlineKeyboardMarkup.builder().keyboardRow(row).build())
                .build();
        try {
            Message sent = client.execute(message);
            return sent == null || sent.getMessageId() == null ? 0 : sent.getMessageId();
        } catch (TelegramApiException e) {
            throw failure("sendMessage", e);
        }
    }

    /**
     * Sends a message as a reply to another one — in a busy group chat (task 68) an acknowledgement that hangs
     * under the message it answers is readable; the same words on their own, three messages later, are noise.
     *
     * @return the id of the sent message
     */
    public int sendReply(long chatId, int replyToMessageId, String text) {
        logOutbound(chatId, text, List.of());
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyToMessageId(replyToMessageId)
                .build();
        try {
            Message sent = client.execute(message);
            return sent == null || sent.getMessageId() == null ? 0 : sent.getMessageId();
        } catch (TelegramApiException e) {
            throw failure("sendMessage", e);
        }
    }

    /**
     * The bot's own {@code @username}, without the at sign — what a group member types to address it.
     *
     * <p>From configuration when set; otherwise one {@code getMe} call, cached for the life of the process. Empty
     * when neither is available: mentions are then not recognised, and the group flow still works through replies
     * to the bot's own messages and through commands, which need no username.
     */
    public Optional<String> botUsername() {
        if (configuredBotUsername != null && !configuredBotUsername.isBlank()) {
            return Optional.of(configuredBotUsername);
        }
        Optional<String> cached = lookedUpBotUsername;
        if (cached != null) {
            return cached;
        }
        try {
            org.telegram.telegrambots.meta.api.objects.User me = client.execute(new GetMe());
            cached = Optional.ofNullable(me == null ? null : me.getUserName());
        } catch (TelegramApiException | RuntimeException e) {
            log.warn("could not look up the bot's username with getMe: {}", e.getMessage());
            cached = Optional.empty();
        }
        lookedUpBotUsername = cached;
        return cached;
    }

    /**
     * Sends a message with a reply-keyboard WebApp button plus a plain-text fallback row.
     *
     * <p>Deliberately a {@code ReplyKeyboardMarkup}, not an inline one: only a WebApp opened from a reply-keyboard
     * button delivers its {@code Telegram.WebApp.sendData()} payload back as {@code message.web_app_data}. An inline
     * {@code web_app} button's data goes through {@code answerWebAppQuery} instead, which this application has no use
     * for.
     */
    public void sendMessageWithWebAppButton(
            long chatId, String text, String webAppLabel, String webAppUrl, String fallbackLabel) {
        logOutbound(chatId, text, List.of(webAppLabel, fallbackLabel));
        ReplyKeyboardMarkup markup = ReplyKeyboardMarkup.builder()
                .keyboardRow(new KeyboardRow(KeyboardButton.builder()
                        .text(webAppLabel)
                        .webApp(WebAppInfo.builder().url(webAppUrl).build())
                        .build()))
                .keyboardRow(new KeyboardRow(fallbackLabel))
                .resizeKeyboard(true)
                .oneTimeKeyboard(true)
                .build();
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(markup)
                .build();
        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            throw failure("sendMessage", e);
        }
    }

    /**
     * Stops the spinner Telegram shows on an inline button until the bot acknowledges the tap.
     *
     * <p>Reported and swallowed, never thrown — this is the one outbound call whose only effect is cosmetic. Every
     * flow acknowledges the tap as its first act, so a throw here aborted the whole handler before it did any of the
     * work the person actually asked for: a live «Замовити» tap died on {@code [400] query is too old and response
     * timeout expired}, no cart was ever built, and the household got «Щось пішло не так» for a button that worked.
     * A callback query expires in about a minute, so any tap Telegram redelivers — across a restart, after a slow
     * reply, over a dropped tunnel — arrives with an id that is already too old to answer. Losing the spinner is the
     * correct price for that; losing the order is not.
     */
    public void answerCallback(String callbackQueryId) {
        AnswerCallbackQuery answer =
                AnswerCallbackQuery.builder().callbackQueryId(callbackQueryId).build();
        try {
            client.execute(answer);
        } catch (TelegramApiException e) {
            log.warn("could not acknowledge callback query {}: {}", callbackQueryId, e.getMessage());
        }
    }

    /**
     * Raw bytes of any file Telegram is holding — a voice note, a fridge photo. This only fetches; what the bytes
     * mean is the caller's business.
     *
     * <p>The download deliberately does not go through the SDK: {@code File.getFileUrl(token)} hardcodes
     * {@code https://api.telegram.org} and ignores the configured {@link TelegramUrl}, so the SDK's own
     * {@code downloadFileAsStream} cannot be pointed anywhere else.
     */
    public byte[] downloadFile(String fileId) {
        try {
            File file = client.execute(GetFile.builder().fileId(fileId).build());
            URI uri = URI.create("%s/file/bot%s/%s".formatted(apiUrl, botToken, file.getFilePath()));
            HttpResponse<byte[]> response = fileDownloader.send(
                    HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new TelegramApiException("file download answered " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TelegramApiFailureException("interrupted while downloading a Telegram file", e);
        } catch (TelegramApiException | IOException e) {
            throw new TelegramApiFailureException("could not download the Telegram file", e);
        }
    }

    /** Registers the webhook URL with Telegram. Called once at startup by the registration service. */
    void setWebhook(String url, String secretToken) {
        SetWebhook.SetWebhookBuilder<?, ?> builder = SetWebhook.builder().url(url);
        if (secretToken != null && !secretToken.isBlank()) {
            builder.secretToken(secretToken);
        }
        try {
            client.execute(builder.build());
        } catch (TelegramApiException e) {
            throw failure("setWebhook", e);
        }
    }

    /**
     * What the household is about to read, in the log next to the MCP and Claude lines that produced it.
     *
     * <p>Every inbound update, every tool call and every prompt was already logged; the one thing missing was the
     * answer the person actually saw, so auditing a live run meant reading the chat on a phone. A checkout link is
     * the only thing in a message worth redacting and {@link SecretRedactor} already knows the shape.
     */
    private static void logOutbound(long chatId, String text, List<String> buttonLabels) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug(
                "Telegram -> chat {}{}: {}",
                chatId,
                buttonLabels.isEmpty() ? "" : " buttons=" + buttonLabels,
                SecretRedactor.truncate(text, 3000));
    }

    private static InlineKeyboardButton toInlineButton(TelegramButton button) {
        var builder = InlineKeyboardButton.builder().text(button.label());
        // Telegram rejects a button carrying both, so set exactly the one the caller chose.
        if (button.url() != null) {
            builder.url(button.url());
        } else {
            builder.callbackData(button.callbackData());
        }
        return builder.build();
    }

    /** The message carries the Bot API error, never the token — the token lives only in the URL path. */
    private static TelegramApiFailureException failure(String label, TelegramApiException e) {
        return new TelegramApiFailureException("Telegram " + label + " failed: " + e.getMessage(), e);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static TelegramUrl telegramUrl(String apiUrl) {
        URI uri = URI.create(apiUrl);
        int port = uri.getPort() != -1 ? uri.getPort() : "http".equals(uri.getScheme()) ? 80 : 443;
        return new TelegramUrl(uri.getScheme(), uri.getHost(), port, false);
    }
}
