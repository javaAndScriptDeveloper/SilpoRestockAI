package com.silporestockai.service;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.entity.User;
import com.silporestockai.model.DishIngredients;
import com.silporestockai.model.OrderTrigger;
import com.silporestockai.model.PlannedIngredient;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

class DishIngredientsServiceTest {

    @Test
    void asksOnceMoreWhenTheModelAnswersAKnownDishWithNoIngredients() {
        // Live, session 25: «гречана каша на молоці» came back with items=[] and the household read «Не зрозумів».
        ClaudeApiClient claude = mock(ClaudeApiClient.class);
        TelegramOutboundService outbound = mock(TelegramOutboundService.class);
        when(claude.completeStructured(anyString(), anyString(), eq(DishIngredients.class)))
                .thenReturn(new DishIngredients("Гречана каша на молоці", 2, List.of()))
                .thenReturn(new DishIngredients(
                        "Гречана каша на молоці",
                        2,
                        List.of(new PlannedIngredient(
                                "гречка", new BigDecimal("1"), "кг", "Крупи і бакалія", null, null))));
        DishIngredientsService service = new DishIngredientsService(
                claude,
                mock(UserProfileRepository.class),
                mock(CartConfirmationService.class),
                outbound,
                new ByteArrayResource("system".getBytes()),
                new ByteArrayResource("identify".getBytes()));
        User user = User.builder().id(UUID.randomUUID()).telegramChatId(7L).build();

        service.orderIngredients(
                user, "гречана каша на молоці", OrderTrigger.of("DISH_INGREDIENTS_ORDER", java.time.Instant.now()));

        verify(claude, times(2)).completeStructured(anyString(), anyString(), eq(DishIngredients.class));
        verify(claude).completeStructured(anyString(), contains("порожній items"), eq(DishIngredients.class));
        verify(outbound, never()).sendMessage(anyLong(), contains("Не зрозумів"));
    }
}
