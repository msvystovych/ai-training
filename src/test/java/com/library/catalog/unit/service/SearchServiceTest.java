package com.library.catalog.unit.service;

import com.library.catalog.service.SearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private SearchService searchService;

    @Test
    void sanitizeQuery_trimsWhitespace() {
        String result = invokesSanitize("  hello world  ");
        assertThat(result).isEqualTo("hello world");
    }

    @Test
    void sanitizeQuery_truncatesLongQuery() {
        String longQuery = "a".repeat(600);
        String result = invokesSanitize(longQuery);
        assertThat(result.length()).isEqualTo(500);
    }

    @Test
    void sanitizeQuery_stripsNullBytes() {
        String result = invokesSanitize("hello\u0000world");
        assertThat(result).isEqualTo("helloworld");
    }

    @Test
    void sanitizeQuery_stripsControlCharacters() {
        String result = invokesSanitize("hello\u0001\u0002world");
        assertThat(result).isEqualTo("helloworld");
    }

    @Test
    void sanitizeQuery_preservesTabsAndNewlines() {
        String result = invokesSanitize("hello\tworld\ntest");
        assertThat(result).isEqualTo("hello\tworld\ntest");
    }

    @Test
    void sanitizeQuery_returnsEmptyForNull() {
        String result = invokesSanitize(null);
        assertThat(result).isEmpty();
    }

    @Test
    void search_withBlankQuery_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> searchService.search("   ", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blank");
    }

    @Test
    void search_withEmptyQuery_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> searchService.search("", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blank");
    }

    private String invokesSanitize(String input) {
        return ReflectionTestUtils.invokeMethod(searchService, "sanitizeQuery", input);
    }
}
