package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.silporestockai.config.MetricsProperties;
import com.silporestockai.controller.InternalMetricsController;
import com.silporestockai.service.MetricsService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class InternalMetricsControllerTest {

    @Test
    void doesNotExistWhileNoTokenIsConfigured() {
        MetricsService metricsService = mock(MetricsService.class);
        InternalMetricsController controller = new InternalMetricsController(new MetricsProperties(""), metricsService);

        assertThat(controller.pitch("anything").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.pitch(null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(metricsService);
    }

    @Test
    void refusesAMissingOrWrongTokenAndServesTheRightOne() {
        MetricsService metricsService = mock(MetricsService.class);
        when(metricsService.markdown(any())).thenReturn("# report");
        InternalMetricsController controller =
                new InternalMetricsController(new MetricsProperties("s3cret"), metricsService);

        assertThat(controller.pitch(null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.pitch("wrong").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var ok = controller.pitch("s3cret");
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).isEqualTo("# report");
    }
}
