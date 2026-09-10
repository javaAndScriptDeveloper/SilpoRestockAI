package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.IntentClassification;
import com.silporestockai.entity.McpToolCall;
import com.silporestockai.service.PitchArtifactService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Task 55: the artifact renders what the tables hold, and nothing it was not given. */
class PitchArtifactServiceTest {

    private static final Instant AT = Instant.parse("2026-09-10T12:00:00Z");
    private static final UUID SOMEONE = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static McpToolCall call(String tool, boolean error) {
        return McpToolCall.builder()
                .id(UUID.randomUUID())
                .toolName(tool)
                .userId(SOMEONE)
                .error(error)
                .calledAt(AT)
                .build();
    }

    private static IntentClassification intent(String name, String outcome) {
        return IntentClassification.builder()
                .id(UUID.randomUUID())
                .intent(name)
                .outcome(outcome)
                .confidence(0.9)
                .userId(SOMEONE)
                .classifiedAt(AT)
                .build();
    }

    @Test
    void theHeadlineCountsDistinctToolsAgainstTheServersForty() {
        String html = PitchArtifactService.render(
                List.of(
                        call("silpo_find_products_batch", false),
                        call("silpo_find_products_batch", false),
                        call("silpo_create_shopping_cart", false)),
                List.of(),
                AT);

        assertThat(html).contains("2 з 40");
        assertThat(html).contains("3 виклик");
    }

    @Test
    void toolsAreOrderedByCallCountAndTheirFailuresAreCounted() {
        String html = PitchArtifactService.render(
                List.of(
                        call("silpo_get_time_slots", true),
                        call("silpo_find_products_batch", false),
                        call("silpo_find_products_batch", false)),
                List.of(),
                AT);

        assertThat(html.indexOf("silpo_find_products_batch")).isLessThan(html.indexOf("silpo_get_time_slots"));
        assertThat(html).contains("1 з помилкою");
    }

    @Test
    void aToolTheCodebaseDoesNotKnowRendersWithoutAnInventedPurpose() {
        String html = PitchArtifactService.render(List.of(call("silpo_something_new", false)), List.of(), AT);

        assertThat(html).contains("silpo_something_new");
        assertThat(PitchArtifactService.FLOW_NOTES).doesNotContainKey("silpo_something_new");
    }

    @Test
    void theIntentDistributionCountsEveryOutcomeIncludingTheFailedOnes() {
        String html = PitchArtifactService.render(
                List.of(),
                List.of(
                        intent("HANGOVER_RELIEF", "ROUTED"),
                        intent("HANGOVER_RELIEF", "ROUTED"),
                        intent("BLACKOUT", "ROUTED"),
                        intent("UNCLASSIFIED", "UNCLASSIFIED")),
                AT);

        assertThat(html).contains("HANGOVER_RELIEF").contains("BLACKOUT").contains("UNCLASSIFIED");
        assertThat(html.indexOf("HANGOVER_RELIEF")).isLessThan(html.indexOf("BLACKOUT"));
        // 3 of 4 classifications were routed — the page says so rather than hiding the miss.
        assertThat(html).contains("3 з 4");
    }

    @Test
    void aPageWithNoDataSaysSoInsteadOfRenderingAnEmptyTable() {
        String html = PitchArtifactService.render(List.of(), List.of(), AT);

        assertThat(html).contains("0 з 40");
        assertThat(html).doesNotContain("<tbody></tbody>");
    }

    @Test
    void thePageNeverCarriesAUserIdentifier() {
        String html = PitchArtifactService.render(
                List.of(call("silpo_find_products_batch", false)), List.of(intent("HELP", "ROUTED")), AT);

        assertThat(html).doesNotContain(SOMEONE.toString());
    }

    @Test
    void aToolNameFromTheServerIsEscapedRatherThanTrusted() {
        String html = PitchArtifactService.render(List.of(call("<script>alert(1)</script>", false)), List.of(), AT);

        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    void everyFlowNoteNamesAToolTheCodebaseActuallyCalls() throws IOException {
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            String code = sources.filter(path -> path.toString().endsWith(".java"))
                    // The notes themselves name every tool, so the page's own source cannot be its own evidence.
                    .filter(path -> !path.endsWith("PitchArtifactService.java"))
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .collect(Collectors.joining("\n"));
            assertThat(PitchArtifactService.FLOW_NOTES.keySet())
                    .allSatisfy(tool -> assertThat(code)
                            .as("a flow note for a tool nothing calls: %s", tool)
                            .contains('"' + tool + '"'));
        }
    }
}
