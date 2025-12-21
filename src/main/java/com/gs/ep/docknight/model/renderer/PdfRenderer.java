package com.gs.ep.docknight.model.renderer;

import com.gs.ep.docknight.model.Element;
import com.gs.ep.docknight.model.Renderer;
import com.gs.ep.docknight.model.attribute.*;
import com.gs.ep.docknight.model.element.Document;
import com.gs.ep.docknight.model.element.HorizontalLine;
import com.gs.ep.docknight.model.element.Image;
import com.gs.ep.docknight.model.element.Page;
import com.gs.ep.docknight.model.element.TextElement;
import com.gs.ep.docknight.model.element.VerticalLine;
import com.gs.ep.docknight.model.attribute.FirstLineIndent;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.eclipse.collections.api.tuple.Pair;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Renders a DocModel Document back to a PDF file.
 */
public class PdfRenderer implements Renderer<byte[]> {

    private PDFont regularFont;
    private PDFont boldFont;
    private final String fontsDir;

    public PdfRenderer() {
        this("src/main/resources/fonts");
    }

    public PdfRenderer(String fontsDir) {
        this.fontsDir = fontsDir;
    }

    @Override
    public byte[] render(Document document) {
        try (PDDocument pdDocument = new PDDocument()) {
            loadFonts(pdDocument);
            for (Element element : document.getContainingElements(Page.class)) {
                renderPage(pdDocument, (Page) element);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            pdDocument.save(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to render PDF", e);
        }
    }

    private void loadFonts(PDDocument pdDocument) {
        try {
            File regFile = new File(fontsDir, "NotoSansSC-Regular.ttf");
            if (regFile.exists()) {
                this.regularFont = PDType0Font.load(pdDocument, regFile);
            } else {
                this.regularFont = PDType1Font.HELVETICA;
            }

            File boldFile = new File(fontsDir, "NotoSansSC-Bold.ttf");
            if (boldFile.exists()) {
                this.boldFont = PDType0Font.load(pdDocument, boldFile);
            } else {
                this.boldFont = this.regularFont;
            }
        } catch (IOException e) {
            this.regularFont = PDType1Font.HELVETICA;
            this.boldFont = PDType1Font.HELVETICA;
        }
    }

    private void renderPage(PDDocument pdDocument, Page page) throws IOException {
        double width = page.getAttribute(Width.class).getMagnitude();
        double height = page.getAttribute(Height.class).getMagnitude();

        PDPage pdPage = new PDPage(new PDRectangle((float) width, (float) height));
        pdDocument.addPage(pdPage);

        try (PDPageContentStream contentStream = new PDPageContentStream(pdDocument, pdPage)) {
            // 1. Draw Page Background Colors
            if (page.hasAttribute(PageColor.class)) {
                List<Pair<java.awt.Rectangle, Integer>> coloredAreas = page.getAttribute(PageColor.class).getValue();
                for (Pair<java.awt.Rectangle, Integer> area : coloredAreas) {
                    java.awt.Rectangle rect = area.getOne();
                    Color color = new Color(area.getTwo());
                    contentStream.setNonStrokingColor(color);
                    contentStream.addRect((float) rect.getX(), (float) (height - rect.getY() - rect.getHeight()),
                            (float) rect.getWidth(), (float) rect.getHeight());
                    contentStream.fill();
                }
            }

            // 2. Process elements
            if (page.hasAttribute(PositionalContent.class)) {
                for (Element element : page.getPositionalContent().getValue().getElements()) {
                    if (element instanceof TextElement) {
                        renderText(contentStream, (TextElement) element, height);
                    } else if (element instanceof Image) {
                        renderImage(pdDocument, contentStream, (Image) element, height);
                    } else if (element instanceof HorizontalLine) {
                        renderHorizontalLine(contentStream, (HorizontalLine) element, height);
                    } else if (element instanceof VerticalLine) {
                        renderVerticalLine(contentStream, (VerticalLine) element, height);
                    }
                }
            }
        }
    }

    private void renderText(PDPageContentStream contentStream, TextElement element, double pageHeight)
            throws IOException {
        String text = element.getAttribute(Text.class).getValue();
        if (text == null || text.trim().isEmpty())
            return;

        double left = element.getAttribute(Left.class).getMagnitude();
        double top = element.getAttribute(Top.class).getMagnitude();
        double elementWidth = element.getAttribute(Width.class).getMagnitude();
        double elementHeight = element.getAttribute(Height.class).getMagnitude();
        double fontSize = 10.0;
        if (element.hasAttribute(FontSize.class)) {
            fontSize = element.getAttribute(FontSize.class).getMagnitude();
        }

        // Style check
        boolean isBold = false;
        if (element.hasAttribute(TextStyles.class)) {
            List<String> styles = element.getAttribute(TextStyles.class).getValue();
            isBold = styles.contains(TextStyles.BOLD);
        }

        double firstLineIndent = 0.0;
        if (element.hasAttribute(FirstLineIndent.class)) {
            firstLineIndent = element.getAttribute(FirstLineIndent.class).getMagnitude();
        }

        // Font selection
        PDFont font = isBold ? boldFont : regularFont;

        // Color selection
        if (element.hasAttribute(com.gs.ep.docknight.model.attribute.Color.class)) {
            java.awt.Color awtColor = element.getAttribute(com.gs.ep.docknight.model.attribute.Color.class).getValue();
            contentStream.setNonStrokingColor(awtColor);
        } else {
            contentStream.setNonStrokingColor(java.awt.Color.BLACK);
        }

        // Split text into lines (respecting existing newlines and first line indent)
        List<String> lines;
        if (firstLineIndent > 0) {
            lines = wrapText(text, font, (float) fontSize, (float) (elementWidth - firstLineIndent),
                    (float) elementWidth);
        } else {
            lines = wrapText(text, font, (float) fontSize, (float) elementWidth, (float) elementWidth);
        }

        float lineHeightFactor = 1.4f; // More natural for CJK and fills space better
        float lineHeight = (float) fontSize * lineHeightFactor;
        float totalTextHeight = lines.size() * lineHeight;

        // TOC Heuristic: If text contains leader dots "....", it might wrap due to
        // extra dots.
        // Instead of shrinking font, we should remove dots until it fits.
        // Only do this if it wraps (lines.size() > 1) and we have dot sequences.
        if (lines.size() > 1 && text.contains("....")) {
            String tempText = text;
            int dotIndex = tempText.lastIndexOf("....");
            while (lines.size() > 1 && dotIndex != -1 && tempText.length() > 10) {
                // Remove 5 dots at a time
                tempText = tempText.replaceFirst("\\.{5}", "."); // Replace 5 dots with 1 (net -4)
                if (tempText.length() == text.length()) {
                    // Try replacing 4 dots with empty if 5 didn't match
                    tempText = tempText.replaceFirst("\\.{4}", "");
                }

                // Re-wrap to check
                if (firstLineIndent > 0) {
                    lines = wrapText(tempText, font, (float) fontSize, (float) (elementWidth - firstLineIndent),
                            (float) elementWidth);
                } else {
                    lines = wrapText(tempText, font, (float) fontSize, (float) elementWidth, (float) elementWidth);
                }

                dotIndex = tempText.lastIndexOf("....");
                text = tempText; // Update main text so we render the truncated version
            }
            totalTextHeight = lines.size() * lineHeight;
        }

        // Scale down if text is too tall for its box, but with a floor
        if (totalTextHeight > elementHeight && elementHeight > 0) {
            float scale = (float) (elementHeight / totalTextHeight);
            float minFontSize = 7.0f; // Minimum readable font size

            if (fontSize * scale < minFontSize) {
                fontSize = minFontSize;
            } else {
                fontSize = fontSize * scale;
            }
            lineHeight = (float) fontSize * lineHeightFactor;
            // CRITICAL: Re-wrap text after scaling font to fill the width properly!
            if (firstLineIndent > 0) {
                // Scale indent proportionally if needed, or keep fixed. Keeping fixed usually
                // safer.
                lines = wrapText(text, font, (float) fontSize, (float) (elementWidth - firstLineIndent),
                        (float) elementWidth);
            } else {
                lines = wrapText(text, font, (float) fontSize, (float) elementWidth, (float) elementWidth);
            }
            totalTextHeight = lines.size() * lineHeight;
        }

        // Vertically center text in the box if there's extra space
        float verticalOffset = 0;
        if (elementHeight > totalTextHeight) {
            verticalOffset = (float) ((elementHeight - totalTextHeight) / 2.0);
        }

        float startY = (float) (pageHeight - top - verticalOffset - (fontSize * 0.8));

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            float lineWidth = 0;
            try {
                lineWidth = font.getStringWidth(line) / 1000 * (float) fontSize;
            } catch (Exception e) {
                lineWidth = (float) elementWidth;
            }

            float x = (float) left;
            float y = startY - (i * lineHeight);

            // Apply First Line Indent offset for the first line
            if (i == 0 && firstLineIndent > 0) {
                x += firstLineIndent;
            }

            if (element.hasAttribute(TextAlign.class)) {
                String align = element.getAttribute(TextAlign.class).getValue();
                if (TextAlign.CENTRE.equals(align)) {
                    x += (elementWidth - lineWidth) / 2;
                } else if (TextAlign.RIGHT.equals(align)) {
                    x += (elementWidth - lineWidth);
                }
            }

            contentStream.beginText();
            contentStream.setFont(font, (float) fontSize);
            contentStream.newLineAtOffset(x, y);
            try {
                contentStream.showText(line);
            } catch (Exception e) {
                // If character encoding fails, skip or use fallback (skipped for now)
            }
            contentStream.endText();
        }
    }

    private List<String> wrapText(String text, PDFont font, float fontSize, float maxWidthFirstLine,
            float maxWidthOtherLines) throws IOException {
        List<String> lines = new java.util.ArrayList<>();
        String[] blocks = text.split("\n");
        for (String block : blocks) {
            if (block.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder currentLine = new StringBuilder();
            float currentWidth = 0;
            float maxWidth = (lines.isEmpty() && currentLine.length() == 0) ? maxWidthFirstLine : maxWidthOtherLines;

            for (int i = 0; i < block.length(); i++) {
                char c = block.charAt(i);
                String segment = String.valueOf(c);
                float charWidth = 0;
                try {
                    charWidth = font.getStringWidth(segment) / 1000 * fontSize;
                } catch (Exception e) {
                    charWidth = fontSize * 0.5f;
                }

                // Update maxWidth based on whether we are on a new line that isn't the
                // text-block's very first line logic?
                // Actually simple logic: if we just added a line to 'lines', all subsequent
                // lines use maxWidthOtherLines.
                // But specifically inside this loop we are building ONE logical paragraph
                // (block).
                // The first line of this BLOCK uses maxWidthFirstLine ONLY if it's the very
                // first line of the function call?
                // No, standard hanging indent applies to the paragraph. Assuming 'text' passed
                // here is a paragraph.

                // Correction: If 'wrapText' is called with a full text, and we split by \n,
                // each \n is a hard break.
                // Usually hanging indent applies to the *visual* lines of a single paragraph.
                // If the text contains multiple paragraphs (hard newlines), we might need to
                // reset.
                // However, our translator usually sends one paragraph per element.
                // Let's assume 'text' is one paragraph. If it has \n, treat them as hard
                // breaks,
                // but strictly speaking, hanging indent usually resets after a hard break.
                // Let's just assume simple wrapping: "Lines" list index determines indentation.
                // Index 0 -> First Line Width. Index > 0 -> Other Line Width.

                // Wait, the inner loop builds lines.
                // We need to check if we are *starting* a new line.

                if (currentLine.length() > 0 && maxWidth > 10 && currentWidth + charWidth > maxWidth) {
                    lines.add(currentLine.toString().trim());
                    currentLine = new StringBuilder();
                    currentWidth = 0;
                    maxWidth = maxWidthOtherLines; // Subsequent lines in this block
                }
                currentLine.append(c);
                currentWidth += charWidth;
            }
            if (currentLine.length() > 0) {
                lines.add(currentLine.toString().trim());
            }
            // After a hard newline (end of block), typically the *next* block starts as a
            // new paragraph.
            // If we want to support multiple paragraphs in one element with hanging indent,
            // logic should probably reset to maxWidthFirstLine for the next block?
            // BUT, usually we only use hanging indent for single "List Item" paragraphs.
            // Let's stick to the current logic which effectively treats the whole text as
            // one flow
            // EXCEPT that I need to ensure lines added preserve the logic.
        }
        return lines;
    }

    // Helper for backward compatibility or simpler calls if needed, though not
    // strictly required if we update all calls.
    // But modifying the signature directly is fine since this is private.
    private List<String> wrapText(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
        return wrapText(text, font, fontSize, maxWidth, maxWidth);
    }

    private void renderImage(PDDocument pdDocument, PDPageContentStream contentStream, Image element, double pageHeight)
            throws IOException {
        if (!element.hasAttribute(ImageData.class))
            return;

        com.gs.ep.docknight.model.ComparableBufferedImage cbi = element.getAttribute(ImageData.class).getValue();
        if (cbi == null || cbi.getBufferedImage() == null)
            return;

        PDImageXObject imageXObject = LosslessFactory.createFromImage(pdDocument, cbi.getBufferedImage());

        double left = element.getAttribute(Left.class).getMagnitude();
        double top = element.getAttribute(Top.class).getMagnitude();
        double width = element.getAttribute(Width.class).getMagnitude();
        double height = element.getAttribute(Height.class).getMagnitude();

        contentStream.drawImage(imageXObject, (float) left, (float) (pageHeight - top - height), (float) width,
                (float) height);
    }

    private void renderHorizontalLine(PDPageContentStream contentStream, HorizontalLine element, double pageHeight)
            throws IOException {
        double top = element.getAttribute(Top.class).getMagnitude();
        double left = element.getAttribute(Left.class).getMagnitude();
        double stretch = element.getAttribute(Stretch.class).getMagnitude();

        contentStream.setLineWidth(1.0f);
        contentStream.setStrokingColor(java.awt.Color.BLACK);
        contentStream.moveTo((float) left, (float) (pageHeight - top));
        contentStream.lineTo((float) (left + stretch), (float) (pageHeight - top));
        contentStream.stroke();
    }

    private void renderVerticalLine(PDPageContentStream contentStream, VerticalLine element, double pageHeight)
            throws IOException {
        double top = element.getAttribute(Top.class).getMagnitude();
        double left = element.getAttribute(Left.class).getMagnitude();
        double stretch = element.getAttribute(Stretch.class).getMagnitude();

        contentStream.setLineWidth(1.0f);
        contentStream.setStrokingColor(java.awt.Color.BLACK);
        contentStream.moveTo((float) left, (float) (pageHeight - top));
        contentStream.lineTo((float) left, (float) (pageHeight - (top + stretch)));
        contentStream.stroke();
    }
}
