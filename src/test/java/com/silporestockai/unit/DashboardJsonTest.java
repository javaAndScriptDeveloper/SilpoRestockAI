package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.utils.MeterNames;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The committed Grafana dashboards query metrics this application actually publishes (tasks 54 and 75).
 *
 * <p>This is the cheap guard against the standard way a checked-in dashboard rots: a meter gets renamed, nothing
 * fails, and the panel quietly shows «No data» until somebody notices during a pitch. Parsing the JSON and mapping
 * every {@code komora_*} series back to a {@link MeterNames} constant costs nothing and makes that a build failure.
 *
 * <p>It deliberately does not check PromQL semantics — only that every name is real. An aggregation being wrong is
 * caught by looking at the dashboard; a name being wrong is not.
 *
 * <p>Task 75 adds the structural promises the two dashboards make: the business one has a guest section with no
 * money in it and a Silpo section that leads with Attributed Revenue; the technical one has MCP RED per tool.
 */
class DashboardJsonTest {

    private static final Path DIRECTORY = Path.of("observability/grafana");
    private static final Path BUSINESS = DIRECTORY.resolve("komora-business.json");
    private static final Path TECHNICAL = DIRECTORY.resolve("komora-observability.json");

    /** Any {@code komora_…} identifier appearing in a PromQL expression. */
    private static final Pattern SERIES = Pattern.compile("komora_[a-z0-9_]+");

    /**
     * What Prometheus appends to a Micrometer name: {@code _total} for a counter, the base unit for anything given
     * one, and the summary parts for a timer or distribution summary.
     */
    private static final List<String> SUFFIXES = List.of(
            "",
            "_total",
            "_uah",
            "_seconds",
            "_count",
            "_sum",
            "_bucket",
            "_max",
            "_uah_count",
            "_uah_sum",
            "_uah_max",
            "_seconds_count",
            "_seconds_sum",
            "_seconds_bucket",
            "_seconds_max");

    @Test
    void everySeriesEitherDashboardQueriesIsAMeterTheAppPublishes() throws IOException {
        Set<String> known = publishedMeterNames();
        for (Path dashboard : dashboards()) {
            Set<String> queried = seriesReferencedBy(dashboard);
            assertThat(queried).as(dashboard.toString()).isNotEmpty();
            Set<String> unknown = new TreeSet<>();
            for (String series : queried) {
                if (!isKnown(series, known)) {
                    unknown.add(series);
                }
            }
            assertThat(unknown)
                    .as(dashboard + " queries metrics that no MeterNames constant declares — either the meter was "
                            + "renamed and the dashboard was not, or the panel was written against a metric that "
                            + "was never implemented")
                    .isEmpty();
        }
    }

    @Test
    void exactlyTwoDashboardsExistAndEachBindsItsDatasourceThroughAVariable() throws IOException {
        assertThat(dashboards()).containsExactlyInAnyOrder(BUSINESS, TECHNICAL);
        assertThat(read(BUSINESS).path("uid").asText()).isEqualTo("komora-business");
        assertThat(read(TECHNICAL).path("uid").asText()).isEqualTo("komora-observability");
        for (Path path : dashboards()) {
            JsonNode variable = read(path).path("templating").path("list").get(0);
            assertThat(variable.path("type").asText()).isEqualTo("datasource");
            assertThat(variable.path("query").asText()).isEqualTo("prometheus");
            // Hardcoding a datasource uid would tie the file to one Grafana; the local harness and Grafana Cloud
            // have different ones, and the whole point is that the same file renders in both.
            assertThat(Files.readString(path)).contains("${DS}");
        }
    }

    /**
     * The business dashboard's two sections, as far as a file can carry the promise: the guest section carries no
     * money at all, and the Silpo section leads with the featuring money.
     */
    @Test
    void theBusinessDashboardKeepsMoneyOutOfTheGuestSection() throws IOException {
        JsonNode dashboard = read(BUSINESS);
        List<JsonNode> rows = dashboard.path("panels").findParents("type").stream()
                .filter(panel -> "row".equals(panel.path("type").asText()))
                .toList();
        assertThat(rows)
                .extracting(row -> row.path("title").asText())
                .anyMatch(title -> title.contains("A ·"))
                .anyMatch(title -> title.contains("B ·"));

        for (JsonNode panel : panelsUnderRow(dashboard, "A ·")) {
            String unit =
                    panel.path("fieldConfig").path("defaults").path("unit").asText();
            assertThat(unit)
                    .as(
                            "guest panel «%s» carries a currency unit",
                            panel.path("title").asText())
                    .doesNotStartWith("currency");
            assertThat(panel.toString())
                    .as(
                            "guest panel «%s» queries a money series",
                            panel.path("title").asText())
                    .doesNotContain("_uah")
                    .doesNotContain("₴");
        }
        List<String> silpoTitles = new ArrayList<>();
        panelsUnderRow(dashboard, "B ·")
                .forEach(panel -> silpoTitles.add(panel.path("title").asText()));
        assertThat(silpoTitles).anyMatch(title -> title.contains("Attributed Revenue"));
        assertThat(silpoTitles).anyMatch(title -> title.contains("PAID_PARTNER"));
        assertThat(silpoTitles).anyMatch(title -> title.contains("OWN_BRAND_MARGIN_BOOST"));
        assertThat(silpoTitles).anyMatch(title -> title.contains("GMV"));
        // The guest section's own headline: the new speed metric, per intent.
        assertThat(panelsUnderRow(dashboard, "A ·"))
                .anyMatch(panel -> panel.toString().contains("komora_intent_order_median_seconds"));
    }

