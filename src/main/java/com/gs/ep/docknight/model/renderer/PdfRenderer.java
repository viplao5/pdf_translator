package com.gs.ep.docknight.model.renderer;

import com.gs.ep.docknight.model.Attribute;
import com.gs.ep.docknight.model.AttributeVisitor;
import com.gs.ep.docknight.model.Document;
import com.gs.ep.docknight.model.Element;
import com.gs.ep.docknight.model.ElementVisitor;
import com.gs.ep.docknight.model.Length;
import com.gs.ep.docknight.model.TabularCellElementGroup; // Corrected
import com.gs.ep.docknight.model.TabularElementGroup;   // Corrected
import com.gs.ep.docknight.model.attribute.BackGroundColor;
import com.gs.ep.docknight.model.attribute.BorderColor;
import com.gs.ep.docknight.model.attribute.BorderStyle;
import com.gs.ep.docknight.model.attribute.Color;
import com.gs.ep.docknight.model.attribute.FontFamily;
import com.gs.ep.docknight.model.attribute.FontSize;
import com.gs.ep.docknight.model.attribute.Height;
import com.gs.ep.docknight.model.attribute.ImageData;
import com.gs.ep.docknight.model.attribute.Left;
import com.gs.ep.docknight.model.attribute.PageColor;
import com.gs.ep.docknight.model.attribute.PageMargin; // Corrected
import com.gs.ep.docknight.model.attribute.PageSize;
import com.gs.ep.docknight.model.attribute.PositionalContent;
import com.gs.ep.docknight.model.attribute.Text;
import com.gs.ep.docknight.model.attribute.TextStyles;
import com.gs.ep.docknight.model.attribute.Top;
import com.gs.ep.docknight.model.attribute.Url;
import com.gs.ep.docknight.model.attribute.Width;
import com.gs.ep.docknight.model.element.ElementGroup;
// import com.gs.ep.docknight.model.element.Group; // Removing incorrect Group import
import com.gs.ep.docknight.model.element.HorizontalLine;
import com.gs.ep.docknight.model.element.Image;
import com.gs.ep.docknight.model.element.Page;
import com.gs.ep.docknight.model.element.Rectangle;
import com.gs.ep.docknight.model.element.TextElement;
import com.gs.ep.docknight.model.element.VerticalLine;
// Removed: import com.gs.ep.docknight.model.attribute.Right;
// Removed: import com.gs.ep.docknight.model.element.Table;
// Removed: import com.gs.ep.docknight.model.element.TableCell;
// Removed: import com.gs.ep.docknight.model.element.TableRow;
// Removed: import com.gs.ep.docknight.model.style.BorderStyle.Style; (enum that doesn't exist)

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class PdfRenderer implements Renderer<byte[]>, ElementVisitor<PDPageContentStream>, AttributeVisitor<PDPageContentStream> {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfRenderer.class);

    private PDDocument pdDocument;
    private PDPage currentPage;
    private PDPageContentStream currentContentStream;
    private float currentPageHeight;

    private PDFont currentFont;
    private float currentFontSize;
    private float currentX;
    private float currentY;
    private java.awt.Color currentColor;

    private PDImageXObject currentImageObject;
    private float imageX, imageY, imageWidth, imageHeight;

    private float rectX, rectY, rectWidth, rectHeight;
    private java.awt.Color rectBgColor;
    private java.awt.Color rectBorderColor;
    private String rectBorderStyleVal; // Changed to String
    private float rectBorderWidth;

    // Line state variables removed as attributes are fetched directly in element handlers

    private float currentTableX, currentTableY;
    private float currentCellX, currentCellY, currentCellWidth, currentCellHeight;
    private java.awt.Color currentCellBgColor;
    private java.awt.Color currentCellBorderColor;
    private String currentCellBorderStyleVal; // Changed to String
    private float currentCellBorderWidth;

    private Map<String, PDFont> loadedFonts = new HashMap<>();
    private String customFontDir;


    public PdfRenderer() {
        this("fonts/");
    }

    public PdfRenderer(String customFontDirPath) {
        this.customFontDir = (customFontDirPath == null || customFontDirPath.trim().isEmpty()) ? "fonts/" : customFontDirPath;
        if (!this.customFontDir.endsWith("/")) {
            this.customFontDir += "/";
        }
        LOGGER.info("PdfRenderer initialized. Custom font directory set to: {}", this.customFontDir);

        this.currentFont = PDType1Font.HELVETICA;
        this.currentFontSize = 12;
        this.currentColor = java.awt.Color.BLACK;
        this.currentX = 0;
        this.currentY = 0;
        this.imageX = 0;
        this.imageY = 0;
        this.imageWidth = 0;
        this.imageHeight = 0;
        this.currentImageObject = null;
        resetRectState();
        // resetLineState(); // Removed
        resetTableState();
        resetCellState();
    }

    private void resetTextState() {
        this.currentFont = PDType1Font.HELVETICA;
        this.currentFontSize = 12;
        this.currentColor = java.awt.Color.BLACK;
        this.currentX = 0;
        this.currentY = 0;
    }

    private void resetImageState() {
        this.currentImageObject = null;
        this.imageX = 0;
        this.imageY = 0;
        this.imageWidth = 50;
        this.imageHeight = 50;
    }

    private void resetRectState() {
        this.rectX = 0;
        this.rectY = 0;
        this.rectWidth = 0;
        this.rectHeight = 0;
        this.rectBgColor = null;
        this.rectBorderColor = null;
        this.rectBorderStyleVal = com.gs.ep.docknight.model.attribute.BorderStyle.SOLID; // Default to solid string
        this.rectBorderWidth = 1f;
    }

    // resetLineState() removed

    private void resetTableState() {
        this.currentTableX = 0;
        this.currentTableY = 0;
    }

    private void resetCellState() {
        this.currentCellX = 0;
        this.currentCellY = 0;
        this.currentCellWidth = 0;
        this.currentCellHeight = 0;
        this.currentCellBgColor = null;
        this.currentCellBorderColor = null;
        this.currentCellBorderStyleVal = com.gs.ep.docknight.model.attribute.BorderStyle.SOLID; // Default to solid string
        this.currentCellBorderWidth = 1f;
    }

    @Override
    public byte[] render(Document document) throws Exception {
        this.pdDocument = new PDDocument();
        try {
            document.accept(this, null);
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            this.pdDocument.save(byteArrayOutputStream);
            return byteArrayOutputStream.toByteArray();
        } finally {
            if (this.pdDocument != null) {
                this.pdDocument.close();
            }
        }
    }

    @Override
    public Class<PDPageContentStream> getElementVisitorDataClass() {
        return PDPageContentStream.class;
    }

    @Override
    public Class<PDPageContentStream> getAttributeVisitorDataClass() {
        return PDPageContentStream.class;
    }

    @Override
    public PDPageContentStream handleElement(Document document, PDPageContentStream parentStream) throws Exception {
        LOGGER.info("Handling Document element.");
        for (Element element : document.getContent().getElements()) {
            element.accept(this, null);
        }
        return parentStream;
    }

    @Override
    public PDPageContentStream handleElement(Page page, PDPageContentStream ignoredStream) throws Exception {
        LOGGER.info("Handling Page element.");
        PDRectangle pdPageSize = PDRectangle.A4;

        PageSize pageSizeAttribute = page.getAttribute(PageSize.class);
        if (pageSizeAttribute != null && pageSizeAttribute.getValue() != null) {
            Pair<Length, Length> pageSizePair = pageSizeAttribute.getValue();
            float widthInPt = (float) pageSizePair.getOne().getMagnitude();
            float heightInPt = (float) pageSizePair.getTwo().getMagnitude();
            if (widthInPt > 0 && heightInPt > 0) {
                pdPageSize = new PDRectangle(widthInPt, heightInPt);
            } else {
                LOGGER.warn("Invalid PageSize dimensions. Defaulting to A4.");
            }
        } else {
            LOGGER.info("No PageSize attribute. Defaulting to A4.");
        }

        this.currentPage = new PDPage(pdPageSize);
        this.pdDocument.addPage(this.currentPage);
        this.currentPageHeight = this.currentPage.getMediaBox().getHeight();

        try {
            this.currentContentStream = new PDPageContentStream(this.pdDocument, this.currentPage);
            PageColor pageColorAttribute = page.getAttribute(PageColor.class);
            if (pageColorAttribute != null && pageColorAttribute.getValue() != null) {
                ListIterable<Pair<java.awt.Rectangle, Integer>> colorRegions = pageColorAttribute.getValue();
                for (Pair<java.awt.Rectangle, Integer> regionPair : colorRegions) {
                    java.awt.Rectangle area = regionPair.getOne();
                    java.awt.Color awtColor = new java.awt.Color(regionPair.getTwo(), true);
                    this.currentContentStream.setNonStrokingColor(awtColor);
                    float pdfX = area.x;
                    float pdfY = this.currentPageHeight - area.y - area.height;
                    this.currentContentStream.addRect(pdfX, pdfY, area.width, area.height);
                    this.currentContentStream.fill();
                }
            }

            for (Attribute attribute : page.getAttributes()) {
                if (!(attribute instanceof PageSize) && !(attribute instanceof PageColor)) {
                    attribute.accept(this, this.currentContentStream);
                }
            }

            PositionalContent positionalContent = page.getAttribute(PositionalContent.class);
            if (positionalContent != null && positionalContent.getValue() != null) {
                 positionalContent.getValue().getElements().forEach(element -> {
                    try { element.accept(this, this.currentContentStream); } catch (Exception e) { LOGGER.error("Error processing element in page", e); }
                });
            } else if (page.getContent() != null && page.getContent().getValue() != null) {
                 page.getContent().getValue().getElements().forEach(element -> {
                    try { element.accept(this, this.currentContentStream); } catch (Exception e) { LOGGER.error("Error processing element in page (fallback)", e); }
                });
            }
            this.currentContentStream.close();
        } catch (Exception e) {
            LOGGER.error("Error handling Page element", e);
            throw new RuntimeException("Failed to handle page", e);
        }
        return null;
    }

    @Override
    public PDPageContentStream handleElement(ElementGroup<?> elementGroup, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling ElementGroup element."); // Corrected log message
        for (Element element : elementGroup.getElements()) {
            element.accept(this, stream);
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleElement(HorizontalLine hLine, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling HorizontalLine element.");
        Left leftAttr = hLine.getAttribute(Left.class);
        Top topAttr = hLine.getAttribute(Top.class);
        Width widthAttr = hLine.getAttribute(Width.class);
        Color colorAttr = hLine.getAttribute(Color.class);
        Height heightAttr = hLine.getAttribute(Height.class);

        float x = (leftAttr != null && leftAttr.getValue() != null) ? (float) leftAttr.getValue().getMagnitude() : 0f;
        float y = (topAttr != null && topAttr.getValue() != null) ? (float) topAttr.getValue().getMagnitude() : 0f;
        float width = (widthAttr != null && widthAttr.getValue() != null) ? (float) widthAttr.getValue().getMagnitude() : 0f;
        java.awt.Color strokeColor = (colorAttr != null && colorAttr.getValue() != null) ? colorAttr.getValue() : java.awt.Color.BLACK;
        float strokeWidth = 1f;
        if (heightAttr != null && heightAttr.getValue() != null && heightAttr.getValue().getMagnitude() > 0) {
            strokeWidth = (float) heightAttr.getValue().getMagnitude();
        }
        float pdfY = this.currentPageHeight - y;
        stream.setStrokingColor(strokeColor);
        stream.setLineWidth(strokeWidth);
        stream.moveTo(x, pdfY);
        stream.lineTo(x + width, pdfY);
        stream.stroke();
        return stream;
    }

    @Override
    public PDPageContentStream handleElement(Image image, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling Image element.");
        resetImageState();
        for (Attribute attribute : image.getAttributes()) {
            attribute.accept(this, stream);
        }
        if (this.currentImageObject != null) {
            try {
                float pdfY = this.currentPageHeight - this.imageY - this.imageHeight;
                stream.drawImage(this.currentImageObject, this.imageX, pdfY, this.imageWidth, this.imageHeight);
            } catch (IOException e) {
                LOGGER.error("Error drawing image", e); throw e;
            }
        } else {
            LOGGER.warn("No image data for Image element at x:{}, y:{}", this.imageX, this.imageY);
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleElement(Rectangle rectangle, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling Rectangle element.");
        resetRectState();
        for (Attribute attribute : rectangle.getAttributes()) {
            attribute.accept(this, stream);
        }
        float x = this.rectX;
        float y = this.currentPageHeight - this.rectY - this.rectHeight;
        if (this.rectBgColor != null) {
            stream.setNonStrokingColor(this.rectBgColor);
            stream.addRect(x, y, this.rectWidth, this.rectHeight);
            stream.fill();
        }
        if (this.rectBorderColor != null && this.rectBorderWidth > 0 &&
            this.rectBorderStyleVal != null && !com.gs.ep.docknight.model.attribute.BorderStyle.NONE.equalsIgnoreCase(this.rectBorderStyleVal)) { // "none" is a valid style string
            stream.setStrokingColor(this.rectBorderColor);
            stream.setLineWidth(this.rectBorderWidth);
            if (com.gs.ep.docknight.model.attribute.BorderStyle.DASHED.equalsIgnoreCase(this.rectBorderStyleVal)) {
                stream.setLineDashPattern(new float[]{3}, 0);
            } else if (com.gs.ep.docknight.model.attribute.BorderStyle.DOTTED.equalsIgnoreCase(this.rectBorderStyleVal)) {
                stream.setLineDashPattern(new float[]{1, 2}, 0);
            } else {
                stream.setLineDashPattern(new float[]{}, 0);
            }
            stream.addRect(x, y, this.rectWidth, this.rectHeight);
            stream.stroke();
            stream.setLineDashPattern(new float[]{}, 0);
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleElement(TabularElementGroup table, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling TabularElementGroup element.");
        resetTableState();
        for (Attribute attribute : table.getAttributes()) {
            attribute.accept(this, stream);
        }
        for (Element element : table.getElements()) {
            if (element instanceof ElementGroup) {
                ElementGroup<?> rowGroup = (ElementGroup<?>) element;
                for (Element cellElement : rowGroup.getElements()) {
                    if (cellElement instanceof TabularCellElementGroup) {
                        ((TabularCellElementGroup) cellElement).accept(this, stream);
                    } else {
                        LOGGER.warn("Unexpected element in row ElementGroup: {}", cellElement.getClass().getSimpleName());
                    }
                }
            } else if (element instanceof TabularCellElementGroup) {
                ((TabularCellElementGroup) element).accept(this, stream);
            } else {
                 LOGGER.warn("Unexpected element in TabularElementGroup: {}", element.getClass().getSimpleName());
                 element.accept(this, stream);
            }
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleElement(TabularCellElementGroup tableCell, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling TabularCellElementGroup element.");
        resetCellState();
        for (Attribute attribute : tableCell.getAttributes()) {
            attribute.accept(this, stream);
        }
        if (this.currentCellBgColor != null) {
            float pdfCellY = this.currentPageHeight - this.currentCellY - this.currentCellHeight;
            stream.setNonStrokingColor(this.currentCellBgColor);
            stream.addRect(this.currentCellX, pdfCellY, this.currentCellWidth, this.currentCellHeight);
            stream.fill();
        }
        if (this.currentCellBorderColor != null && this.currentCellBorderWidth > 0 &&
            this.currentCellBorderStyleVal != null && !com.gs.ep.docknight.model.attribute.BorderStyle.NONE.equalsIgnoreCase(this.currentCellBorderStyleVal)) {
            float pdfCellY = this.currentPageHeight - this.currentCellY - this.currentCellHeight;
            stream.setStrokingColor(this.currentCellBorderColor);
            stream.setLineWidth(this.currentCellBorderWidth);
             if (com.gs.ep.docknight.model.attribute.BorderStyle.DASHED.equalsIgnoreCase(this.currentCellBorderStyleVal)) {
                stream.setLineDashPattern(new float[]{3}, 0);
            } else if (com.gs.ep.docknight.model.attribute.BorderStyle.DOTTED.equalsIgnoreCase(this.currentCellBorderStyleVal)) {
                stream.setLineDashPattern(new float[]{1, 2}, 0);
            } else {
                stream.setLineDashPattern(new float[]{}, 0);
            }
            stream.addRect(this.currentCellX, pdfCellY, this.currentCellWidth, this.currentCellHeight);
            stream.stroke();
            stream.setLineDashPattern(new float[]{}, 0);
        }
        for (Element element : tableCell.getElements()) {
            element.accept(this, stream);
        }
        return stream;
    }

    // TableRow handler removed

    @Override
    public PDPageContentStream handleElement(TextElement textElement, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling TextElement.");
        resetTextState();
        for (Attribute attribute : textElement.getAttributes()) {
            attribute.accept(this, stream);
        }
        if (this.currentFont == null) {
            this.currentFont = PDType1Font.HELVETICA;
        }
        stream.setFont(this.currentFont, this.currentFontSize);
        if (this.currentColor != null) {
            stream.setNonStrokingColor(this.currentColor.getRed() / 255f,
                                       this.currentColor.getGreen() / 255f,
                                       this.currentColor.getBlue() / 255f);
        } else {
            stream.setNonStrokingColor(java.awt.Color.BLACK);
        }
        stream.beginText();
        float yInPdf = this.currentPageHeight - this.currentY - this.currentFontSize;
        stream.newLineAtOffset(this.currentX, yInPdf);
        if (textElement.getText() != null) {
            textElement.getText().accept(this, stream);
        } else {
            LOGGER.warn("TextElement has no Text attribute.");
        }
        stream.endText();
        return stream;
    }

    @Override
    public PDPageContentStream handleElement(VerticalLine vLine, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling VerticalLine element.");
        Left leftAttr = vLine.getAttribute(Left.class);
        Top topAttr = vLine.getAttribute(Top.class);
        Height heightAttr = vLine.getAttribute(Height.class);
        Color colorAttr = vLine.getAttribute(Color.class);
        Width widthAttr = vLine.getAttribute(Width.class);

        float x = (leftAttr != null && leftAttr.getValue() != null) ? (float) leftAttr.getValue().getMagnitude() : 0f;
        float y = (topAttr != null && topAttr.getValue() != null) ? (float) topAttr.getValue().getMagnitude() : 0f;
        float height = (heightAttr != null && heightAttr.getValue() != null) ? (float) heightAttr.getValue().getMagnitude() : 0f;
        java.awt.Color strokeColor = (colorAttr != null && colorAttr.getValue() != null) ? colorAttr.getValue() : java.awt.Color.BLACK;
        float strokeWidth = 1f;
        if (widthAttr != null && widthAttr.getValue() != null && widthAttr.getValue().getMagnitude() > 0) {
            strokeWidth = (float) widthAttr.getValue().getMagnitude();
        }
        float pdfYStart = this.currentPageHeight - y;
        float pdfYEnd = this.currentPageHeight - y - height;
        stream.setStrokingColor(strokeColor);
        stream.setLineWidth(strokeWidth);
        stream.moveTo(x, pdfYStart);
        stream.lineTo(x, pdfYEnd);
        stream.stroke();
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(BackGroundColor bgColor, PDPageContentStream stream) throws Exception {
        Element parent = bgColor.getParentElement();
        if (parent instanceof Rectangle) {
            this.rectBgColor = bgColor.getValue();
        } else if (parent instanceof TabularCellElementGroup) {
            this.currentCellBgColor = bgColor.getValue();
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(BorderColor borderColor, PDPageContentStream stream) throws Exception {
        Element parent = borderColor.getParentElement();
        java.awt.Color commonColor = null;
        if (borderColor.getValue() != null && borderColor.getValue().getCommon() != null) {
            commonColor = borderColor.getValue().getCommon();
        }
        if (commonColor == null) {
            return stream;
        }
        if (parent instanceof Rectangle) {
            this.rectBorderColor = commonColor;
        } else if (parent instanceof TabularCellElementGroup) {
            this.currentCellBorderColor = commonColor;
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(BorderStyle borderStyle, PDPageContentStream stream) throws Exception {
        Element parent = borderStyle.getParentElement();
        String commonStyleString = null;
        if (borderStyle.getValue() != null && borderStyle.getValue().getCommon() != null) {
            commonStyleString = borderStyle.getValue().getCommon(); // This is a String
        }

        if (commonStyleString == null || commonStyleString.trim().isEmpty()) {
            return stream;
        }

        if (parent instanceof Rectangle) {
            this.rectBorderStyleVal = commonStyleString;
        } else if (parent instanceof TabularCellElementGroup) {
            this.currentCellBorderStyleVal = commonStyleString;
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(Color color, PDPageContentStream stream) throws Exception {
        Element parent = color.getParentElement();
        java.awt.Color value = color.getValue();
        if (parent instanceof TextElement) {
            this.currentColor = value;
        } else if (parent instanceof Rectangle) {
            if (this.rectBorderColor == null) { this.rectBorderColor = value; }
        } else if (parent instanceof TabularCellElementGroup) {
            if (this.currentCellBorderColor == null) { this.currentCellBorderColor = value; }
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(FontFamily fontFamily, PDPageContentStream stream) throws Exception {
        String fontFamilyName = fontFamily.getValue();
        if (this.loadedFonts.containsKey(fontFamilyName)) {
            this.currentFont = this.loadedFonts.get(fontFamilyName); return stream;
        }
        String lowerFamilyName = fontFamilyName.toLowerCase();
        boolean isStandardFont = true;
        switch (lowerFamilyName) {
            case "helvetica": this.currentFont = PDType1Font.HELVETICA; break;
            case "helvetica-bold": this.currentFont = PDType1Font.HELVETICA_BOLD; break;
            case "helvetica-oblique": this.currentFont = PDType1Font.HELVETICA_OBLIQUE; break;
            case "helvetica-boldoblique": this.currentFont = PDType1Font.HELVETICA_BOLD_OBLIQUE; break;
            case "times-roman": case "times": this.currentFont = PDType1Font.TIMES_ROMAN; break;
            case "times-bold": this.currentFont = PDType1Font.TIMES_BOLD; break;
            case "times-italic": this.currentFont = PDType1Font.TIMES_ITALIC; break;
            case "times-bolditalic": this.currentFont = PDType1Font.TIMES_BOLD_ITALIC; break;
            case "courier": this.currentFont = PDType1Font.COURIER; break;
            case "courier-bold": this.currentFont = PDType1Font.COURIER_BOLD; break;
            case "courier-oblique": this.currentFont = PDType1Font.COURIER_OBLIQUE; break;
            case "courier-boldoblique": this.currentFont = PDType1Font.COURIER_BOLD_OBLIQUE; break;
            default: isStandardFont = false;
        }
        if (!isStandardFont) {
            File fontFile = new File(customFontDir, fontFamilyName + ".ttf");
            if (fontFile.exists()) {
                try {
                    this.currentFont = PDType0Font.load(this.pdDocument, fontFile);
                    this.loadedFonts.put(fontFamilyName, this.currentFont);
                } catch (IOException e) {
                    LOGGER.warn("Failed to load custom font {}: {}", fontFile.getAbsolutePath(), e.getMessage());
                    this.currentFont = PDType1Font.HELVETICA;
                }
            } else {
                LOGGER.warn("Custom font file not found: {}. Defaulting.", fontFile.getAbsolutePath());
                this.currentFont = PDType1Font.HELVETICA;
            }
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(FontSize fontSize, PDPageContentStream stream) throws Exception {
        this.currentFontSize = (float) fontSize.getValue().getMagnitude();
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(Height height, PDPageContentStream stream) throws Exception {
        Element parent = height.getParentElement();
        float h = (float) height.getValue().getMagnitude();
        if (parent instanceof Image) { this.imageHeight = h; }
        else if (parent instanceof Rectangle) { this.rectHeight = h; }
        else if (parent instanceof TabularCellElementGroup) { this.currentCellHeight = h; }
        else if (!(parent instanceof VerticalLine) && !(parent instanceof HorizontalLine)) {
            LOGGER.info("Height attribute not for Image, Rectangle, or TabularCellElementGroup. Ignoring.");
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(ImageData imageData, PDPageContentStream stream) throws Exception {
        com.gs.ep.docknight.model.ComparableBufferedImage comparableBufferedImage = imageData.getValue();
        if (comparableBufferedImage != null && comparableBufferedImage.getValue() != null) {
            try {
                this.currentImageObject = LosslessFactory.createFromImage(this.pdDocument, comparableBufferedImage.getValue());
            } catch (IOException e) {
                LOGGER.error("Failed to create PDImageXObject from ImageData", e); throw e;
            }
        } else {
            LOGGER.warn("ImageData attribute value is null.");
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(Left left, PDPageContentStream stream) throws Exception {
        Element parent = left.getParentElement();
        float l = (float) left.getValue().getMagnitude();
        if (parent instanceof TextElement) { this.currentX = l; }
        else if (parent instanceof Image) { this.imageX = l; }
        else if (parent instanceof Rectangle) { this.rectX = l; }
        else if (parent instanceof TabularCellElementGroup) { this.currentCellX = l; }
        else if (!(parent instanceof HorizontalLine) && !(parent instanceof VerticalLine)) {
            LOGGER.info("Left attribute not for Text, Image, Rectangle, or TabularCellElementGroup. Ignoring.");
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(PageColor pageColor, PDPageContentStream stream) throws Exception {
        LOGGER.info("PageColor attribute encountered. It's processed within Page.handleElement.");
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(PageMargin pageMargin, PDPageContentStream stream) throws Exception {
        LOGGER.info("Not yet implemented: handleAttribute(PageMargin)");
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(PageSize pageSize, PDPageContentStream stream) throws Exception {
        LOGGER.info("PageSize attribute encountered. It's processed within Page.handleElement.");
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(PositionalContent positionalContent, PDPageContentStream stream) throws Exception {
        if (positionalContent.getValue() != null) {
            for (Element element : positionalContent.getValue().getElements()) {
                element.accept(this, stream);
            }
        }
        return stream;
    }

    // Right attribute handler removed

    @Override
    public PDPageContentStream handleAttribute(Text textAttribute, PDPageContentStream stream) throws Exception {
        String textValue = textAttribute.getValue();
        if (textValue != null && !textValue.isEmpty()) {
            try { stream.showText(textValue); } catch (IOException e) { LOGGER.error("Error showing text", e); throw e; }
        } else {
            LOGGER.warn("Text attribute is null or empty.");
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(TextStyles textStyles, PDPageContentStream stream) throws Exception {
        boolean isBold = textStyles.getValue().contains(com.gs.ep.docknight.model.style.TextStyle.Bold);
        boolean isItalic = textStyles.getValue().contains(com.gs.ep.docknight.model.style.TextStyle.Italic);

        if (this.currentFont instanceof PDType1Font) {
            String baseFontName = this.currentFont.getName().split("-")[0];
            PDFont targetFont = this.currentFont;

            if (baseFontName.equalsIgnoreCase("Helvetica")) {
                if (isBold && isItalic) targetFont = PDType1Font.HELVETICA_BOLD_OBLIQUE;
                else if (isBold) targetFont = PDType1Font.HELVETICA_BOLD;
                else if (isItalic) targetFont = PDType1Font.HELVETICA_OBLIQUE;
                else targetFont = PDType1Font.HELVETICA;
            } else if (baseFontName.equalsIgnoreCase("Times") || baseFontName.equalsIgnoreCase("Times-Roman")) {
                if (isBold && isItalic) targetFont = PDType1Font.TIMES_BOLD_ITALIC;
                else if (isBold) targetFont = PDType1Font.TIMES_BOLD;
                else if (isItalic) targetFont = PDType1Font.TIMES_ITALIC;
                else targetFont = PDType1Font.TIMES_ROMAN;
            } else if (baseFontName.equalsIgnoreCase("Courier")) {
                if (isBold && isItalic) targetFont = PDType1Font.COURIER_BOLD_OBLIQUE;
                else if (isBold) targetFont = PDType1Font.COURIER_BOLD;
                else if (isItalic) targetFont = PDType1Font.COURIER_OBLIQUE;
                else targetFont = PDType1Font.COURIER;
            }
            if (targetFont != null && targetFont != this.currentFont) {
                 this.currentFont = targetFont;
            } else if ((isBold || isItalic) && targetFont == this.currentFont &&
                        !(this.currentFont.getName().toLowerCase().contains("bold") ||
                          this.currentFont.getName().toLowerCase().contains("italic") ||
                          this.currentFont.getName().toLowerCase().contains("oblique")) ){
                 LOGGER.warn("No specific bold/italic variant for standard font {}.", this.currentFont.getName());
            }
        } else if (this.currentFont instanceof PDType0Font) {
            if (isBold || isItalic) {
                LOGGER.warn("TextStyles (bold/italic) on custom font ({}). Ensure styled version is loaded via FontFamily.", this.currentFont.getName());
            }
        } else {
            if (isBold || isItalic) {
                LOGGER.warn("TextStyles on unknown font type: {}.", this.currentFont != null ? this.currentFont.getClass().getName() : "null");
            }
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(Top top, PDPageContentStream stream) throws Exception {
        Element parent = top.getParentElement();
        float t = (float) top.getValue().getMagnitude();
        if (parent instanceof TextElement) { this.currentY = t; }
        else if (parent instanceof Image) { this.imageY = t; }
        else if (parent instanceof Rectangle) { this.rectY = t; }
        else if (parent instanceof TabularCellElementGroup) { this.currentCellY = t; }
        else if (!(parent instanceof HorizontalLine) && !(parent instanceof VerticalLine)) {
            LOGGER.info("Top attribute not for Text, Image, Rectangle, or TabularCellElementGroup. Ignoring.");
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(Url url, PDPageContentStream stream) throws Exception {
        if (url.getParentElement() instanceof Image) {
            LOGGER.warn("Handling Url attribute for Image: {}. Remote image loading not implemented.", url.getValue());
        } else {
            LOGGER.info("Url attribute not for Image, ignoring: {}", url.getValue());
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(Width width, PDPageContentStream stream) throws Exception {
        Element parent = width.getParentElement();
        float w = (float) width.getValue().getMagnitude();
        if (parent instanceof Image) { this.imageWidth = w; }
        else if (parent instanceof Rectangle) { this.rectWidth = w; }
        else if (parent instanceof TabularCellElementGroup) { this.currentCellWidth = w; }
        else if (!(parent instanceof HorizontalLine) && !(parent instanceof VerticalLine)) {
            LOGGER.info("Width attribute not for Image, Rectangle, or TabularCellElementGroup. Ignoring.");
        }
        return stream;
    }
}
