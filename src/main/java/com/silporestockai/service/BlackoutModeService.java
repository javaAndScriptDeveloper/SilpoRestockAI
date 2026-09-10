package com.silporestockai.service;

import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.model.MatchingHints;
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
     * <p>Silpo's product data carries no "needs no cooking" flag — and no "needs a fridge" one either, checked
     * live in task 73 — so guessing either from a product name is how a demo ends up ordering frozen dumplings
     * during a blackout. «Готова страва» is gone from the list for that reason: what the catalog calls one is a
     * soup in a pouch.
     *
     * <p>Six lines, and each earns its place in a single meal: bread with tinned fish or pâté, water and juice
     * to drink, biscuits after. The list this replaced had eleven, and a live «світло вимкнули» turned them into
     * an eleven-line ₴778 shop holding Сир Spomlek «Радамер» and Шинка Алан — refrigerated, in a household whose
     * entire problem is that the fridge is off. Горіхи, яблука and банани went with them: they keep perfectly
     * well, but a bag holding tinned fish, fruit, nuts and biscuits is a grocery run, not an answer to «світло
     * вимкнули».
     *
     * <p>The lines stay one plain word each, and «паштет» is why. Narrowing it to «паштет консервований» to keep
     * the chilled aisle out looked safer and cost ₴23 a tin on a live run: Silpo's search is a plain text match,
     * the two-word term returned nothing at all, and the rescue pass bought a ₴99 pâté where the ordinary shelf
     * had one at ₴74.99. Which shelf a line may take from is the cart's constraint — {@code MatchingHints} — not
     * a phrase to be smuggled into the line's own name.
     *
     * <p>One or two of each, which leaves the kit under Silpo's ₴799 minimum on purpose (decided with the
     * household, 2026-09-10). The confirmation already says how short it is and offers the top-up; padding an
     * emergency order until a delivery threshold is cleared would be hiding the shop's rule rather than stating
     * it — and the last time a top-up filled that gap unasked it put flour and raw carrots in a no-stove lunch.
     */
    private static final List<BlackoutLine> NO_COOKING_NEEDED = List.of(
            new BlackoutLine("вода питна негазована", "2", "шт"),
            new BlackoutLine("сік", "1", "шт"),
            new BlackoutLine("хліб", "1", "шт"),
            new BlackoutLine("консерви рибні", "2", "шт"),
            new BlackoutLine("паштет", "2", "шт"),
            new BlackoutLine("печиво", "1", "шт"));

    private record BlackoutLine(String name, String quantity, String unit) {}

    private final CartConfirmationService cartConfirmationService;

    /**
     * Builds the emergency cart and puts it through the usual confirmation. Ad-hoc: the baseline is untouched.
     *
     * <p>The no-fridge constraint travels with the cart rather than living in this list, because the list is not
     * where it can be enforced: «сік» and «паштет консервований» are shelf-stable lines whose searches still
     * reach chilled shelves.
     */
    public void buildBlackoutOrder(User user, OrderTrigger trigger) {
        log.info("building a blackout order for user {}", user.getId());
        cartConfirmationService.present(
                user, items(user.getId()), OrderType.AD_HOC, false, trigger, MatchingHints.withoutAFridge());
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
