package com.silporestockai.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.model.GiftAddress;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The address object handed to {@code silpo_update_shopping_cart} for a gift.
 *
 * <p>Probed live on 2026-09-10: the server keeps {@code phone}, {@code flat}, {@code entrance}, {@code floor} and
 * {@code courrierComment} verbatim, and hands the coordinates back as strings. So this is what has to be sent —
 * not a tidier shape of our own.
 */
@DisplayName("the address a gift is delivered to")
class GiftAddressArgumentsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode place() throws Exception {
        return MAPPER.readTree("""
                {"address":"Київ, вулиця Хрещатик, 22","city":"Київ","street":"вулиця Хрещатик",
                 "houseNumber":"22","district":"Центр","latitude":50.4498465,"longitude":30.5230925}
                """);
    }

    @Test
    void carriesTheDoorAndTheNumberToCallWithCoordinatesAsStrings() throws Exception {
        GiftAddress destination = new GiftAddress("Київ, вулиця Хрещатик, 22", "42", "3", "5", "+380671234567");

        Map<String, Object> address = CartBuildingService.giftAddressArguments(place(), destination);

        assertThat(address)
                .containsEntry("addressType", "flat")
                .containsEntry("city", "Київ")
                .containsEntry("street", "вулиця Хрещатик")
                .containsEntry("house", "22")
                .containsEntry("district", "Центр")
                .containsEntry("flat", "42")
                .containsEntry("entrance", "3")
                .containsEntry("floor", "5")
                .containsEntry("phone", "+380671234567")
                .containsEntry("latitude", "50.4498465")
                .containsEntry("longitude", "30.5230925");
        assertThat(address.get("courrierComment").toString()).contains("Подарунок");
    }

    @Test
    void aBuildingWithNoApartmentIsAHouse() throws Exception {
        Map<String, Object> address = CartBuildingService.giftAddressArguments(
                place(), new GiftAddress("Київ, вулиця Хрещатик, 22", null, null, null, "+380671234567"));

        assertThat(address).containsEntry("addressType", "house").doesNotContainKey("flat");
    }

    @Test
    void noPhoneMeansNoPhoneKeyRatherThanAnEmptyOne() throws Exception {
        Map<String, Object> address = CartBuildingService.giftAddressArguments(
                place(), new GiftAddress("Київ, вулиця Хрещатик, 22", "42", null, null, null));

        assertThat(address).doesNotContainKey("phone");
    }
}
