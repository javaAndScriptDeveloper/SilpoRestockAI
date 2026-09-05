package com.silporestockai.service.onboarding;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.silporestockai.config.TelegramProperties;
import com.silporestockai.entity.User;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.MealPlanHandoffService;
import com.silporestockai.service.SilpoAuthService;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class OnboardingFlowServiceTest {

    @Test
    void reopenFormWithoutWebAppSaysSo() {
        TelegramProperties properties = new TelegramProperties(null, null, null, null, "");
        TelegramOutboundService outbound = mock(TelegramOutboundService.class);
        OnboardingFlowService service = new OnboardingFlowService(
                mock(UserProfileRepository.class),
                mock(UserRepository.class),
                mock(ConversationStateService.class),
                mock(ProfileEnrichmentService.class),
                outbound,
                mock(SilpoAuthService.class),
                properties,
                mock(ApplicationEventPublisher.class),
                mock(MealPlanHandoffService.class));
        User user = User.builder().id(UUID.randomUUID()).telegramChatId(42L).build();

        service.reopenForm(user);

        verify(outbound).sendMessage(42L, "Анкета зараз недоступна.");
        verify(outbound, never())
                .sendMessageWithWebAppButton(anyLong(), anyString(), anyString(), anyString(), anyString());
    }
}
