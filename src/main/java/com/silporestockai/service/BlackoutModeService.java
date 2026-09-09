package com.silporestockai.service;

import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.model.OrderTrigger;
import com.silporestockai.model.OrderType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Food for a day or two with no stove and no fridge.
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
     * ends up ordering frozen dumplings during a blackout. Quantities are a small stock-up on purpose: one of
     * everything came to ₴742 on a live account, which is under Silpo's ₴799 minimum delivery order — and the
     * top-up that then filled the gap from the household's weekly baseline added flour and raw carrots to a
     * no-stove lunch. A kit that clears the minimum on its own, with nothing that needs a pan, is the right shape.
     * «Готова страва» is gone from it for the same reason: what the catalog calls one is a soup in a pouch.
     */
    private static final List<BlackoutLine> NO_COOKING_NEEDED = List.of(
            new BlackoutLine("вода питна негазована", "2", "шт"),
            new BlackoutLine("сік", "2", "шт"),
            new BlackoutLine("хліб", "2", "шт"),
            new BlackoutLine("консерви рибні", "2", "шт"),
            new BlackoutLine("паштет", "1", "шт"),
            new BlackoutLine("сир нарізаний", "1", "шт"),
            new BlackoutLine("шинка нарізана", "1", "шт"),
            new BlackoutLine("горіхи", "1", "шт"),
            new BlackoutLine("печиво", "1", "шт"),
            new BlackoutLine("яблука", "1", "кг"),
            new BlackoutLine("банани", "1", "кг"));

    private record BlackoutLine(String name, String quantity, String unit) {}

    private final CartConfirmationService cartConfirmationService;

    /** Builds the emergency cart and puts it through the usual confirmation. Ad-hoc: the baseline is untouched. */
    public void buildBlackoutOrder(User user, OrderTrigger trigger) {
        log.info("building a blackout order for user {}", user.getId());
        cartConfirmationService.present(user, items(user.getId()), OrderType.AD_HOC, false, trigger);
    }

    private static List<ShoppingListItem> items(UUID userId) {
        return NO_COOKING_NEEDED.stream()
                .map(line -> ShoppingListItem.builder()
                        .id(UUID.randomUUID())
                        .userId(userId)
                        .name(line.name())
                        .quantity(new BigDecimal(line.quantity()))
                        .unit(line.unit())
                        .build())
                .toList();
    }
}
