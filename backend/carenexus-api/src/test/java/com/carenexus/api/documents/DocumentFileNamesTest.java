package com.carenexus.api.documents;

import com.carenexus.api.documents.service.DocumentFileNames;
import com.carenexus.api.storage.ScanResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The download name rules of the documents contract (section 2.1, "File name"). */
class DocumentFileNamesTest {

    @Test
    void keepsAPlainNameWithAMatchingExtension() {
        assertEquals("fbc-2026-11.pdf", DocumentFileNames.sanitize("fbc-2026-11.pdf", ScanResult.PDF));
        assertEquals("Card Front.JPEG", DocumentFileNames.sanitize("Card Front.JPEG", ScanResult.JPEG));
        assertEquals("scan.dicom", DocumentFileNames.sanitize("scan.dicom", ScanResult.DICOM));
    }

    @Test
    void dropsDirectoriesOfBothSeparatorStyles() {
        assertEquals("report.pdf", DocumentFileNames.sanitize("../../etc/report.pdf", ScanResult.PDF));
        assertEquals("report.pdf", DocumentFileNames.sanitize("C:\\Users\\me\\report.pdf", ScanResult.PDF));
    }

    @Test
    void appendsTheCanonicalExtensionOfTheDetectedType() {
        assertEquals("scan.exe.pdf", DocumentFileNames.sanitize("scan.exe", ScanResult.PDF));
        assertEquals("photo.png.jpg", DocumentFileNames.sanitize("photo.png", ScanResult.JPEG));
        assertEquals("xray.dcm", DocumentFileNames.sanitize("xray", ScanResult.DICOM));
        assertEquals("image.png", DocumentFileNames.sanitize("image", ScanResult.PNG));
    }

    @Test
    void removesControlAndReservedCharactersAndCollapsesWhiteSpace() {
        // Control characters (tab, newline, bell) are removed, not turned into spaces.
        assertEquals("ab c.pdf", DocumentFileNames.sanitize("a\t\"b<>:|?*\u0007 \n c.pdf", ScanResult.PDF));
        assertEquals("a b c.pdf", DocumentFileNames.sanitize("a   b \u00A0 c.pdf", ScanResult.PDF));
        assertEquals("my name.pdf", DocumentFileNames.sanitize("  ..my   name..  ", ScanResult.PDF));
    }

    @Test
    void fallsBackToDocumentWhenNothingIsLeft() {
        assertEquals("document.pdf", DocumentFileNames.sanitize(null, ScanResult.PDF));
        assertEquals("document.pdf", DocumentFileNames.sanitize("  ...  ", ScanResult.PDF));
        assertEquals("document.png", DocumentFileNames.sanitize("dir/", ScanResult.PNG));
    }

    @Test
    void shortensTheBaseSoTheWholeNameIsAtMost150Characters() {
        String longName = "x".repeat(400) + ".pdf";
        String result = DocumentFileNames.sanitize(longName, ScanResult.PDF);
        assertEquals(150, result.length());
        assertTrue(result.endsWith(".pdf"));

        String appended = DocumentFileNames.sanitize("y".repeat(300), ScanResult.JPEG);
        assertEquals(150, appended.length());
        assertTrue(appended.endsWith(".jpg"));

        // A surrogate pair at the cut is never split.
        String emoji = "\uD83D\uDE00".repeat(100) + ".png";
        String cut = DocumentFileNames.sanitize(emoji, ScanResult.PNG);
        assertTrue(cut.length() <= 150);
        assertTrue(cut.endsWith(".png"));
        String base = cut.substring(0, cut.length() - 4);
        assertEquals(0, base.length() % 2);
        assertTrue(Character.isLowSurrogate(base.charAt(base.length() - 1)));
    }

    @Test
    void refusesATypeOutsideTheAllowList() {
        assertThrows(IllegalArgumentException.class, () -> DocumentFileNames.sanitize("a.txt", "text/plain"));
    }
}
