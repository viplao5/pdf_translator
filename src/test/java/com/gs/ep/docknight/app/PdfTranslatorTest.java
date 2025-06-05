package com.gs.ep.docknight.app;

import com.gs.ep.docknight.model.translation.SiliconBasedTranslationService;
import com.gs.ep.docknight.model.translation.TranslationService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class PdfTranslatorTest {

    // Helper method to create a simple PDF with one line of text
    private byte[] createSimplePdfBytes(String text) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            try (PDPageContentStream contentStream = new PDPageContentStream(doc, page)) {
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 12);
                contentStream.newLineAtOffset(100, 700);
                contentStream.showText(text);
                contentStream.endText();
            }
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    @Test
    void translatePdf_shouldTranslateTextInPdf_andReturnTranslatedPdfBytes() throws Exception {
        String originalText = "Hello World";
        String sourceLang = "en";
        String targetLang = "fr";
        String expectedTranslatedTextFragment = originalText + "_translated_[" + targetLang + "]";

        // 1. Create a simple PDF for input
        byte[] inputPdfBytes = createSimplePdfBytes(originalText);
        InputStream testPdfInputStream = new ByteArrayInputStream(inputPdfBytes);

        // 2. Instantiate PdfTranslator with a mock/placeholder translation service
        // Using a dummy API key as SiliconBasedTranslationService now requires one.
        TranslationService translationService = new SiliconBasedTranslationService("dummy-api-key");
        // Using default font path "fonts/" for PdfTranslator
        PdfTranslator pdfTranslator = new PdfTranslator(translationService, "fonts/");

        // 3. Call translatePdf
        byte[] outputPdfBytes = pdfTranslator.translatePdf(testPdfInputStream, sourceLang, targetLang);

        // 4. Validate Output PDF
        assertNotNull(outputPdfBytes);
        assertTrue(outputPdfBytes.length > 0);

        // Check if it's a PDF (starts with %PDF)
        String outputStart = new String(outputPdfBytes, 0, Math.min(outputPdfBytes.length, 5));
        assertEquals("%PDF-", outputStart);

        // Parse the output PDF and extract text
        try (PDDocument translatedDoc = PDDocument.load(new ByteArrayInputStream(outputPdfBytes))) {
            assertEquals(1, translatedDoc.getNumberOfPages(), "Translated PDF should have one page.");
            PDFTextStripper textStripper = new PDFTextStripper();
            String translatedPdfText = textStripper.getText(translatedDoc);

            // Assert that the translated text is present
            assertTrue(translatedPdfText.contains(expectedTranslatedTextFragment),
                    "Translated PDF text should contain: '" + expectedTranslatedTextFragment + "'. Actual text: '" + translatedPdfText + "'");
        }
    }

    @Test
    void translatePdf_withNoText_shouldReturnSimilarPdf() throws Exception {
        String originalText = ""; // Or a PDF with shapes but no text elements
        byte[] inputPdfBytes = createSimplePdfBytes(originalText); // creates a PDF with an empty text line essentially
        InputStream testPdfInputStream = new ByteArrayInputStream(inputPdfBytes);

        TranslationService translationService = new SiliconBasedTranslationService("dummy-api-key");
        PdfTranslator pdfTranslator = new PdfTranslator(translationService, "fonts/");

        byte[] outputPdfBytes = pdfTranslator.translatePdf(testPdfInputStream, "en", "fr");

        assertNotNull(outputPdfBytes);
        assertTrue(outputPdfBytes.length > 0);

        try (PDDocument translatedDoc = PDDocument.load(new ByteArrayInputStream(outputPdfBytes))) {
            assertEquals(1, translatedDoc.getNumberOfPages());
            PDFTextStripper textStripper = new PDFTextStripper();
            String translatedPdfText = textStripper.getText(translatedDoc).trim();
            // Depending on how PdfParser creates TextElement for empty strings from PDFBox,
            // this might be empty or contain whitespace.
            // If TextElement is not created for empty strings, then no translation happens.
            // If it is, it might result in "_translated_[fr]".
            // Current behavior of SiliconBasedTranslationService: returns "" + "_translated_[" + targetLang + "]" if text is ""
            // However, PdfTranslator's loop `if (originalText != null && !originalText.trim().isEmpty())`
            // should prevent empty strings from being translated.
            assertTrue(translatedPdfText.isEmpty(), "PDF with no translatable text should result in effectively empty text content. Actual: '" + translatedPdfText + "'");
        }
    }
}
