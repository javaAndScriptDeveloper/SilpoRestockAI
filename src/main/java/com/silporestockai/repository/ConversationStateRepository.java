package com.silporestockai.repository;

import com.silporestockai.entity.ConversationState;
import org.springframework.data.jpa.repository.JpaRepository;

/** Conversation state, one row per Telegram chat. */
public interface ConversationStateRepository extends JpaRepository<ConversationState, Long> {}
