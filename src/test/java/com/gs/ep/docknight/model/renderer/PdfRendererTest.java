package com.gs.ep.docknight.model.renderer;

import com.gs.ep.docknight.model.Document;
import com.gs.ep.docknight.model.Element;
import com.gs.ep.docknight.model.Length;
import com.gs.ep.docknight.model.PositionalElementList;
import com.gs.ep.docknight.model.attribute.*;
import com.gs.ep.docknight.model.element.Page;
import com.gs.ep.docknight.model.element.TextElement;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.eclipse.collections.impl.factory.Lists;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class PdfRendererTest {

    @Test
    void render_withSimpleProgrammaticDocument_shouldProducePdfWithText() throws Exception {
        // 1. Create a Document object programmatically
        Document document = new Document();
        Page page = new Page();
        document.add(page); // Assuming Document has an add method or similar for pages

        PositionalElementList pageContent = new PositionalElementList();
        page.addAttribute(new PositionalContent(pageContent));

        // Create a TextElement
        TextElement textElement = new TextElement();
        String originalText = "Hello from PdfRendererTest";
        textElement.addAttribute(new Text(originalText));
        textElement.addAttribute(new FontFamily("Helvetica"));
        textElement.addAttribute(new FontSize(new Length(12, Length.Unit.PT)));
        textElement.addAttribute(new Color(java.awt.Color.BLACK));
        textElement.addAttribute(new Left(new Length(100, Length.Unit.PT)));
        textElement.addAttribute(new Top(new Length(100, Length.Unit.PT))); // From top of page

        pageContent.add(textElement);

        // 2. Instantiate PdfRenderer
        // Using default constructor, which implies default font path "fonts/"
        PdfRenderer pdfRenderer = new PdfRenderer();

        // 3. Render the document
        byte[] pdfBytes = pdfRenderer.render(document);

        // 4. Validate the output PDF
        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 0);

        String outputStart = new String(pdfBytes, 0, Math.min(pdfBytes.length, 5));
        assertEquals("%PDF-", outputStart, "Output should start with %PDF-");

        try (PDDocument renderedDoc = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
            assertEquals(1, renderedDoc.getNumberOfPages(), "Rendered PDF should have one page.");
            PDFTextStripper textStripper = new PDFTextStripper();
            String renderedText = textStripper.getText(renderedDoc);

            assertTrue(renderedText.contains(originalText),
                    "Rendered PDF text should contain: '" + originalText + "'. Actual text: '" + renderedText + "'");
        }
    }

    @Test
    void render_documentWithCustomFont_shouldNotThrowErrorIfFontNotAvailable() throws Exception {
        Document document = new Document();
        Page page = new Page();
        document.add(page);

        PositionalElementList pageContent = new PositionalElementList();
        page.addAttribute(new PositionalContent(pageContent));

        TextElement textElement = new TextElement();
        textElement.addAttribute(new Text("Custom font test"));
        // This font likely does not exist in "fonts/" directory during test
        textElement.addAttribute(new FontFamily("NonExistentCustomFont"));
        textElement.addAttribute(new FontSize(new Length(12, Length.Unit.PT)));
        textElement.addAttribute(new Left(new Length(50, Length.Unit.PT)));
        textElement.addAttribute(new Top(new Length(50, Length.Unit.PT)));
        pageContent.add(textElement);

        // Test with a specific (likely non-existent for test) font path
        PdfRenderer pdfRenderer = new PdfRenderer("test-fonts-empty-dir/");

        byte[] pdfBytes = null;
        // Expect it to fall back to Helvetica and not throw an exception
        try {
             pdfBytes = pdfRenderer.render(document);
        } catch (Exception e) {
            fail("Rendering with a non-existent custom font (and fallback mechanism) should not throw an exception.", e);
        }

        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 0);

        // Verify text is still rendered (likely with Helvetica)
         try (PDDocument renderedDoc = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
            PDFTextStripper textStripper = new PDFTextStripper();
            String renderedText = textStripper.getText(renderedDoc);
            assertTrue(renderedText.contains("Custom font test"));
        }
    }
}