    /** Task 64's promises, carried over into the rebuilt featuring block. */
    @Test
    void theFeaturingBlockExplainsItselfWithoutNarration() throws IOException {
        JsonNode dashboard = read(BUSINESS);
        List<JsonNode> all = panels(dashboard);

        // The industry name for FSR, so anyone who has bought retail media recognises the number instantly.
        assertThat(all.stream()
                        .filter(panel -> panel.path("title").asText().contains("Featured Share Rate"))
                        .map(panel -> panel.path("description").asText()))
                .anyMatch(description -> description.contains("Share of Shelf"));

        List<JsonNode> conversion = all.stream()
                .filter(panel -> panel.path("title").asText().contains("Conversion Rate"))
                .toList();
        assertThat(conversion)
                .as("stage-to-stage percentages must be labelled with the business term, once per pool")
                .hasSize(2);
        for (JsonNode panel : conversion) {
            // The live run produced 125 %: the bar clamps, the printed value stays true, and the description says
            // why a funnel stage can exceed its own previous stage at all.
            assertThat(panel.path("fieldConfig").path("defaults").path("max").asInt())
                    .isEqualTo(100);
            assertThat(panel.path("description").asText()).contains("не суворо вкладена");
        }
    }

    @Test
    void theTechnicalDashboardLeadsWithMcpRedPerTool() throws IOException {
        JsonNode dashboard = read(TECHNICAL);
        JsonNode first = dashboard.path("panels").get(0);
        assertThat(first.path("type").asText()).isEqualTo("row");
        assertThat(first.path("title").asText()).contains("RED");
        List<String> expressions = expressions(dashboard);
        assertThat(expressions)
                .anyMatch(expr -> expr.contains("komora_mcp_call_seconds_count") && expr.contains("by (tool)"))
                .anyMatch(
                        expr -> expr.contains("komora_mcp_call_seconds_bucket") && expr.contains("histogram_quantile"))
                .anyMatch(expr -> expr.contains("komora_claude_call_seconds"));
    }

    private static List<Path> dashboards() throws IOException {
        try (Stream<Path> files = Files.list(DIRECTORY)) {
            return files.filter(path -> path.toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
    }

    private static JsonNode read(Path path) throws IOException {
        return new ObjectMapper().readTree(Files.readString(path));
    }

    /** Every panel, including the ones nested inside a collapsed row. */
    private static List<JsonNode> panels(JsonNode dashboard) {
        List<JsonNode> all = new ArrayList<>();
        for (JsonNode panel : dashboard.path("panels")) {
            all.add(panel);
            panel.path("panels").forEach(all::add);
        }
        return all;
    }

    /**
     * The panels that sit under a row whose title contains the marker — the ones that follow it in the top-level
     * list until the next row, plus any nested inside it when the row is collapsed.
     */
    private static List<JsonNode> panelsUnderRow(JsonNode dashboard, String marker) {
        List<JsonNode> under = new ArrayList<>();
        boolean inside = false;
        for (JsonNode panel : dashboard.path("panels")) {
            if ("row".equals(panel.path("type").asText())) {
                inside = panel.path("title").asText().contains(marker);
                if (inside) {
                    panel.path("panels").forEach(under::add);
                }
                continue;
            }
            if (inside) {
                under.add(panel);
            }
        }
        return under;
    }

    private static boolean isKnown(String series, Set<String> known) {
        for (String suffix : SUFFIXES) {
            if (series.endsWith(suffix) && known.contains(series.substring(0, series.length() - suffix.length()))) {
                return true;
            }
        }
        return false;
    }

    /** Every {@code komora.*} constant on {@link MeterNames}, in the shape Prometheus renders it. */
    private static Set<String> publishedMeterNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Field field : MeterNames.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            try {
                String value = (String) field.get(null);
                if (value.startsWith("komora.")) {
                    names.add(value.replace('.', '_'));
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("could not read " + field.getName(), e);
            }
        }
        return names;
    }

    private static Set<String> seriesReferencedBy(Path dashboard) throws IOException {
        Set<String> series = new TreeSet<>();
        for (String expr : expressions(read(dashboard))) {
            Matcher matcher = SERIES.matcher(expr);
            while (matcher.find()) {
                series.add(matcher.group());
            }
        }
        return series;
    }

    private static List<String> expressions(JsonNode node) {
        List<String> found = new ArrayList<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if ("expr".equals(entry.getKey()) && entry.getValue().isTextual()) {
                    found.add(entry.getValue().asText());
                } else {
                    found.addAll(expressions(entry.getValue()));
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> found.addAll(expressions(child)));
        }
        return found;
    }
}
