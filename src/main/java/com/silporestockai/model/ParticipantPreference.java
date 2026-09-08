package com.silporestockai.model;

/**
 * The durable part of one participant's reply, as the model extracted it (task 68): «світле пиво», not
 * «сьогодні не п'ю віскі». Stored on this round's participant row only; later rounds read it as history.
 */
public record ParticipantPreference(long telegramUserId, String preferenceSummary) {}
