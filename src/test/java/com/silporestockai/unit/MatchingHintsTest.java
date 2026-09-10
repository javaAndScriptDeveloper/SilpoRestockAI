package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.model.MatchingHints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("what the product choice knows about a cart beyond its lines")
class MatchingHintsTest {

    @Test
    void aWeeklyPlanConstrainsNothing() {
        assertThat(MatchingHints.NONE.noColdChain()).isFalse();
        assertThat(MatchingHints.ofWords("привези води").noColdChain()).isFalse();
    }

    @Test
    void aBlackoutCartRefusesTheColdChain() {
        MatchingHints hints = MatchingHints.withoutAFridge();

        assertThat(hints.noColdChain()).isTrue();
        assertThat(hints.personsWords()).isNull();
        assertThat(hints.alsoSearchFor("хліб")).isEmpty();
    }
}
