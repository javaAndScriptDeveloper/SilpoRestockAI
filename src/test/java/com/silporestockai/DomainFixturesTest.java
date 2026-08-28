package com.silporestockai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;

import com.silporestockai.entity.CustomerOrder;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.OrderStatus;
import com.silporestockai.support.Fixtures;
import java.util.UUID;
import org.instancio.Instancio;
import org.instancio.junit.InstancioExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The fixture pattern later tasks copy: build a fully populated entity with {@link Fixtures}, then pin only the fields
 * the test actually asserts on. Keeps a test about order status from also having to invent a household size.
 */
@ExtendWith(InstancioExtension.class)
@DisplayName("domain entities can be built as test fixtures")
class DomainFixturesTest {

    @Test
    void buildsAFullyPopulatedUserProfile() {
        UserProfile profile = Fixtures.create(UserProfile.class);

        assertThat(profile.getId()).isNotNull();
        assertThat(profile.getUserId()).isNotNull();
        assertThat(profile.getHouseholdSize()).isNotNull();
        assertThat(profile.getSpecialMode()).isNotNull();
    }

    @Test
    void pinsOnlyTheFieldsUnderTest() {
        UUID userId = UUID.randomUUID();

        CustomerOrder order = Instancio.of(CustomerOrder.class)
                .set(field(CustomerOrder::getUserId), userId)
                .set(field(CustomerOrder::getStatus), OrderStatus.CONFIRMED)
                .create();

        assertThat(order.getUserId()).isEqualTo(userId);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getSilpoCartId()).isNotBlank();
    }
}
