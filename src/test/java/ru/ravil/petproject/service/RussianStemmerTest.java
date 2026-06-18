package ru.ravil.petproject.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RussianStemmerTest {

    @Test
    void collapsesNounDeclensions() {
        assertAllStemEqual(List.of("книга", "книги", "книгу", "книге", "книгой", "книгами", "книгах"));
        assertAllStemEqual(List.of("стол", "стола", "столу", "столе", "столом", "столы", "столов"));
    }

    @Test
    void collapsesAdjectiveForms() {
        assertAllStemEqual(List.of(
                "красный", "красная", "красное", "красные",
                "красного", "красному", "красным", "красных", "красной"));
    }

    @Test
    void stripsCommonVerbEndingsAfterStemVowel() {
        // "ть"/"л" after а/я are removed, the stem vowel is kept
        assertThat(RussianStemmer.stem("читать")).isEqualTo("чита");
        assertThat(RussianStemmer.stem("читал")).isEqualTo("чита");
    }

    @Test
    void leavesNonCyrillicTokensUntouched() {
        assertThat(RussianStemmer.stem("pgvector")).isEqualTo("pgvector");
        assertThat(RussianStemmer.stem("OpenAI")).isEqualTo("openai");
    }

    @Test
    void doesNotOverStripShortWords() {
        // ending must start at/after the RV region, so 1-2 letter words survive
        assertThat(RussianStemmer.stem("я")).isEqualTo("я");
        assertThat(RussianStemmer.stem("и")).isEqualTo("и");
    }

    @Test
    void handlesNullAndEmpty() {
        assertThat(RussianStemmer.stem(null)).isEqualTo("");
        assertThat(RussianStemmer.stem("")).isEqualTo("");
    }

    private void assertAllStemEqual(List<String> forms) {
        String expected = RussianStemmer.stem(forms.getFirst());
        assertThat(expected).isNotBlank();
        for (String form : forms) {
            assertThat(RussianStemmer.stem(form)).as("stem of '%s'", form).isEqualTo(expected);
        }
    }
}
