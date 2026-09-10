package com.silporestockai.service.telegram;

import com.silporestockai.model.BenefitsOverview;
import com.silporestockai.model.GiftCertificate;
import com.silporestockai.model.LoyaltyCoupon;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

/**
 * The «які в мене вигоди?» answer (task 79).
 *
 * <p>Written around one distinction, because it is the only one a household can act on: what this bot applies by
 * itself when an order is confirmed, and what nothing but the Silpo app can do. The second list is not a smaller
 * version of the first — the live MCP server has no tool that applies a coupon, activates a personal promo or
 * changes a Плюхс subscription, so promising any of that would be a lie the code could not keep.
 */
@Service
public class BenefitsMessageService {

    public String overviewText(BenefitsOverview overview) {
        if (overview == null || !overview.anythingRead()) {
            return "«Сільпо» зараз не відповіло про бонуси й купони — спробуй ще раз за кілька хвилин.";
        }
        StringBuilder text = new StringBuilder("💳 Твої вигоди в «Сільпо»\n");

        text.append("\nЦе застосую сам, коли підтвердиш замовлення:");
        text.append("\n• Балабонуси: ")
                .append(overview.bonusBalance() == null ? "не побачив" : amount(overview.bonusBalance()));
        if (overview.certificates().isEmpty()) {
            text.append("\n• Сертифікати: немає");
        } else {
            for (GiftCertificate certificate : overview.certificates()) {
                text.append("\n• Сертифікат ")
                        .append(certificate.value() == null ? "" : money(certificate.value()) + " грн ")
                        .append('(')
                        .append(certificate.maskedBarcode())
                        .append(')');
                if (certificate.expiresOn() != null) {
                    text.append(" — діє до ").append(certificate.expiresOn());
                }
            }
        }
        text.append("\n• Промокоди: ")
                .append(overview.promoCodes().isEmpty() ? "немає" : String.join(", ", overview.promoCodes()));

        text.append("\n\nА це працює тільки у застосунку «Сільпо» — там немає дії, якою я міг би це ввімкнути:");
        if (overview.coupons().isEmpty()) {
            text.append("\n• Купонів немає");
        } else {
            for (LoyaltyCoupon coupon : overview.coupons()) {
                text.append("\n• ").append(coupon.label());
                if (coupon.endDate() != null) {
                    text.append(" (до ").append(coupon.endDate()).append(')');
                }
                // «Увімкнений» is the household's own toggle; «спрацює» is Silpo's eligibility answer. They are
                // different questions and the tool's description says never to read one off the other.
                text.append(coupon.active() ? " — увімкнений" : " — вимкнений");
                if (Boolean.FALSE.equals(coupon.canBeApplied())) {
                    text.append(", зараз не спрацює");
                }
                if (coupon.progressText() != null) {
                    text.append(", прогрес: ").append(coupon.progressText());
                }
                if (coupon.limitText() != null && !coupon.limitText().isBlank()) {
                    text.append("\n  ")
                            .append(coupon.limitText().replace('\r', ' ').replace('\n', ' '));
                }
            }
        }
        for (String promo : overview.promos()) {
            text.append("\n• Персональна пропозиція: ").append(promo);
        }
        if (overview.premiumSummary() != null) {
            text.append("\n• «Плюхс»: ").append(overview.premiumSummary());
        }
        for (String link : overview.premiumLinks()) {
            text.append("\n  ").append(link);
        }
        return text.toString();
    }

    /** Two decimals for money, as everywhere else a person reads a price. */
    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    /** Bonus counts read better without trailing zeros. */
    private static String amount(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.UNNECESSARY) : stripped).toPlainString();
    }
}
