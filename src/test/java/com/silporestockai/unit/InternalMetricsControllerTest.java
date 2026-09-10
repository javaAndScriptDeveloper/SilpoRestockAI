package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.silporestockai.config.MetricsProperties;
import com.silporestockai.controller.InternalMetricsController;
import com.silporestockai.service.MetricsService;
import com.silporestockai.service.PitchArtifactService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class InternalMetricsControllerTest {

    @Test
    void doesNotExistWhileNoTokenIsConfigured() {
        MetricsService metricsService = mock(MetricsService.class);
        PitchArtifactService pitchArtifactService = mock(PitchArtifactService.class);
        InternalMetricsController controller =
                new InternalMetricsController(new MetricsProperties(""), metricsService, pitchArtifactService);

        assertThat(controller.pitch("anything").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.pitch(null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.pitchArtifact("anything").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(metricsService);
        verifyNoInteractions(pitchArtifactService);
    }

    @Test
    void refusesAMissingOrWrongTokenAndServesTheRightOne() {
        MetricsService metricsService = mock(MetricsService.class);
        when(metricsService.markdown(any())).thenReturn("# report");
        InternalMetricsController controller = new InternalMetricsController(
                new MetricsProperties("s3cret"), metricsService, mock(PitchArtifactService.class));

        assertThat(controller.pitch(null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.pitch("wrong").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var ok = controller.pitch("s3cret");
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).isEqualTo("# report");
    }

    /** Task 55: the artifact is a second report behind the same shared secret, not a public endpoint. */
    @Test
    void servesThePitchArtifactOnlyToTheRightToken() {
        PitchArtifactService pitchArtifactService = mock(PitchArtifactService.class);
        when(pitchArtifactService.html()).thenReturn("<!doctype html>");
        InternalMetricsController controller = new InternalMetricsController(
                new MetricsProperties("s3cret"), mock(MetricsService.class), pitchArtifactService);

        assertThat(controller.pitchArtifact(null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.pitchArtifact("wrong").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var ok = controller.pitchArtifact("s3cret");
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).isEqualTo("<!doctype html>");
    }
}
