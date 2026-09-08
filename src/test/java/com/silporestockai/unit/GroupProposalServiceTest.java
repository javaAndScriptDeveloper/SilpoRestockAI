package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.service.GroupProposalService;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("group proposal arithmetic")
class GroupProposalServiceTest {

    @Test
    void quantitiesAreClampedToThreePerHeadAndNeverBelowOne() {
        assertThat(GroupProposalService.clampQuantity(new BigDecimal("40"), 3)).isEqualByComparingTo("9");
        assertThat(GroupProposalService.clampQuantity(new BigDecimal("0.5"), 3)).isEqualByComparingTo("1");
        assertThat(GroupProposalService.clampQuantity(null, 3)).isEqualByComparingTo("1");
        assertThat(GroupProposalService.clampQuantity(new BigDecimal("6"), 3)).isEqualByComparingTo("6");
    }

    @Test
    void theSplitIsAnEvenTwoDecimalDivision() {
        assertThat(GroupProposalService.perHead(new BigDecimal("1000"), 3)).isEqualByComparingTo("333.33");
        assertThat(GroupProposalService.perHead(new BigDecimal("1000"), 0)).isNull();
        assertThat(GroupProposalService.perHead(null, 3)).isNull();
    }
}
