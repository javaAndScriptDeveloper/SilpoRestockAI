package com.silporestockai.model;

/** Where a piece of feedback came in from. One value today; the column exists so a second entry point needs no migration. */
public enum FeedbackSource {
    /** The persistent-menu «Фідбек» button (or the equivalent {@code /feedback} command). */
    BUTTON
}
