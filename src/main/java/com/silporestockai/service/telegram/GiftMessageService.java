package com.silporestockai.service.telegram;

import org.springframework.stereotype.Service;

/**
 * What a gift sounds like in a chat (task 81).
 *
 * <p>No method here takes an address. Two of the three ways a gift's destination is arrived at are the
 * recipient's own information, given on the understanding that it stays with the bot — and a class that accepted
 * an address would be one careless refactor away from printing it on the sender's screen.
 */
@Service
public class GiftMessageService {

    /** Asking a friend who never opted in. Their chat, their choice, and their words are what resolves it. */
    public String askRecipientForAddress(String senderLabel, String theme) {
        return ("""
                🎁 %s хоче надіслати тобі подарунок через «Сільпо»%s.

                Куди привезти? Напиши одним повідомленням адресу, квартиру й телефон для кур'єра — \
                наприклад: «Київ, вулиця Хрещатик 22, кв. 42, +380671234567».

                Відправник твоєї адреси не побачить — я передаю її лише в замовлення. \
                Не хочеш — просто напиши «ні».""").formatted(senderLabel, theme == null || theme.isBlank() ? "" : " — " + theme);
    }

    /**
     * Telling a friend who did opt in.
     *
     * <p>They consented to storing an address, not to this particular delivery, and somebody has to be home when
     * a courier arrives. Product owner's call (2026-09-10) over keeping it a surprise.
     */
    public String tellRecipientAboutTheGift(String senderLabel, String slot) {
        return ("🎁 %s надсилає тобі подарунок через «Сільпо» — привезуть на твою збережену адресу, %s. "
                        + "Якщо цей час не підходить, скажи мені, і я передам.")
                .formatted(senderLabel, slot);
    }

    /** The sender, once somebody else's address is in hand. Deliberately says nothing about what it is. */
    public String addressInHand(String recipientLabel) {
        return "Адресу для %s маю — збираю кошик.".formatted(recipientLabel);
    }

    /** The sender, while a friend has not answered yet. */
    public String waitingOnTheRecipient(String recipientLabel) {
        return "Запитав у %s адресу. Щойно відповість — зберу кошик і покажу тобі.".formatted(recipientLabel);
    }

    /** The honest dead end: there is no chat to ask in, so the sender is told exactly that. */
    public String recipientUnreachable(String recipientLabel) {
        return ("%s ще не користувався ботом, тому не можу його спитати. "
                        + "Назви адресу сам, якщо знаєш — наприклад: «надішли подарунок на Київ, "
                        + "вулиця Хрещатик 22, кв. 42, +380671234567».")
                .formatted(recipientLabel);
    }

    /** Nobody answered inside the window. */
    public String requestExpired(String recipientLabel) {
        return ("%s поки не відповів про адресу, тож я поставив цей подарунок на паузу. "
                        + "Скажи ще раз, коли захочеш спробувати — або назви адресу сам.")
                .formatted(recipientLabel);
    }

    /** The recipient said no. Their answer, passed on without their reasons. */
    public String recipientDeclined(String recipientLabel) {
        return "%s поки не хоче отримувати подарунок. Нічого не замовляв.".formatted(recipientLabel);
    }

    /** The sender typed an address but no number to call. */
    public String askSenderForPhone(String recipientLabel) {
        return ("Який телефон у %s? Кур'єр телефонує за номером із замовлення, і без нього дзвонитимуть тобі — "
                        + "а адреси ти не бачиш, тож підказати під'їзд не вийде. "
                        + "Якщо номера немає, напиши «не знаю».")
                .formatted(recipientLabel);
    }

    /** The sender chose to go without a number. Said plainly, once, rather than discovered at the door. */
    public String noPhoneWarning() {
        return "Добре, відправлю без номера отримувача — тоді кур'єр телефонуватиме тобі.";
    }

    /** Silpo does not drive there. */
    public String deliveryUnavailable() {
        return "«Сільпо» не доставляє за цією адресою — доставки додому туди немає. Спробуй іншу адресу.";
    }

    /** The geocoder found nothing. */
    public String addressNotFound() {
        return "Не зміг знайти таку адресу в «Сільпо». Напиши точніше — місто, вулицю й номер будинку.";
    }

    /** Nobody was named at all. */
    public String whoIsItFor() {
        return "Кому надіслати? Назви @нік друга або адресу — і що саме привезти.";
    }

    /** The sentence was about a gift but nothing in it could be read. */
    public String couldNotRead() {
        return "Не зрозумів, кому надіслати. Напиши, наприклад: «надішли подарунок @нік, щось до кави».";
    }
}
