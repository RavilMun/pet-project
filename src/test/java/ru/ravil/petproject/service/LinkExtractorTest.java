package ru.ravil.petproject.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class LinkExtractorTest {

    private final LinkExtractor linkExtractor = new LinkExtractor();

    @Test
    void extractReturnsLinksWithNormalizedDomains() {
        List<ExtractedLink> links = linkExtractor.extract("read https://www.Example.com/docs and https://habr.com/ru/articles/1");

        assertThat(links).containsExactly(
                new ExtractedLink("https://www.Example.com/docs", "example.com"),
                new ExtractedLink("https://habr.com/ru/articles/1", "habr.com")
        );
    }

    @Test
    void extractTrimsTrailingPunctuation() {
        List<ExtractedLink> links = linkExtractor.extract("open https://example.com/docs.");

        assertThat(links).containsExactly(new ExtractedLink("https://example.com/docs", "example.com"));
    }

    @Test
    void extractDeduplicatesUrlsPreservingOrder() {
        List<ExtractedLink> links = linkExtractor.extract("https://example.com https://example.com https://spring.io");

        assertThat(links).containsExactly(
                new ExtractedLink("https://example.com", "example.com"),
                new ExtractedLink("https://spring.io", "spring.io")
        );
    }

    @Test
    void extractReturnsEmptyListForTextWithoutLinks() {
        assertThat(linkExtractor.extract("plain text")).isEmpty();
    }
}
