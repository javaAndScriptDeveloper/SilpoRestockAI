package com.silporestockai.model;

import java.math.BigDecimal;

/**
 * One gift certificate the household owns, as {@code silpo_get_my_certificates} describes it.
 *
 * <p>The barcode and the PIN together are what {@code silpo_add_or_update_certificates} needs to put it on a cart;
 * the PIN is optional in the tool's schema and absent for some certificate kinds, so it is nullable here too.
 *
 * @param barcode the certificate's own barcode — the id every other tool refers to it by
 * @param pincode the PIN, required for gift certificates and null when the response carried none
 * @param value what the certificate is worth, or null when Silpo did not say
 * @param expiresOn the expiry as Silpo wrote it. Deliberately a string: the tool's own description warns that
 *     {@code expireDate} carries no timezone offset, unlike every other timestamp in this API, so it is shown as
 *     an approximate date rather than parsed into an instant that would be wrong by up to a day.
 */
public record GiftCertificate(String barcode, String pincode, BigDecimal value, String expiresOn) {

    /** The last four digits, which is how a person recognises their own certificate without reading it out loud. */
    public String maskedBarcode() {
        if (barcode == null) {
            return "";
        }
        return barcode.length() <= 4 ? barcode : "…" + barcode.substring(barcode.length() - 4);
    }
}
