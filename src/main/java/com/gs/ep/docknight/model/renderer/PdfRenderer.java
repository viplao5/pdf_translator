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
                    int colorValue = area.getTwo();
                    Color color = new Color(colorValue);

                    // Skip black or very dark colors (may cause unwanted black bars)
                    // Also skip very thin strips (width or height < 3) as they are likely border
                    // lines
                    int brightness = (color.getRed() + color.getGreen() + color.getBlue()) / 3;
                    boolean isBlackOrDark = brightness < 30;
                    boolean isThinStrip = rect.getWidth() < 3 || rect.getHeight() < 3;

                    if (isBlackOrDark || isThinStrip) {
                        continue; // Skip black/dark areas and thin strips
                    }

                    contentStream.setNonStrokingColor(color);
                    contentStream.addRect((float) rect.getX(), (float) (height - rect.getY() - rect.getHeight()),
                            (float) rect.getWidth(), (float) rect.getHeight());
                    contentStream.fill();
                }
            }

            // 2. Collect table boundaries to identify table-related lines
            // Store table regions as rectangles (left, top, right, bottom)
            java.util.List<double[]> tableRegions = new java.util.ArrayList<>();
            if (page.hasAttribute(PositionalContent.class)) {
                for (com.gs.ep.docknight.model.TabularElementGroup<Element> tableGroup : page.getPositionalContent()
                        .getValue().getTabularGroups()) {
                    // Calculate table bounding box using BOTH text boundaries AND element positions
                    // This ensures border lines are included even after translation changes text
                    // bounds
                    double minLeft = Double.MAX_VALUE;
                    double minTop = Double.MAX_VALUE;
                    double maxRight = Double.MIN_VALUE;
                    double maxBottom = Double.MIN_VALUE;

                    for (org.eclipse.collections.api.list.MutableList<com.gs.ep.docknight.model.TabularCellElementGroup<Element>> row : tableGroup
                            .getCells()) {
                        for (com.gs.ep.docknight.model.TabularCellElementGroup<Element> cell : row) {
                            if (!cell.getElements().isEmpty()) {
                                // Use text bounding box
                                com.gs.ep.docknight.model.RectangleProperties<Double> bbox = cell.getTextBoundingBox();
                                minLeft = Math.min(minLeft, bbox.getLeft());
                                minTop = Math.min(minTop, bbox.getTop());
                                maxRight = Math.max(maxRight, bbox.getRight());
                                maxBottom = Math.max(maxBottom, bbox.getBottom());

                                // Also check individual element positions (may have been modified during
                                // translation)
                                for (Element cellElement : cell.getElements()) {
                                    if (cellElement.hasAttribute(Left.class)) {
                                        double elemLeft = cellElement.getAttribute(Left.class).getMagnitude();
                                        minLeft = Math.min(minLeft, elemLeft);
                                        if (cellElement.hasAttribute(Width.class)) {
                                            double elemRight = elemLeft
                                                    + cellElement.getAttribute(Width.class).getMagnitude();
                                            maxRight = Math.max(maxRight, elemRight);
                                        }
                                    }
                                    if (cellElement.hasAttribute(Top.class)) {
                                        double elemTop = cellElement.getAttribute(Top.class).getMagnitude();
                                        minTop = Math.min(minTop, elemTop);
                                        if (cellElement.hasAttribute(Height.class)) {
                                            double elemBottom = elemTop
                                                    + cellElement.getAttribute(Height.class).getMagnitude();
                                            maxBottom = Math.max(maxBottom, elemBottom);
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (minLeft != Double.MAX_VALUE) {
                        // Add generous margin to table region to ensure all border lines are included
                        // Border lines may extend beyond text content area
                        double margin = 30.0;
                        tableRegions.add(new double[] {
                                minLeft - margin,
                                minTop - margin,
                                maxRight + margin,
                                maxBottom + margin
                        });
                    }
                }
            }

            // 3. Collect image/diagram regions for line preservation
            // This includes both Image elements and dense line clusters (vector graphics)
            java.util.List<double[]> imageRegions = new java.util.ArrayList<>();
            if (page.hasAttribute(PositionalContent.class)) {
                // Add regions around Image elements
                for (Element element : page.getPositionalContent().getValue().getElements()) {
                    if (element instanceof Image && element.hasAttribute(Left.class)
                            && element.hasAttribute(Top.class)) {
                        double imgLeft = element.getAttribute(Left.class).getMagnitude();
                        double imgTop = element.getAttribute(Top.class).getMagnitude();
                        double imgWidth = element.hasAttribute(Width.class)
                                ? element.getAttribute(Width.class).getMagnitude()
                                : 100;
                        double imgHeight = element.hasAttribute(Height.class)
                                ? element.getAttribute(Height.class).getMagnitude()
                                : 100;
                        double margin = 20.0;
                        imageRegions.add(new double[] {
                                imgLeft - margin, imgTop - margin,
                                imgLeft + imgWidth + margin, imgTop + imgHeight + margin
                        });
                    }
                }
            }

            // 4. Process elements
            if (page.hasAttribute(PositionalContent.class)) {
                for (Element element : page.getPositionalContent().getValue().getElements()) {
                    if (element instanceof TextElement) {
                        renderText(contentStream, (TextElement) element, height);
                    } else if (element instanceof Image) {
                        renderImage(pdDocument, contentStream, (Image) element, height);
                    } else if (element instanceof HorizontalLine) {
                        // Only render lines that are part of tables
                        if (isTableLine(element, tableRegions)) {
                            renderHorizontalLine(contentStream, (HorizontalLine) element, height);
                        }
                    } else if (element instanceof VerticalLine) {
                        // Only render lines that are part of tables
                        if (isTableLine(element, tableRegions)) {
                            renderVerticalLine(contentStream, (VerticalLine) element, height);
                        }
                    }
                }
            }
        }
    }

    /**
     * Determines if a line element is part of a table structure.
     * A line is considered a table line if it falls within or near a table region.
     * Uses relaxed boundary checking to ensure border lines at table edges are
     * included.
     */
    private boolean isTableLine(Element lineElement, java.util.List<double[]> tableRegions) {
        if (tableRegions.isEmpty()) {
            return false; // No tables on this page, so no table lines
        }

        double left = lineElement.getAttribute(Left.class).getMagnitude();
        double top = lineElement.getAttribute(Top.class).getMagnitude();

        // Extra tolerance for border lines that may be slightly outside the calculated
        // region
        double tolerance = 10.0;

        // For a line, we also need to check its extent
        double lineEnd;
        if (lineElement instanceof HorizontalLine) {
            double stretch = lineElement.getAttribute(Stretch.class).getMagnitude();
            lineEnd = left + stretch;
            // Check if the line is within any table region (with tolerance)
            for (double[] region : tableRegions) {
                double tableLeft = region[0] - tolerance;
                double tableTop = region[1] - tolerance;
                double tableRight = region[2] + tolerance;
                double tableBottom = region[3] + tolerance;

                // Check if line is within table's vertical range and overlaps horizontally
                if (top >= tableTop && top <= tableBottom &&
                        !(lineEnd < tableLeft || left > tableRight)) {
                    return true;
                }
            }
        } else if (lineElement instanceof VerticalLine) {
            double stretch = lineElement.getAttribute(Stretch.class).getMagnitude();
            lineEnd = top + stretch;
            // Check if the line is within any table region (with tolerance)
            for (double[] region : tableRegions) {
                double tableLeft = region[0] - tolerance;
                double tableTop = region[1] - tolerance;
                double tableRight = region[2] + tolerance;
                double tableBottom = region[3] + tolerance;

                // Check if line is within table's horizontal range and overlaps vertically
                if (left >= tableLeft && left <= tableRight &&
                        !(lineEnd < tableTop || top > tableBottom)) {
                    return true;
                }
            }
        }

        return false;
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

        // 注意：我们是在全新的空白页面上绘制，不需要白色背景覆盖
        // 原始英文文本不存在于输出页面中
        // 之前的"小黑条"问题是因为图形区域截图包含了文本，已通过给截图添加边距解决

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

        // Slight font size adjustment (make smaller by 1 as requested)
        // Ensure effective font size doesn't drop too low before shrinking logic
        fontSize = Math.max(6.0f, (float) fontSize - 1.0f);

        // Split text into lines (respecting existing newlines and first line indent)
        List<String> lines;
        if (firstLineIndent > 0) {
            lines = wrapText(text, font, (float) fontSize, (float) (elementWidth - firstLineIndent),
                    (float) elementWidth);
        } else {
            lines = wrapText(text, font, (float) fontSize, (float) elementWidth, (float) elementWidth);
        }

        float lineHeightFactor = 1.4f; // reduced from 1.4f as requested
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
            float minFontSize = 10.0f; // Minimum readable font size - reduced slightly

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

        java.awt.image.BufferedImage bufferedImage = cbi.getBufferedImage();
        PDImageXObject imageXObject = LosslessFactory.createFromImage(pdDocument, bufferedImage);

        double left = element.getAttribute(Left.class).getMagnitude();
        double top = element.getAttribute(Top.class).getMagnitude();
        double targetWidth = element.getAttribute(Width.class).getMagnitude();
        double targetHeight = element.getAttribute(Height.class).getMagnitude();

        // 获取图片原始尺寸
        int originalWidth = bufferedImage.getWidth();
        int originalHeight = bufferedImage.getHeight();

        // 计算宽高比，保持原始比例避免拉伸变形
        double originalAspectRatio = (double) originalWidth / originalHeight;
        double targetAspectRatio = targetWidth / targetHeight;

        double renderWidth, renderHeight;

        if (Math.abs(originalAspectRatio - targetAspectRatio) > 0.01) {
            // 宽高比不一致，需要调整以保持原始比例
            if (originalAspectRatio > targetAspectRatio) {
                // 图片更宽，以宽度为基准
                renderWidth = targetWidth;
                renderHeight = targetWidth / originalAspectRatio;
            } else {
                // 图片更高，以高度为基准
                renderHeight = targetHeight;
                renderWidth = targetHeight * originalAspectRatio;
            }
        } else {
            // 宽高比一致，直接使用目标尺寸
            renderWidth = targetWidth;
            renderHeight = targetHeight;
        }

        // 保持图片在原始位置渲染（左上角对齐），不做居中偏移
        // PDF坐标系Y轴从下往上，所以需要转换
        contentStream.drawImage(imageXObject,
                (float) left,
                (float) (pageHeight - top - renderHeight),
                (float) renderWidth,
                (float) renderHeight);
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
