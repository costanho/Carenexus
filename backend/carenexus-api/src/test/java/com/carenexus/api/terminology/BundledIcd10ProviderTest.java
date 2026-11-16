package com.carenexus.api.terminology;

import com.carenexus.api.terminology.provider.BundledIcd10Provider;
import com.carenexus.api.terminology.provider.CodeValidation;
import com.carenexus.api.terminology.provider.IcdCodeFormat;
import com.carenexus.api.terminology.provider.TerminologyCode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bundled ICD-10 catalogue: guaranteed entries, format check, ranking and the file-format check.
 */
class BundledIcd10ProviderTest {

    private static BundledIcd10Provider provider;

    @BeforeAll
    static void load() {
        provider = BundledIcd10Provider.fromClasspath(BundledIcd10Provider.DEFAULT_RESOURCE);
    }

    @Test
    void shippedCatalogueContainsTheGuaranteedEntries() {
        Map<String, String> guaranteed = Map.of(
                "I10", "Essential (primary) hypertension",
                "E11.9", "Type 2 diabetes mellitus without complications",
                "J06.9", "Acute upper respiratory infection, unspecified",
                "J02.9", "Acute pharyngitis, unspecified",
                "R07.9", "Chest pain, unspecified",
                "R00.2", "Palpitations");
        guaranteed.forEach((code, description) -> {
            assertThat(provider.describe(code)).contains(description);
            assertThat(provider.validate(code)).isEqualTo(CodeValidation.VERIFIED);
        });
        assertThat(provider.size()).isGreaterThan(100);
        assertThat(provider.providerName()).isEqualTo("bundled");
    }

    @Test
    void validateNormalisesAndSeparatesUnverifiedFromInvalid() {
        assertThat(provider.validate(" j06.9 ")).isEqualTo(CodeValidation.VERIFIED);
        assertThat(provider.validate("R05.3")).isEqualTo(CodeValidation.UNVERIFIED);
        assertThat(provider.validate("Z99.89")).isEqualTo(CodeValidation.UNVERIFIED);
        assertThat(provider.validate("S52.501A")).isEqualTo(CodeValidation.VERIFIED);
        for (String bad : new String[]{null, "", "  ", "J06.", "J069X.1", "1J0.6", "J06.12345", "JJ6.9", "J06-9", "hello"}) {
            assertThat(provider.validate(bad)).as(String.valueOf(bad)).isEqualTo(CodeValidation.INVALID);
        }
        assertThat(provider.describe("R05.3")).isEmpty();
        assertThat(provider.describe(null)).isEmpty();
    }

    @Test
    void codePrefixIgnoresTheDotAndCase() {
        for (String query : new String[]{"J06", "J069", "j06.9", " j06.9 "}) {
            assertThat(provider.search(query, 10)).as(query)
                    .extracting(TerminologyCode::code).first().isEqualTo("J06.9");
        }
    }

    @Test
    void descriptionMatchesNeedEveryWordAndRankAfterCodeMatches() {
        assertThat(provider.search("pharyng", 10)).extracting(TerminologyCode::code)
                .containsExactly("J02.0", "J02.9", "J31.2");
        assertThat(provider.search("acute   PHARYNGITIS", 10)).extracting(TerminologyCode::code)
                .containsExactly("J02.9");
        // "e11" is a code prefix (E11.*) and also occurs in no description: code matches only, by code.
        List<TerminologyCode> diabetes = provider.search("E11", 50);
        assertThat(diabetes).isNotEmpty().allSatisfy(c -> assertThat(c.code()).startsWith("E11"));
        assertThat(diabetes).extracting(TerminologyCode::code).isSorted();
        assertThat(provider.search("hypertension", 50)).extracting(TerminologyCode::code).contains("I10", "O13.9")
                .doesNotContain("I11.9");
        assertThat(provider.search("", 10)).isEmpty();
        assertThat(provider.search("zzzz nothing", 10)).isEmpty();
        assertThat(provider.search("R", 3)).hasSize(3);
    }

    @Test
    void parseRejectsBrokenCatalogues() {
        assertThatThrownBy(() -> BundledIcd10Provider.parse("t", new StringReader("J06.9 no tab\n")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("line 1");
        assertThatThrownBy(() -> BundledIcd10Provider.parse("t", new StringReader("# c\nBAD\tText\n")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("line 2");
        assertThatThrownBy(() -> BundledIcd10Provider.parse("t", new StringReader("I10\tA\ni10\tB\n")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> BundledIcd10Provider.parse("t", new StringReader("I10\t \n")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("description");
        assertThatThrownBy(() -> BundledIcd10Provider.parse("t", new StringReader("# only comments\n\n")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no codes");

        BundledIcd10Provider small = BundledIcd10Provider.parse("t", new StringReader("i10\tEssential\n\n# x\nA09\tGastro\n"));
        assertThat(small.size()).isEqualTo(2);
        assertThat(small.describe("I10")).contains("Essential");
    }

    @Test
    void formatHelpers() {
        assertThat(IcdCodeFormat.normalize(" e11.65 ")).isEqualTo("E11.65");
        assertThat(IcdCodeFormat.compact("j06.9")).isEqualTo("J069");
        assertThat(IcdCodeFormat.isWellFormed("F32.A")).isTrue();
        assertThat(IcdCodeFormat.isWellFormed("W19.XXXA")).isTrue();
    }
}
