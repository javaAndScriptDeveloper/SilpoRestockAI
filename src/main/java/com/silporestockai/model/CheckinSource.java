package com.silporestockai.model;

/**
 * How a check-in arrived. Persisted by name.
 *
 * <p>Kept so the data stays distinguishable later: a photo reading is inherently rougher than a typed sentence, and
 * anything measuring parse quality has to be able to tell them apart.
 */
public enum CheckinSource {
    /** Typed into the chat. */
    TEXT,
    /** A voice note, transcribed before parsing. */
    VOICE,
    /** A photo of the fridge, read by the model. The approximate one. */
    PHOTO
}
