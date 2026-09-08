package com.silporestockai.service;

import com.silporestockai.model.TelegramIncomingUpdate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * A drinks round in a Telegram group (task 68). Skeleton: the flow arrives in the next commits.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupEventService {

    /** Everything a group chat can send. */
    public void handle(TelegramIncomingUpdate incoming) {
        log.debug("group update in chat {}: {}", incoming.chatId(), incoming);
    }
}
