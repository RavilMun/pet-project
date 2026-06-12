package ru.ravil.petproject.service;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class LinkExtractor {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s<>()]+", Pattern.CASE_INSENSITIVE);

    public List<ExtractedLink> extract(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        LinkedHashMap<String, ExtractedLink> linksByUrl = new LinkedHashMap<>();
        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find()) {
            String url = trimTrailingPunctuation(matcher.group());
            String domain = extractDomain(url);
            if (domain != null) {
                linksByUrl.putIfAbsent(url, new ExtractedLink(url, domain));
            }
        }

        return List.copyOf(linksByUrl.values());
    }

    private String extractDomain(String url) {
        try {
            String host = URI.create(url).getHost();
            if (!StringUtils.hasText(host)) {
                return null;
            }

            String normalizedHost = host.toLowerCase(Locale.ROOT);
            return normalizedHost.startsWith("www.") ? normalizedHost.substring(4) : normalizedHost;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String trimTrailingPunctuation(String url) {
        String result = url;
        while (!result.isEmpty() && ".,!?;:)]}".indexOf(result.charAt(result.length() - 1)) >= 0) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
