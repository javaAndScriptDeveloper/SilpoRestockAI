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
import org.junit.jupiter.api.Test;

/**
 * The committed Grafana dashboard queries metrics this application actually publishes (task 54).
 *
 * <p>This is the cheap guard against the standard way a checked-in dashboard rots: a meter gets renamed, nothing
 * fails, and the panel quietly shows «No data» until somebody notices during a pitch. Parsing the JSON and mapping
 * every {@code komora_*} series back to a {@link MeterNames} constant costs nothing and makes that a build failure.
 *
 * <p>It deliberately does not check PromQL semantics — only that every name is real. An aggregation being wrong is
 * caught by looking at the dashboard; a name being wrong is not.
 */
class DashboardJsonTest {

    private static final Path DASHBOARD = Path.of("observability/grafana/komora-dashboard.json");

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
    void everySeriesTheDashboardQueriesIsAMeterTheAppPublishes() throws IOException {
        Set<String> known = publishedMeterNames();
        Set<String> queried = seriesReferencedByTheDashboard();

        assertThat(queried).isNotEmpty();
        Set<String> unknown = new TreeSet<>();
        for (String series : queried) {
            if (!isKnown(series, known)) {
                unknown.add(series);
            }
        }
        assertThat(unknown)
                .as("dashboard panels query metrics that no MeterNames constant declares — either the meter was "
                        + "renamed and the dashboard was not, or the panel was written against a metric that was "
                        + "never implemented")
                .isEmpty();
    }

    @Test
    void theDashboardBindsItsDatasourceThroughAVariableSoOneFileFitsBothGrafanas() throws IOException {
        JsonNode dashboard = new ObjectMapper().readTree(Files.readString(DASHBOARD));

        JsonNode variable = dashboard.path("templating").path("list").get(0);
        assertThat(variable.path("type").asText()).isEqualTo("datasource");
        assertThat(variable.path("query").asText()).isEqualTo("prometheus");
        // Hardcoding a datasource uid would tie the file to one Grafana; the local harness and Grafana Cloud have
        // different ones, and the whole point is that the same file renders in both.
        assertThat(Files.readString(DASHBOARD)).contains("${DS}");
        assertThat(dashboard.path("uid").asText()).isEqualTo("komora-observability");
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

    private static Set<String> seriesReferencedByTheDashboard() throws IOException {
        JsonNode dashboard = new ObjectMapper().readTree(Files.readString(DASHBOARD));
        Set<String> series = new TreeSet<>();
        for (String expr : expressions(dashboard)) {
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
