package com.carenexus.api.delivery;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TemplateRenderer without a Spring context: the smoke template shipped by this slice, placeholder rules and the
 * fail-fast behaviour for unknown or malformed templates.
 */
class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    @Test
    void rendersSubjectAndBodyOfTheSmokeTemplate() {
        RenderedTemplate rendered = renderer.render("delivery-test",
                Map.of("name", "Ann", "link", "http://localhost:8081/auth/login"));

        assertEquals("CareNexus delivery test for Ann", rendered.subject());
        assertTrue(rendered.body().startsWith("Hello Ann,\n"), rendered.body());
        assertTrue(rendered.body().contains("\nhttp://localhost:8081/auth/login\n"));
        assertFalse(rendered.body().contains("Subject:"), "the subject line is not part of the body");
        assertFalse(rendered.body().contains("{{"), "no placeholder is left");
        assertTrue(rendered.body().endsWith("No action is needed.\n"), "the body is kept verbatim to its last line");
    }

    @Test
    void aMissingValueRendersEmpty() {
        RenderedTemplate withoutLink = renderer.render("delivery-test", Map.of("name", "Ann"));
        assertTrue(withoutLink.body().contains("installation.\n\n\n\nNo action is needed."), withoutLink.body());

        RenderedTemplate withoutModel = renderer.render("delivery-test", null);
        assertEquals("CareNexus delivery test for", withoutModel.subject());
        assertTrue(withoutModel.body().startsWith("Hello ,\n"));
    }

    @Test
    void valuesAreInsertedVerbatimAndNotExpandedAgain() {
        RenderedTemplate rendered = renderer.render("delivery-test",
                Map.of("name", "{{link}} $1 \\n", "link", "https://example.test/?a=1&b=$2"));

        assertTrue(rendered.body().startsWith("Hello {{link}} $1 \\n,\n"), rendered.body());
        assertTrue(rendered.body().contains("https://example.test/?a=1&b=$2"));
    }

    @Test
    void aValueNeverAddsALineToTheSubject() {
        RenderedTemplate rendered = renderer.render("delivery-test", Map.of("name", "Ann\r\nBcc: someone@example.com"));

        assertEquals("CareNexus delivery test for Ann Bcc: someone@example.com", rendered.subject());
    }

    @Test
    void anUnknownTemplateFailsFast() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> renderer.render("delivery-no-such-template", Map.of()));
        assertTrue(ex.getMessage().contains("delivery-no-such-template"));
        assertFalse(renderer.exists("delivery-no-such-template"));
        assertTrue(renderer.exists("delivery-test"));
    }

    @Test
    void aTemplateIdIsAFileNameNeverAPath() {
        for (String id : new String[]{null, "", " ", "../application", "messages/delivery-test", "delivery-test.txt",
                "Delivery-Test", "delivery_test", "a".repeat(81)}) {
            assertThrows(IllegalStateException.class, () -> renderer.render(id, Map.of()), String.valueOf(id));
            assertFalse(renderer.exists(id));
        }
    }

    @Test
    void parse_readsSubjectBlankLineAndBody() {
        TemplateRenderer.Template template = TemplateRenderer.parse("t", "Subject:  Hello {{ name }} \r\n\r\nLine one\r\nLine two\r\n");

        assertEquals("Hello {{ name }}", template.subject());
        assertEquals("Line one\nLine two\n", template.body());
    }

    @Test
    void parse_rejectsMalformedTemplates() {
        assertThrows(IllegalStateException.class, () -> TemplateRenderer.parse("t", ""));
        assertThrows(IllegalStateException.class, () -> TemplateRenderer.parse("t", "Hello\n\nBody\n"),
                "no Subject line");
        assertThrows(IllegalStateException.class, () -> TemplateRenderer.parse("t", "Subject:\n\nBody\n"),
                "empty subject");
        assertThrows(IllegalStateException.class, () -> TemplateRenderer.parse("t", "Subject: Hi\nBody\n"),
                "no blank line");
        assertThrows(IllegalStateException.class, () -> TemplateRenderer.parse("t", "Subject: Hi"),
                "subject only");
        assertThrows(IllegalStateException.class, () -> TemplateRenderer.parse("t", "Subject: Hi\n\n  \n"),
                "blank body");
    }

    @Test
    void renderedTemplate_toStringDoesNotPrintTheText() {
        RenderedTemplate rendered = renderer.render("delivery-test", Map.of("name", "Ann", "link", "http://secret-link"));
        assertFalse(rendered.toString().contains("secret-link"));
        assertFalse(rendered.toString().contains("Ann"));
    }
}
