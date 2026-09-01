package com.silporestockai.service.telegram;

import org.springframework.stereotype.Service;

/**
 * The wording of a check-in, kept out of the service that decides when one happens.
 *
 * <p>Same split as {@code CartMessageService}: a copy change must never touch a class that writes to the database.
 */
@Service
public class CheckinMessageService {

    /**
     * Opens the conversation. Deliberately two short questions rather than a form: the answer is parsed by a model in
     * task 12, so free wording costs nothing, and a person answering a bot on a Tuesday evening will not fill in a
     * form.
     */
    public String promptText() {
        return "Як справи з їжею? Що вже закінчилось, а чого ще вистачає?";
    }
}
