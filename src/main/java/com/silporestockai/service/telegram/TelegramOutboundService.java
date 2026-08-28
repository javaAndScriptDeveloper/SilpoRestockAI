package com.silporestockai.service.telegram;

import com.silporestockai.config.TelegramProperties;
import com.silporestockai.exception.TelegramApiFailureException;
import com.silporestockai.model.TelegramButton;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
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

    public TelegramOutboundService(TelegramProperties properties) {
        this.apiUrl = stripTrailingSlash(properties.apiUrl());
        this.botToken = properties.botToken();
        this.client = new OkHttpTelegramClient(botToken, telegramUrl(apiUrl));
    }

    public void sendMessage(long chatId, String text) {
        SendMessage message = SendMessage.builder().chatId(chatId).text(text).build();
        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            throw failure("sendMessage", e);
        }
    }

    public void sendMessageWithButtons(long chatId, String text, List<TelegramButton> buttons) {
        InlineKeyboardRow row = new InlineKeyboardRow(buttons.stream()
                .map(button -> InlineKeyboardButton.builder()
                        .text(button.label())
                        .callbackData(button.callbackData())
                        .build())
                .toList());
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(InlineKeyboardMarkup.builder().keyboardRow(row).build())
                .build();
        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            throw failure("sendMessage", e);
        }
    }

    /** Stops the spinner Telegram shows on an inline button until the bot acknowledges the tap. */
    public void answerCallback(String callbackQueryId) {
        AnswerCallbackQuery answer =
                AnswerCallbackQuery.builder().callbackQueryId(callbackQueryId).build();
        try {
            client.execute(answer);
        } catch (TelegramApiException e) {
            throw failure("answerCallbackQuery", e);
        }
    }

    /**
     * Raw bytes of a voice note. Transcription is task 12; this only fetches.
     *
     * <p>The download deliberately does not go through the SDK: {@code File.getFileUrl(token)} hardcodes
     * {@code https://api.telegram.org} and ignores the configured {@link TelegramUrl}, so the SDK's own
     * {@code downloadFileAsStream} cannot be pointed anywhere else.
     */
    public byte[] downloadVoiceNote(String fileId) {
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
            throw new TelegramApiFailureException("interrupted while downloading a Telegram voice note", e);
        } catch (TelegramApiException | IOException e) {
            throw new TelegramApiFailureException("could not download the Telegram voice note", e);
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
