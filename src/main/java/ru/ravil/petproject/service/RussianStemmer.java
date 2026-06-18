package ru.ravil.petproject.service;

/**
 * Dependency-free port of the Snowball Russian stemmer (https://snowballstem.org/algorithms/russian/stemmer.html).
 * Used by the hybrid ranker as a morphological alternative to the crude {@code commonPrefixLength}
 * token matching, so inflected forms of the same word ("книга"/"книги"/"книгу" → "книг") collapse to
 * one stem. Gated by {@code search.ranking.stemming-enabled} in {@code InboxItemSearchService}; off by
 * default so current ranking behaviour is unchanged until validated by the eval harness.
 *
 * <p>Operates on a single lowercase token; ё is folded to е. Non-Cyrillic tokens are returned as-is.
 */
final class RussianStemmer {

    private static final String VOWELS = "аеиоуыэюя";

    private RussianStemmer() {
    }

    // Ending groups (sorted longest-first within each group so the longest match wins).
    private static final String[] PERFECTIVE_GERUND_AYA = {"вшись", "вши", "в"};
    private static final String[] PERFECTIVE_GERUND = {"ывшись", "ившись", "ывши", "ивши", "ыв", "ив"};
    private static final String[] ADJECTIVE = {
            "ими", "ыми", "его", "ого", "ему", "ому", "их", "ых",
            "ее", "ие", "ые", "ое", "ей", "ий", "ый", "ой", "ем", "им", "ым", "ом",
            "ую", "юю", "ая", "яя", "ою", "ею"
    };
    private static final String[] PARTICIPLE_AYA = {"ющ", "нн", "вш", "ем", "щ"};
    private static final String[] PARTICIPLE = {"ующ", "ивш", "ывш"};
    private static final String[] REFLEXIVE = {"ся", "сь"};
    private static final String[] VERB_AYA = {
            "ешь", "нно", "ете", "йте", "ла", "на", "ли", "ем", "ло", "но", "ет", "ют", "ны", "ть", "й", "л", "н"
    };
    private static final String[] VERB = {
            "ейте", "уйте", "ила", "ыла", "ена", "ите", "или", "ыли", "ило", "ыло", "ено", "ует", "уют",
            "ишь", "ить", "ыть", "ены", "ят", "ит", "ыт", "ей", "уй", "ил", "ыл", "им", "ым", "ен", "ую", "ю"
    };
    private static final String[] NOUN = {
            "иями", "ями", "ами", "ией", "иям", "ием", "иях",
            "ие", "ье", "еи", "ии", "ей", "ой", "ий", "ям", "ем", "ам", "ом", "ах", "ях", "ия", "ья", "ев", "ов",
            "а", "е", "и", "й", "о", "у", "ы", "ь", "ю", "я"
    };
    private static final String[] SUPERLATIVE = {"ейше", "ейш"};
    private static final String[] DERIVATIONAL = {"ость", "ост"};

    static String stem(String token) {
        if (token == null || token.isEmpty()) {
            return token == null ? "" : token;
        }
        String word = token.toLowerCase(java.util.Locale.ROOT).replace('ё', 'е');
        if (!isCyrillic(word)) {
            return word;
        }

        int rv = rvIndex(word);
        int r2 = r2Index(word);
        StringBuilder w = new StringBuilder(word);

        // Step 1
        if (!removePerfectiveGerund(w, rv)) {
            removeEnding(w, rv, REFLEXIVE, false);
            if (!removeAdjectival(w, rv)
                    && !removeEnding(w, rv, VERB_AYA, true)
                    && !removeEnding(w, rv, VERB, false)) {
                removeEnding(w, rv, NOUN, false);
            }
        }

        // Step 2: trailing и
        removeEnding(w, rv, new String[]{"и"}, false);

        // Step 3: derivational ость/ост, only if the ending lies within R2
        if (endsAt(w, r2, DERIVATIONAL) != null) {
            removeEnding(w, r2, DERIVATIONAL, false);
        }

        // Step 4: undouble нн / strip superlative / strip soft sign (longest match wins)
        if (endsWith(w, "нн")) {
            w.setLength(w.length() - 1);
        } else if (removeEnding(w, rv, SUPERLATIVE, false)) {
            if (endsWith(w, "нн")) {
                w.setLength(w.length() - 1);
            }
        } else if (endsWith(w, "ь")) {
            w.setLength(w.length() - 1);
        }

        return w.toString();
    }

