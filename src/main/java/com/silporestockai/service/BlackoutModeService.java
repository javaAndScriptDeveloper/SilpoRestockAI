package com.silporestockai.service;

import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.model.OrderType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Lunch with no stove and no fridge.
 *
 * <p>Not a second ordering pipeline: building a cart is task 09's job and confirming one is task 10's. The only thing
 * that is different in a blackout is what gets searched for, and that is the entire contents of this class.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlackoutModeService {

    /**
     * What a household can eat during an outage, written down rather than inferred.
     *
     * <p>Silpo's product data carries no "needs no cooking" flag, and guessing one from a product name is how a demo
     * ends up ordering frozen dumplings during a blackout. Short on purpose: this is a lunch, not a shop.
     */
    private static final List<String> NO_COOKING_NEEDED = List.of(
            "готова страва",
            "сендвіч",
            "консерви рибні",
            "паштет",
            "хліб",
            "горіхи",
            "печиво",
            "сік",
            "вода питна негазована");

    private final CartConfirmationService cartConfirmationService;

    /** Builds the emergency cart and puts it through the usual confirmation. Ad-hoc: the baseline is untouched. */
    public void buildBlackoutOrder(User user) {
        log.info("building a blackout order for user {}", user.getId());
        cartConfirmationService.present(user, items(user.getId()), OrderType.AD_HOC);
    }

    /** One unit of each. Quantities are not the interesting question when the lights are off. */
    private static List<ShoppingListItem> items(UUID userId) {
        return NO_COOKING_NEEDED.stream()
                .map(name -> ShoppingListItem.builder()
                        .id(UUID.randomUUID())
                        .userId(userId)
                        .name(name)
                        .quantity(BigDecimal.ONE)
                        .unit("шт")
                        .build())
                .toList();
    }
}
