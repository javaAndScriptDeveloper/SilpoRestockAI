package com.silporestockai.service.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/** Which Telegram failures may be retried: only the ones that provably delivered nothing. */
class TelegramOutboundServiceTest {

    @Test
    void aNameThatDidNotResolveIsRetriable() {
        var e = new TelegramApiException(
                "Unable to execute sendmessage method",
                new UnknownHostException("api.telegram.org: Temporary failure in name resolution"));
        assertThat(TelegramOutboundService.failedBeforeReachingTelegram(e)).isTrue();
    }

    @Test
    void aRefusedConnectionIsRetriableEvenWhenWrappedTwice() {
        var e = new TelegramApiException(
                "outer", new RuntimeException("middle", new ConnectException("Connection refused")));
        assertThat(TelegramOutboundService.failedBeforeReachingTelegram(e)).isTrue();
    }

    @Test
    void aTimeoutIsNotRetriableBecauseTheMessageMayHaveLanded() {
        var e = new TelegramApiException("Unable to execute sendmessage method", new SocketTimeoutException("timeout"));
        assertThat(TelegramOutboundService.failedBeforeReachingTelegram(e)).isFalse();
    }

    @Test
    void aBotApiErrorIsNotRetriable() {
        var e = new TelegramApiException("Error sending message: [400] Bad Request: chat not found");
        assertThat(TelegramOutboundService.failedBeforeReachingTelegram(e)).isFalse();
    }
}
