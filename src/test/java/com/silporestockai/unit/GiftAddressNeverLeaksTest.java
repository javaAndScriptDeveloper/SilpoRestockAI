package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.service.telegram.GiftMessageService;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sender may never see where a gift is going. In two of the three ways a destination is resolved the address
 * is the recipient's own, given on the understanding that it stays with the bot.
 *
 * <p>The real guard against a leak is structural and lives in {@link GiftMessageService} itself: not one method
 * there accepts an address, so there is nothing for one to be composed out of. What this test defends is that
 * property — a future method that grows an address parameter, or hardcodes a street into sender-facing copy, is
 * caught the day it is written rather than the day somebody reads their friend's address off their own screen.
 *
 * <p>The end-to-end assertions live where they belong, in {@code GiftResolutionIntegrationTest} and
 * {@code GiftCartBuildIntegrationTest}: both drive real resolutions and assert the sender's chat never contains
 * the address or the phone that reached Silpo.
 */
@DisplayName("nothing a gift sender reads carries an address")
class GiftAddressNeverLeaksTest {

    /**
     * What an address looks like: a street word followed by a name or a number, or a run of digits long enough to
     * be a phone.
     *
     * <p>The trailing capital or digit is what separates «вулиця Хрещатик» from «напиши місто, вулицю й номер
     * будинку» — the second is an instruction about addresses, which several of these messages legitimately give.
     */
    private static final Pattern ADDRESS_SHAPED =
            Pattern.compile("(вулиц\\p{L}*|вул\\.|проспект\\p{L}*)\\s+[\\p{Lu}\\d]|\\+?\\d{9}");

    /**
     * The messages that may legitimately show an example address.
     *
     * <p>Two are printed in the recipient's own chat, where an example of what to type is the entire point.
     * {@code recipientUnreachable} does reach a sender — but the address in it is the example inside its own
     * advice, «назви адресу сам, наприклад…», not anything read out of a stored row.
     */
    private static final List<String> MAY_SHOW_AN_EXAMPLE =
            List.of("askRecipientForAddress", "tellRecipientAboutTheGift", "recipientUnreachable");

    /** Realistic arguments: every parameter of every method here is a label, a theme or a delivery window. */
    private static final String LABEL = "@olena";

    @Test
    void noMethodHereAcceptsMoreThanALabelAThemeOrASlot() {
        for (Method method : publicMessages()) {
            assertThat(method.getParameterTypes())
                    .as("%s must take only Strings — an address has no business reaching this class", method.getName())
                    .allMatch(String.class::equals);
            assertThat(method.getParameterCount())
                    .as("%s grew a parameter; check it is not an address", method.getName())
                    .isLessThanOrEqualTo(2);
        }
    }

    @Test
    void noSenderFacingMessageContainsAnythingAddressShaped() throws Exception {
        GiftMessageService service = new GiftMessageService();
        int checked = 0;
        for (Method method : publicMessages()) {
            if (MAY_SHOW_AN_EXAMPLE.contains(method.getName())) {
                continue;
            }
            Object[] arguments = Arrays.stream(method.getParameterTypes())
                    .map(type -> (Object) LABEL)
                    .toArray();
            String produced = (String) method.invoke(service, arguments);
            assertThat(ADDRESS_SHAPED.matcher(produced).find())
                    .as("%s reads as though it carries an address: %s", method.getName(), produced)
                    .isFalse();
            checked++;
        }
        // A guard that silently checks nothing is worse than no guard: it reads as passing forever.
        assertThat(checked).isGreaterThanOrEqualTo(8);
    }

    @Test
    void theMessageAskingAFriendForTheirAddressPromisesTheSenderWillNotSeeIt() {
        assertThat(new GiftMessageService().askRecipientForAddress("@andrii", "щось до кави"))
                .contains("Хрещатик")
                .contains("Відправник твоєї адреси не побачить");
    }

    private static List<Method> publicMessages() {
        return Arrays.stream(GiftMessageService.class.getDeclaredMethods())
                .filter(method -> method.getReturnType().equals(String.class))
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .toList();
    }
}
