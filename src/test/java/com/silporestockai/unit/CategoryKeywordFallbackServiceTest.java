package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.service.CategoryKeywordFallbackService;
import org.junit.jupiter.api.Test;

class CategoryKeywordFallbackServiceTest {

    private final CategoryKeywordFallbackService service = new CategoryKeywordFallbackService();

    @Test
    void matchesAKnownKeyword() {
        assertThat(service.categorize("Молоко 2.5%")).isEqualTo("Молочні продукти");
    }

    @Test
    void fallsBackForAnUnknownName() {
        assertThat(service.categorize("Дещо незрозуміле")).isEqualTo("Інше");
    }

    @Test
    void treatsANullNameAsUnknown() {
        assertThat(service.categorize(null)).isEqualTo("Інше");
    }
}
