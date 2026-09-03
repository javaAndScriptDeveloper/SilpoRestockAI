package com.silporestockai.unit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.mapper.ShoppingListItemMapperImpl;
import com.silporestockai.model.ShoppingListStatus;
import com.silporestockai.repository.MealPlanRepository;
import com.silporestockai.repository.ShoppingListItemRepository;
import com.silporestockai.service.CategoryKeywordFallbackService;
import com.silporestockai.service.ShoppingListService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/**
 * Locks in the spec's "AI called only on real change" rule at the one place it's actually testable:
 * {@link ShoppingListService} must never touch a {@link ClaudeApiClient}, no matter which of its methods runs. A
 * mocked client with zero stubbing and an explicit {@code verifyNoInteractions} after every call is what turns "we
 * didn't mean to call it" into "it is not even reachable from here" — there is no {@code ClaudeApiClient} field on
 * this class for these calls to reach.
 */
class ShoppingListServiceManualEditTest {

    private final ClaudeApiClient claudeApiClient = mock(ClaudeApiClient.class);
    private final ShoppingListItemRepository repository = mock(ShoppingListItemRepository.class);
    private final MealPlanRepository mealPlanRepository = mock(MealPlanRepository.class);
    private final ShoppingListService service = new ShoppingListService(
            mealPlanRepository, repository, new ShoppingListItemMapperImpl(), new CategoryKeywordFallbackService());

    @Test
    void addingAnItemNeverTouchesClaude() {
        UUID userId = UUID.randomUUID();
        when(repository.save(ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));

        service.addItem(userId, "Молоко", new BigDecimal("2"), "л", null);

        verifyNoInteractions(claudeApiClient);
    }

    @Test
    void removingAnItemNeverTouchesClaude() {
        UUID userId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ShoppingListItem item = ShoppingListItem.builder()
                .id(itemId)
                .userId(userId)
                .name("Молоко")
                .status(ShoppingListStatus.ACTIVE)
                .build();
        when(repository.findById(itemId)).thenReturn(Optional.of(item));

        service.removeItem(userId, itemId);

        verifyNoInteractions(claudeApiClient);
    }

    @Test
    void changingAQuantityNeverTouchesClaude() {
        UUID userId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ShoppingListItem item = ShoppingListItem.builder()
                .id(itemId)
                .userId(userId)
                .name("Молоко")
                .status(ShoppingListStatus.ACTIVE)
                .build();
        when(repository.findById(itemId)).thenReturn(Optional.of(item));

        service.updateQuantity(userId, itemId, new BigDecimal("3"));

        verifyNoInteractions(claudeApiClient);
    }

    @Test
    void viewingTheCurrentListNeverTouchesClaude() {
        UUID userId = UUID.randomUUID();
        when(repository.findByUserIdAndStatus(userId, ShoppingListStatus.ACTIVE))
                .thenReturn(List.of());

        service.currentItems(userId);

        verifyNoInteractions(claudeApiClient);
    }
}