    private static boolean removePerfectiveGerund(StringBuilder w, int rv) {
        // longest match across both groups; group AYA additionally requires a preceding а/я
        String best = endsAtWithPreceding(w, rv, PERFECTIVE_GERUND, false);
        String bestAya = endsAtWithPreceding(w, rv, PERFECTIVE_GERUND_AYA, true);
        String chosen = longer(best, bestAya);
        if (chosen == null) {
            return false;
        }
        w.setLength(w.length() - chosen.length());
        return true;
    }

    private static boolean removeAdjectival(StringBuilder w, int rv) {
        if (!removeEnding(w, rv, ADJECTIVE, false)) {
            return false;
        }
        // an adjective ending may be preceded by a participle ending
        if (endsAtWithPreceding(w, rv, PARTICIPLE_AYA, true) != null) {
            removeEndingPreceding(w, rv, PARTICIPLE_AYA, true);
        } else {
            removeEnding(w, rv, PARTICIPLE, false);
        }
        return true;
    }

    /** Removes the longest ending from {@code group} found at/after {@code region}; returns whether removed. */
    private static boolean removeEnding(StringBuilder w, int region, String[] group, boolean requirePrecedingAYa) {
        String match = requirePrecedingAYa
                ? endsAtWithPreceding(w, region, group, true)
                : endsAt(w, region, group);
        if (match == null) {
            return false;
        }
        w.setLength(w.length() - match.length());
        return true;
    }

    private static boolean removeEndingPreceding(StringBuilder w, int region, String[] group, boolean aya) {
        String match = endsAtWithPreceding(w, region, group, aya);
        if (match == null) {
            return false;
        }
        w.setLength(w.length() - match.length());
        return true;
    }

    /** Longest ending in {@code group} that {@code w} ends with and whose start index is >= {@code region}. */
    private static String endsAt(StringBuilder w, int region, String[] group) {
        String best = null;
        for (String suffix : group) {
            int start = w.length() - suffix.length();
            if (start >= region && endsWith(w, suffix) && (best == null || suffix.length() > best.length())) {
                best = suffix;
            }
        }
        return best;
    }

    private static String endsAtWithPreceding(StringBuilder w, int region, String[] group, boolean aya) {
        String best = null;
        for (String suffix : group) {
            int start = w.length() - suffix.length();
            if (start < region || !endsWith(w, suffix)) {
                continue;
            }
            if (aya) {
                int p = start - 1;
                if (p < 0 || (w.charAt(p) != 'а' && w.charAt(p) != 'я')) {
                    continue;
                }
            }
            if (best == null || suffix.length() > best.length()) {
                best = suffix;
            }
        }
        return best;
    }

    private static String longer(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.length() >= b.length() ? a : b;
    }

    private static boolean endsWith(StringBuilder w, String suffix) {
        int start = w.length() - suffix.length();
        if (start < 0) {
            return false;
        }
        for (int i = 0; i < suffix.length(); i++) {
            if (w.charAt(start + i) != suffix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** RV: position after the first vowel (or end of word if none). */
    private static int rvIndex(String w) {
        for (int i = 0; i < w.length(); i++) {
            if (isVowel(w.charAt(i))) {
                return i + 1;
            }
        }
        return w.length();
    }

    /** R2: region after the first non-vowel-following-a-vowel inside R1. */
    private static int r2Index(String w) {
        int r1 = w.length();
        for (int i = 1; i < w.length(); i++) {
            if (!isVowel(w.charAt(i)) && isVowel(w.charAt(i - 1))) {
                r1 = i + 1;
                break;
            }
        }
        for (int i = r1 + 1; i < w.length(); i++) {
            if (!isVowel(w.charAt(i)) && isVowel(w.charAt(i - 1))) {
                return i + 1;
            }
        }
        return w.length();
    }

    private static boolean isVowel(char c) {
        return VOWELS.indexOf(c) >= 0;
    }

    private static boolean isCyrillic(String w) {
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (c >= 'а' && c <= 'я') {
                return true;
            }
        }
        return false;
    }
}
