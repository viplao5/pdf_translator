package com.gs.ep.docknight.model.renderer;

import com.gs.ep.docknight.model.Attribute;
import com.gs.ep.docknight.model.AttributeVisitor;
import com.gs.ep.docknight.model.Document;
import com.gs.ep.docknight.model.Element;
import com.gs.ep.docknight.model.ElementVisitor;
import com.gs.ep.docknight.model.attribute.BackGroundColor;
import com.gs.ep.docknight.model.attribute.BorderColor;
import com.gs.ep.docknight.model.attribute.BorderStyle;
import com.gs.ep.docknight.model.attribute.Color;
import com.gs.ep.docknight.model.attribute.Height;
import com.gs.ep.docknight.model.attribute.Left;
import com.gs.ep.docknight.model.attribute.LineColor;
import com.gs.ep.docknight.model.attribute.LineWidth;
import com.gs.ep.docknight.model.attribute.PageColor;
import com.gs.ep.docknight.model.attribute.PageMargins;
import com.gs.ep.docknight.model.attribute.PageSize;
import com.gs.ep.docknight.model.attribute.PositionalContent;
import com.gs.ep.docknight.model.Length;
import com.gs.ep.docknight.model.attribute.FontFamily;
import com.gs.ep.docknight.model.attribute.FontSize;
import com.gs.ep.docknight.model.attribute.ImageData;
import com.gs.ep.docknight.model.attribute.Right;
import com.gs.ep.docknight.model.attribute.Text; // Attribute for text content
import com.gs.ep.docknight.model.attribute.TextStyles;
import com.gs.ep.docknight.model.attribute.Url;
import com.gs.ep.docknight.model.attribute.Top;
import com.gs.ep.docknight.model.attribute.Width;
import com.gs.ep.docknight.model.element.Group;
import com.gs.ep.docknight.model.element.HorizontalLine;
import com.gs.ep.docknight.model.element.Image;
import com.gs.ep.docknight.model.element.Page;
import com.gs.ep.docknight.model.element.Rectangle;
import com.gs.ep.docknight.model.element.Table;
import com.gs.ep.docknight.model.element.TableCell;
import com.gs.ep.docknight.model.element.TableRow;
import com.gs.ep.docknight.model.element.TextElement; // The actual element for text
import com.gs.ep.docknight.model.element.VerticalLine;
import com.gs.ep.docknight.model.style.BorderStyle.Style;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.tuple.Pair;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
// import java.awt.Color; // Already imported via com.gs.ep.docknight.model.attribute.Color

public class PdfRenderer implements Renderer<byte[]>, ElementVisitor<PDPageContentStream>, AttributeVisitor<PDPageContentStream> {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfRenderer.class);

    private PDDocument pdDocument;
    private PDPage currentPage;
    private PDPageContentStream currentContentStream;
    private float currentPageHeight;

    // Member variables for text styling and positioning
    private PDFont currentFont;
    private float currentFontSize;
    private float currentX;
    private float currentY;
    private java.awt.Color currentColor;

    // Member variables for image handling
    private PDImageXObject currentImageObject;
    private float imageX, imageY, imageWidth, imageHeight;

    // Member variables for rectangle handling
    private float rectX, rectY, rectWidth, rectHeight;
    private java.awt.Color rectBgColor;
    private java.awt.Color rectBorderColor;
    private Style rectBorderStyleVal;
    private float rectBorderWidth; // Assuming a single width for now

    // Member variables for line handling (can be combined if careful)
    private float lineX, lineY, lineEndX, lineEndY; // For general line drawing coordinates
    private java.awt.Color currentStrokeColor; // General for lines and borders
    private float currentStrokeWidth;

    // Member variables for table/cell handling
    private float currentTableX, currentTableY; // Optional: for overall table positioning
    private float currentCellX, currentCellY, currentCellWidth, currentCellHeight;
    private java.awt.Color currentCellBgColor;
    private java.awt.Color currentCellBorderColor;
    private Style currentCellBorderStyleVal;
    private float currentCellBorderWidth;

    // Font handling
    private Map<String, PDFont> loadedFonts = new HashMap<>();
    private String customFontDir; // Made configurable


    public PdfRenderer() {
        this("fonts/"); // Default constructor calls the new one with default path
    }

    public PdfRenderer(String customFontDirPath) {
        this.customFontDir = (customFontDirPath == null || customFontDirPath.trim().isEmpty()) ? "fonts/" : customFontDirPath;
        if (!this.customFontDir.endsWith("/")) {
            this.customFontDir += "/";
        }
        LOGGER.info("PdfRenderer initialized. Custom font directory set to: {}", this.customFontDir);

        // Initialize default text properties
        this.currentFont = PDType1Font.HELVETICA;
        this.currentFontSize = 12;
        this.currentColor = java.awt.Color.BLACK;
        this.currentX = 0;
        this.currentY = 0;
        // Initialize default image properties (or do it in a resetImageState method)
        this.imageX = 0;
        this.imageY = 0;
        this.imageWidth = 0; // Or a default sensible size
        this.imageHeight = 0; // Or a default sensible size
        this.currentImageObject = null;
        resetRectState();
        resetLineState();
        resetTableState();
        resetCellState();
        // Ensure loadedFonts is initialized here if not done at declaration, but it is.
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
        this.imageWidth = 50; // Default width, adjust as needed
        this.imageHeight = 50; // Default height, adjust as needed
    }

    private void resetRectState() {
        this.rectX = 0;
        this.rectY = 0;
        this.rectWidth = 0;
        this.rectHeight = 0;
        this.rectBgColor = null;
        this.rectBorderColor = null;
        this.rectBorderStyleVal = Style.NONE;
        this.rectBorderWidth = 1f; // Default border width
    }

    private void resetLineState() {
        this.lineX = 0;
        this.lineY = 0;
        this.lineEndX = 0;
        this.lineEndY = 0;
        this.currentStrokeColor = java.awt.Color.BLACK; // Default line/border color
        this.currentStrokeWidth = 1f; // Default line/border width
    }

    private void resetTableState() {
        this.currentTableX = 0;
        this.currentTableY = 0;
        // Other table-wide defaults if any
    }

    private void resetCellState() {
        this.currentCellX = 0;
        this.currentCellY = 0;
        this.currentCellWidth = 0;
        this.currentCellHeight = 0;
        this.currentCellBgColor = null;
        this.currentCellBorderColor = null; // Default to no border or a table default
        this.currentCellBorderStyleVal = Style.NONE;
        this.currentCellBorderWidth = 0f; // Default to no border width or a table default
    }

    @Override
    public byte[] render(Document document) throws Exception {
        this.pdDocument = new PDDocument();
        try {
            document.accept(this, null); // Initial call, stream might be page-specific
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

    // ElementVisitor implementations (placeholders)
    @Override
    public PDPageContentStream handleElement(Document document, PDPageContentStream parentStream) throws Exception {
        LOGGER.info("Handling Document element.");
        // Implementation will be added in the next step
        for (Element element : document.getContent().getElements()) {
            element.accept(this, null); // Stream is created/managed per page
        }
        return parentStream;
    }

    @Override
    public PDPageContentStream handleElement(Page page, PDPageContentStream ignoredStream) throws Exception {
        LOGGER.info("Handling Page element.");
        PDRectangle pdPageSize = PDRectangle.A4; // Default

        PageSize pageSizeAttribute = page.getAttribute(PageSize.class);
        if (pageSizeAttribute != null && pageSizeAttribute.getValue() != null) {
            Pair<Length, Length> pageSizePair = pageSizeAttribute.getValue();
            float widthInPt = (float) pageSizePair.getOne().getMagnitude();
            float heightInPt = (float) pageSizePair.getTwo().getMagnitude();
            if (widthInPt > 0 && heightInPt > 0) {
                pdPageSize = new PDRectangle(widthInPt, heightInPt);
                LOGGER.info("Using PageSize from attribute: {} x {}", widthInPt, heightInPt);
            } else {
                LOGGER.warn("Invalid PageSize dimensions from attribute (width: {}, height: {}). Defaulting to A4.", widthInPt, heightInPt);
            }
        } else {
            LOGGER.info("No PageSize attribute found or value is null. Defaulting to A4.");
        }

        this.currentPage = new PDPage(pdPageSize);
        this.pdDocument.addPage(this.currentPage);
        this.currentPageHeight = this.currentPage.getMediaBox().getHeight();

        try {
            this.currentContentStream = new PDPageContentStream(this.pdDocument, this.currentPage);

            // Process PageColor attribute first (if present) to draw background
            PageColor pageColorAttribute = page.getAttribute(PageColor.class);
            if (pageColorAttribute != null && pageColorAttribute.getValue() != null) {
                LOGGER.info("Handling PageColor attribute for page background.");
                ListIterable<Pair<java.awt.Rectangle, Integer>> colorRegions = pageColorAttribute.getValue();
                for (Pair<java.awt.Rectangle, Integer> regionPair : colorRegions) {
                    java.awt.Rectangle area = regionPair.getOne();
                    java.awt.Color awtColor = new java.awt.Color(regionPair.getTwo(), true); // true for hasAlpha

                    this.currentContentStream.setNonStrokingColor(awtColor);
                    // Convert AWT rectangle to PDF coordinates (Y is inverted)
                    float pdfX = area.x;
                    float pdfY = this.currentPageHeight - area.y - area.height;
                    this.currentContentStream.addRect(pdfX, pdfY, area.width, area.height);
                    this.currentContentStream.fill();
                    LOGGER.debug("Filled page area {} with color {}", area, awtColor);
                }
            }

            // Process other attributes of the page (excluding PageSize and PageColor as they are handled)
            for (Attribute attribute : page.getAttributes()) {
                if (!(attribute instanceof PageSize) && !(attribute instanceof PageColor)) {
                    attribute.accept(this, this.currentContentStream);
                }
            }

            // Process content of the page
            PositionalContent positionalContent = page.getAttribute(PositionalContent.class);
            if (positionalContent != null && positionalContent.getValue() != null) {
                 positionalContent.getValue().getElements().forEach(element -> {
                    try {
                        element.accept(this, this.currentContentStream);
                    } catch (Exception e) {
                        LOGGER.error("Error processing element within page", e);
                    }
                });
            } else if (page.getContent() != null && page.getContent().getValue() != null) {
                // Fallback if PositionalContent is not the primary way content is held (original handling)
                 page.getContent().getValue().getElements().forEach(element -> {
                    try {
                        element.accept(this, this.currentContentStream);
                    } catch (Exception e) {
                        LOGGER.error("Error processing element within page (fallback content)", e);
                    }
                });
            }
            this.currentContentStream.close();
        } catch (Exception e) { // Catch broader exception due to attribute processing
            LOGGER.error("Error handling Page element or its attributes/content", e);
            throw new RuntimeException("Failed to handle page", e);
        }
        return null; // Stream is closed, nothing to return for chaining here
    }

    @Override
    public PDPageContentStream handleElement(Group group, PDPageContentStream stream) throws Exception {
        LOGGER.info("Not yet implemented: handleElement(Group)");
        for (Element element : group.getElements()) {
            element.accept(this, stream);
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleElement(HorizontalLine hLine, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling HorizontalLine element.");
        resetLineState(); // Resets lineX, lineY, lineEndX, lineEndY, currentStrokeColor, currentStrokeWidth

        // Process attributes to populate line properties
        for (Attribute attr : hLine.getAttributes()) {
            attr.accept(this, stream);
        }

        // Use populated values (lineX from Left, lineY from Top, lineEndX from Width + Left)
        // currentStrokeColor from Color attribute, currentStrokeWidth from LineWidth
        // Note: Left, Top, Width attributes need to correctly set these for HLine/VLine

        float y = this.currentPageHeight - this.lineY;

        stream.setStrokingColor(this.currentStrokeColor != null ? this.currentStrokeColor : java.awt.Color.BLACK);
        stream.setLineWidth(this.currentStrokeWidth > 0 ? this.currentStrokeWidth : 1f);
        stream.moveTo(this.lineX, y);
        stream.lineTo(this.lineEndX, y); // lineEndX should be lineX + width
        stream.stroke();
        LOGGER.info("Drew HorizontalLine from ({},{}) to ({},{})", this.lineX, y, this.lineEndX, y);
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
                // PDF Y is from bottom, Model Y is from Top.
                float pdfY = this.currentPageHeight - this.imageY - this.imageHeight;
                stream.drawImage(this.currentImageObject, this.imageX, pdfY, this.imageWidth, this.imageHeight);
                LOGGER.info("Drew image at x:{}, y:{}, w:{}, h:{}", this.imageX, pdfY, this.imageWidth, this.imageHeight);
            } catch (IOException e) {
                LOGGER.error("Error drawing image", e);
                throw e;
            }
        } else {
            LOGGER.warn("No image data (currentImageObject is null) for Image element at x:{}, y:{}. Skipping draw.", this.imageX, this.imageY);
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleElement(Rectangle rectangle, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling Rectangle element.");
        resetRectState(); // Reset for each new Rectangle

        for (Attribute attribute : rectangle.getAttributes()) {
            attribute.accept(this, stream);
        }

        float x = this.rectX;
        float y = this.currentPageHeight - this.rectY - this.rectHeight; // PDF Y is from bottom

        // Fill background
        if (this.rectBgColor != null) {
            stream.setNonStrokingColor(this.rectBgColor);
            stream.addRect(x, y, this.rectWidth, this.rectHeight);
            stream.fill();
            LOGGER.info("Filled Rectangle at ({},{}) w:{}, h:{} with color {}", x,y,this.rectWidth, this.rectHeight, this.rectBgColor);
        }

        // Stroke border
        if (this.rectBorderColor != null && this.rectBorderStyleVal != Style.NONE && this.rectBorderWidth > 0) {
            stream.setStrokingColor(this.rectBorderColor);
            stream.setLineWidth(this.rectBorderWidth);
            // TODO: Implement different border styles (dashed, dotted) if needed.
            // For now, all are treated as solid.
            if (this.rectBorderStyleVal == Style.DASHED) {
                 // Example: 3 units on, 3 units off
                stream.setLineDashPattern(new float[]{3, 3}, 0);
            } else if (this.rectBorderStyleVal == Style.DOTTED) {
                // Example: 1 unit on, 2 units off (approximates dots)
                stream.setLineDashPattern(new float[]{1, 2}, 0);
            } else {
                // Solid or other styles treated as solid
                stream.setLineDashPattern(new float[]{}, 0); // Reset to solid
            }
            stream.addRect(x, y, this.rectWidth, this.rectHeight);
            stream.stroke();
            stream.setLineDashPattern(new float[]{}, 0); // Reset dash pattern after use
            LOGGER.info("Stroked Rectangle border at ({},{}) w:{}, h:{} with color {}", x,y,this.rectWidth, this.rectHeight, this.rectBorderColor);
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleElement(Table table, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling Table element.");
        resetTableState();
        // Process table-level attributes if any (e.g., overall Left/Top for the table block)
        for (Attribute attribute : table.getAttributes()) {
            // Example: if (attribute instanceof Left) this.currentTableX = ...
            // For now, assuming cells have absolute positions or positions relative to an implicit table origin
            attribute.accept(this, stream);
        }

        LOGGER.debug("Processing {} rows for table.", table.getElements().size());
        for (TableRow row : table.getElements()) {
            row.accept(this, stream);
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleElement(TableCell tableCell, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling TableCell element.");
        resetCellState();

        // 1. Process attributes of the TableCell to determine its geometry and styling
        // (e.g., Left, Top, Width, Height, BackGroundColor, BorderColor, etc.)
        for (Attribute attribute : tableCell.getAttributes()) {
            attribute.accept(this, stream);
        }

        // 2. Draw cell background
        if (this.currentCellBgColor != null) {
            float pdfCellY = this.currentPageHeight - this.currentCellY - this.currentCellHeight;
            stream.setNonStrokingColor(this.currentCellBgColor);
            stream.addRect(this.currentCellX, pdfCellY, this.currentCellWidth, this.currentCellHeight);
            stream.fill();
            LOGGER.debug("Filled TableCell at ({},{}) w:{}, h:{} with color {}", this.currentCellX, pdfCellY, this.currentCellWidth, this.currentCellHeight, this.currentCellBgColor);
        }

        // 3. Draw cell borders
        // TODO: Implement more sophisticated border handling (e.g., collapsed borders)
        if (this.currentCellBorderColor != null && this.currentCellBorderStyleVal != Style.NONE && this.currentCellBorderWidth > 0) {
            float pdfCellY = this.currentPageHeight - this.currentCellY - this.currentCellHeight;
            stream.setStrokingColor(this.currentCellBorderColor);
            stream.setLineWidth(this.currentCellBorderWidth);

            if (this.currentCellBorderStyleVal == Style.DASHED) {
                stream.setLineDashPattern(new float[]{3, 3}, 0);
            } else if (this.currentCellBorderStyleVal == Style.DOTTED) {
                stream.setLineDashPattern(new float[]{1, 2}, 0);
            } else {
                stream.setLineDashPattern(new float[]{}, 0); // Solid
            }
            stream.addRect(this.currentCellX, pdfCellY, this.currentCellWidth, this.currentCellHeight);
            stream.stroke();
            stream.setLineDashPattern(new float[]{}, 0); // Reset dash pattern
            LOGGER.debug("Stroked TableCell border at ({},{}) w:{}, h:{}", this.currentCellX, pdfCellY, this.currentCellWidth, this.currentCellHeight);
        }

        // 4. Process the content of the cell
        // Assumes cell content elements (TextElement, Image, etc.) have their own
        // absolute positioning attributes or positioning relative to the page.
        LOGGER.debug("Processing {} elements inside TableCell.", tableCell.getElements().size());
        for (Element element : tableCell.getElements()) {
            element.accept(this, stream);
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleElement(TableRow tableRow, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling TableRow element.");
        // Process row-level attributes if any
        for (Attribute attribute : tableRow.getAttributes()) {
            attribute.accept(this, stream);
        }
        LOGGER.debug("Processing {} cells for table row.", tableRow.getElements().size());
        for (TableCell cell : tableRow.getElements()) {
            cell.accept(this, stream);
        }
        return stream;
    }

    // Renamed from handleElement(Text text, ...) to handleElement(TextElement textElement, ...)
    @Override
    public PDPageContentStream handleElement(TextElement textElement, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling TextElement.");
        resetTextState(); // Reset for each new TextElement

        // Process attributes to populate currentFont, currentFontSize, currentColor, currentX, currentY
        for (Attribute attribute : textElement.getAttributes()) {
            attribute.accept(this, stream);
        }

        // Apply gathered font and color
        if (this.currentFont == null) {
            LOGGER.warn("currentFont is null, defaulting to Helvetica.");
            this.currentFont = PDType1Font.HELVETICA; // Fallback
        }
        stream.setFont(this.currentFont, this.currentFontSize);
        if (this.currentColor != null) {
            stream.setNonStrokingColor(this.currentColor.getRed() / 255f,
                                       this.currentColor.getGreen() / 255f,
                                       this.currentColor.getBlue() / 255f);
        } else {
            stream.setNonStrokingColor(java.awt.Color.BLACK); // Fallback
        }

        stream.beginText();
        // PDF Y is from bottom, Model Y is from Top. baseline adjustment is also tricky.
        // For now, using a simple conversion. May need fine-tuning.
        float yInPdf = this.currentPageHeight - this.currentY - this.currentFontSize;
        stream.newLineAtOffset(this.currentX, yInPdf);

        // The Text attribute itself will call showText via its handler
        if (textElement.getText() != null) {
            textElement.getText().accept(this, stream);
        } else {
            LOGGER.warn("TextElement has no Text attribute to render.");
        }

        stream.endText();
        return stream;
    }

    @Override
    public PDPageContentStream handleElement(VerticalLine vLine, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling VerticalLine element.");
        resetLineState();

        for (Attribute attr : vLine.getAttributes()) {
            attr.accept(this, stream);
        }

        // Use populated values (lineX from Left, lineY from Top, lineEndY from Top + Height)
        // currentStrokeColor from Color attribute, currentStrokeWidth from LineWidth
        float x = this.lineX;
        float yStart = this.currentPageHeight - this.lineY;
        float yEnd = this.currentPageHeight - this.lineEndY; // lineEndY should be lineY + height

        stream.setStrokingColor(this.currentStrokeColor != null ? this.currentStrokeColor : java.awt.Color.BLACK);
        stream.setLineWidth(this.currentStrokeWidth > 0 ? this.currentStrokeWidth : 1f);
        stream.moveTo(x, yStart);
        stream.lineTo(x, yEnd);
        stream.stroke();
        LOGGER.info("Drew VerticalLine from ({},{}) to ({},{})", x, yStart, x, yEnd);
        return stream;
    }

    // AttributeVisitor implementations
    @Override
    public PDPageContentStream handleAttribute(BackGroundColor bgColor, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling BackGroundColor attribute for parent: {}", bgColor.getParentElement().getClass().getSimpleName());
        Element parent = bgColor.getParentElement();
        if (parent instanceof Rectangle) {
            this.rectBgColor = bgColor.getValue();
        } else if (parent instanceof TableCell) {
            this.currentCellBgColor = bgColor.getValue();
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(BorderColor borderColor, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling BorderColor attribute for parent: {}", borderColor.getParentElement().getClass().getSimpleName());
        Element parent = borderColor.getParentElement();
        java.awt.Color commonColor = null;
        if (borderColor.getValue() != null && borderColor.getValue().getCommon() != null) {
            commonColor = borderColor.getValue().getCommon();
        }

        if (commonColor == null) {
            LOGGER.warn("BorderColor attribute value or common color is null for parent {}", parent.getClass().getSimpleName());
            return stream;
        }

        if (parent instanceof Rectangle) {
            this.rectBorderColor = commonColor;
        } else if (parent instanceof TableCell) {
            this.currentCellBorderColor = commonColor;
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(BorderStyle borderStyle, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling BorderStyle attribute for parent: {}", borderStyle.getParentElement().getClass().getSimpleName());
        Element parent = borderStyle.getParentElement();

        com.gs.ep.docknight.model.style.BorderStyle commonStyle = null;
        if (borderStyle.getValue() != null && borderStyle.getValue().getCommon() != null) {
            commonStyle = borderStyle.getValue().getCommon();
        }

        if (commonStyle == null) {
            LOGGER.warn("BorderStyle attribute value or common style is null for parent {}", parent.getClass().getSimpleName());
            return stream;
        }

        if (parent instanceof Rectangle) {
            this.rectBorderStyleVal = commonStyle.getStyle();
            if (commonStyle.getWidth() != null) { // Assuming BorderStyle includes width
                 this.rectBorderWidth = (float) commonStyle.getWidth().getMagnitude();
            }
        } else if (parent instanceof TableCell) {
            this.currentCellBorderStyleVal = commonStyle.getStyle();
            if (commonStyle.getWidth() != null) { // Assuming BorderStyle includes width
                 this.currentCellBorderWidth = (float) commonStyle.getWidth().getMagnitude();
            }
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(Color color, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling Color attribute value: {}", color.getValue());
        Element parent = color.getParentElement();
        java.awt.Color value = color.getValue();
        if (parent instanceof TextElement) {
            this.currentColor = value;
        } else if (parent instanceof HorizontalLine || parent instanceof VerticalLine) {
            this.currentStrokeColor = value;
        } else if (parent instanceof TableCell) { // For cell border color if not covered by BorderColor
            if (this.currentCellBorderColor == null) { // Prioritize BorderColor if present
                 this.currentCellBorderColor = value;
                 LOGGER.debug("Using generic Color attribute for TableCell border color.");
            }
        } else {
            LOGGER.info("Color attribute not for TextElement, Line, or TableCell border. Ignoring.");
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(FontFamily fontFamily, PDPageContentStream stream) throws Exception {
        String fontFamilyName = fontFamily.getValue();
        LOGGER.info("Handling FontFamily attribute: {}", fontFamilyName);

        if (this.loadedFonts.containsKey(fontFamilyName)) {
            this.currentFont = this.loadedFonts.get(fontFamilyName);
            LOGGER.debug("Using cached font: {}", fontFamilyName);
            return stream;
        }

        // Try standard fonts first
        // Note: PDType1Font names are specific constants, direct name matching might not be robust.
        // Example: "Helvetica" -> PDType1Font.HELVETICA.
        // This simplistic switch is for common names.
        String lowerFamilyName = fontFamilyName.toLowerCase();
        boolean isStandardFont = true;
        switch (lowerFamilyName) {
            case "helvetica":
                this.currentFont = PDType1Font.HELVETICA;
                break;
            case "helvetica-bold":
                this.currentFont = PDType1Font.HELVETICA_BOLD;
                break;
            case "helvetica-oblique":
                this.currentFont = PDType1Font.HELVETICA_OBLIQUE;
                break;
            case "helvetica-boldoblique":
            case "helvetica-boldoblique": // PDFBox uses BOLD_OBLIQUE
                this.currentFont = PDType1Font.HELVETICA_BOLD_OBLIQUE;
                break;
            case "times-roman": // PDType1Font.TIMES_ROMAN.getName() is "Times-Roman"
            case "times": // common alias
                this.currentFont = PDType1Font.TIMES_ROMAN;
                break;
            case "times-bold":
                this.currentFont = PDType1Font.TIMES_BOLD;
                break;
            case "times-italic":
                this.currentFont = PDType1Font.TIMES_ITALIC;
                break;
            case "times-bolditalic":
                this.currentFont = PDType1Font.TIMES_BOLD_ITALIC;
                break;
            case "courier":
                this.currentFont = PDType1Font.COURIER;
                break;
            case "courier-bold":
                this.currentFont = PDType1Font.COURIER_BOLD;
                break;
            case "courier-oblique":
                this.currentFont = PDType1Font.COURIER_OBLIQUE;
                break;
            case "courier-boldoblique":
                this.currentFont = PDType1Font.COURIER_BOLD_OBLIQUE;
                break;
            // Other PDType1Font names: Symbol, ZapfDingbats
            default:
                isStandardFont = false;
        }

        if (isStandardFont) {
            LOGGER.debug("Using standard PDF Type1 font: {}", this.currentFont.getName());
            // No need to cache standard fonts in loadedFonts map explicitly unless for unified access logic
        } else {
            // Try to load from custom font directory
            File fontFile = new File(customFontDir, fontFamilyName + ".ttf"); // Assuming .ttf
            if (fontFile.exists()) {
                try {
                    this.currentFont = PDType0Font.load(this.pdDocument, fontFile);
                    this.loadedFonts.put(fontFamilyName, this.currentFont);
                    LOGGER.info("Successfully loaded custom font: {} from {}", fontFamilyName, fontFile.getAbsolutePath());
                } catch (IOException e) {
                    LOGGER.warn("Failed to load custom font from {}: {}. Falling back to Helvetica.", fontFile.getAbsolutePath(), e.getMessage());
                    this.currentFont = PDType1Font.HELVETICA;
                }
            } else {
                LOGGER.warn("Custom font file not found: {}. Searched in {}. Falling back to Helvetica.", fontFamilyName + ".ttf", new File(customFontDir).getAbsolutePath());
                this.currentFont = PDType1Font.HELVETICA;
            }
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(FontSize fontSize, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling FontSize attribute: {}", fontSize.getValue().getMagnitude());
        this.currentFontSize = (float) fontSize.getValue().getMagnitude();
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(Height height, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling Height attribute: {}", height.getValue().getMagnitude());
        Element parent = height.getParentElement();
        float h = (float) height.getValue().getMagnitude();
        if (parent instanceof Image) {
            this.imageHeight = h;
        } else if (parent instanceof Rectangle) {
            this.rectHeight = h;
        } else if (parent instanceof VerticalLine) {
            this.lineEndY = this.lineY + h;
        } else if (parent instanceof TableCell) {
            this.currentCellHeight = h;
        } else {
            LOGGER.info("Height attribute not for Image, Rectangle, VerticalLine, or TableCell. Ignoring.");
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(ImageData imageData, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling ImageData attribute.");
        com.gs.ep.docknight.model.ComparableBufferedImage comparableBufferedImage = imageData.getValue();
        if (comparableBufferedImage != null && comparableBufferedImage.getValue() != null) {
            try {
                this.currentImageObject = LosslessFactory.createFromImage(this.pdDocument, comparableBufferedImage.getValue());
            } catch (IOException e) {
                LOGGER.error("Failed to create PDImageXObject from ImageData", e);
                throw e;
            }
        } else {
            LOGGER.warn("ImageData attribute value is null.");
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(Left left, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling Left attribute: {}", left.getValue().getMagnitude());
        Element parent = left.getParentElement();
        float l = (float) left.getValue().getMagnitude();
        if (parent instanceof TextElement) {
            this.currentX = l;
        } else if (parent instanceof Image) {
            this.imageX = l;
        } else if (parent instanceof Rectangle) {
            this.rectX = l;
        } else if (parent instanceof HorizontalLine || parent instanceof VerticalLine) {
            this.lineX = l;
        } else if (parent instanceof TableCell) {
            this.currentCellX = l;
        } else {
            LOGGER.info("Left attribute not for Text, Image, Rectangle, Line, or TableCell. Ignoring.");
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(LineColor lineColor, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling LineColor attribute: {}", lineColor.getValue());
        Element parent = lineColor.getParentElement();
        java.awt.Color value = lineColor.getValue();
         if (parent instanceof HorizontalLine || parent instanceof VerticalLine) {
            this.currentStrokeColor = value;
        } else if (parent instanceof Rectangle) {
            this.rectBorderColor = value;
        } else if (parent instanceof TableCell) {
             this.currentCellBorderColor = value;
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(LineWidth lineWidth, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling LineWidth attribute: {}", lineWidth.getValue().getMagnitude());
        float lw = (float) lineWidth.getValue().getMagnitude();
        Element parent = lineWidth.getParentElement();
        if (parent instanceof HorizontalLine || parent instanceof VerticalLine) {
            this.currentStrokeWidth = lw;
        } else if (parent instanceof Rectangle) {
            this.rectBorderWidth = lw;
        } else if (parent instanceof TableCell) {
            this.currentCellBorderWidth = lw;
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(PageColor pageColor, PDPageContentStream stream) throws Exception {
        // Actual drawing logic is within handleElement(Page, ...) to ensure correct order.
        // This handler can log or be a no-op if all logic is centralized there.
        LOGGER.info("PageColor attribute encountered. It's processed within Page.handleElement.");
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(PageMargins pageMargins, PDPageContentStream stream) throws Exception {
        LOGGER.info("Not yet implemented: handleAttribute(PageMargins)");
        // TODO: Potentially adjust drawable area based on margins.
        // This might involve translating the coordinate system (stream.transform(matrix))
        // or adjusting coordinates for all elements drawn on the page.
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(PageSize pageSize, PDPageContentStream stream) throws Exception {
        // Actual page size setting is within handleElement(Page, ...) when the PDPage is created.
        // This handler can log or be a no-op.
        LOGGER.info("PageSize attribute encountered. It's processed within Page.handleElement during PDPage creation.");
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(PositionalContent positionalContent, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling PositionalContent attribute.");
        if (positionalContent.getValue() != null) {
            for (Element element : positionalContent.getValue().getElements()) {
                element.accept(this, stream);
            }
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(Right right, PDPageContentStream stream) throws Exception {
        LOGGER.info("Not yet implemented: handleAttribute(Right)");
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(Text textAttribute, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling Text attribute (content).");
        String textValue = textAttribute.getValue();
        if (textValue != null && !textValue.isEmpty()) {
            try {
                stream.showText(textValue);
            } catch (IOException e) {
                LOGGER.error("Error showing text: {}", textValue, e);
                throw e;
            }
        } else {
            LOGGER.warn("Text attribute is null or empty.");
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(TextStyles textStyles, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling TextStyles attribute. Current font: {}", this.currentFont.getName());
        // This is a simplified approach. Real bold/italic often means a different font file.
        // For PDType1Font, some have bold/italic variants.
        boolean isBold = textStyles.getValue().contains(com.gs.ep.docknight.model.style.TextStyle.Bold);
        boolean isItalic = textStyles.getValue().contains(com.gs.ep.docknight.model.style.TextStyle.Italic);

        if (this.currentFont instanceof PDType1Font) {
            // Standard font styling (attempt to find a variant)
            // This logic is simplified; a more robust approach would involve checking the base font name
            // and then constructing the expected styled name (e.g., "Helvetica-Bold").
            // The FontFamily handler already tries to match some styled names directly.
            // This section is a fallback if a base font (e.g. "Helvetica") was set and now needs styling.
            String baseFontName = this.currentFont.getName().split("-")[0]; //e.g. "Helvetica-BoldOblique" -> "Helvetica"
            PDFont targetFont = this.currentFont; // Default to current

            if (baseFontName.equalsIgnoreCase("Helvetica")) {
                if (isBold && isItalic) targetFont = PDType1Font.HELVETICA_BOLD_OBLIQUE;
                else if (isBold) targetFont = PDType1Font.HELVETICA_BOLD;
                else if (isItalic) targetFont = PDType1Font.HELVETICA_OBLIQUE;
                else targetFont = PDType1Font.HELVETICA; // Plain
            } else if (baseFontName.equalsIgnoreCase("Times") || baseFontName.equalsIgnoreCase("Times-Roman")) {
                if (isBold && isItalic) targetFont = PDType1Font.TIMES_BOLD_ITALIC;
                else if (isBold) targetFont = PDType1Font.TIMES_BOLD;
                else if (isItalic) targetFont = PDType1Font.TIMES_ITALIC;
                else targetFont = PDType1Font.TIMES_ROMAN; // Plain
            } else if (baseFontName.equalsIgnoreCase("Courier")) {
                if (isBold && isItalic) targetFont = PDType1Font.COURIER_BOLD_OBLIQUE;
                else if (isBold) targetFont = PDType1Font.COURIER_BOLD;
                else if (isItalic) targetFont = PDType1Font.COURIER_OBLIQUE;
                else targetFont = PDType1Font.COURIER; // Plain
            }
            // Apply if different from current and not null
            if (targetFont != null && targetFont != this.currentFont) {
                 this.currentFont = targetFont;
                 LOGGER.debug("Switched to standard styled font: {}", this.currentFont.getName());
            } else if ( (isBold || isItalic) && targetFont == this.currentFont &&
                        !(this.currentFont.getName().toLowerCase().contains("bold") ||
                          this.currentFont.getName().toLowerCase().contains("italic") ||
                          this.currentFont.getName().toLowerCase().contains("oblique")) ){
                 // If a style was requested, but the font didn't change and isn't already styled by name
                 LOGGER.warn("No specific bold/italic variant found for standard font {}. Style may not be fully applied.", this.currentFont.getName());
            }

        } else if (this.currentFont instanceof PDType0Font) {
            // Custom loaded font (PDType0Font)
            if (isBold || isItalic) {
                // For custom fonts, bold/italic typically requires loading a separate font file
                // (e.g., "MyFont-Bold.ttf"). The FontFamily attribute should specify this.
                // The current approach loads the font specified in FontFamily. If that was "MyFont-Bold",
                // then it's already bold. This TextStyles attribute doesn't trigger a new font load here.
                LOGGER.warn("TextStyles attribute (bold/italic) applied to a custom font ({}). " +
                            "Ensure the font loaded via FontFamily attribute is the desired styled version (e.g., 'FontName-Bold.ttf'). " +
                            "This renderer does not currently derive styled custom font files from a base custom font and TextStyles.",
                            this.currentFont.getName());
            }
        } else {
            if (isBold || isItalic) {
                LOGGER.warn("TextStyles (bold/italic) encountered with an unknown font type: {}. Styles may not apply.",
                            this.currentFont != null ? this.currentFont.getClass().getName() : "null");
            }
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(Top top, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling Top attribute: {}", top.getValue().getMagnitude());
        Element parent = top.getParentElement();
        float t = (float) top.getValue().getMagnitude();
        if (parent instanceof TextElement) {
            this.currentY = t;
        } else if (parent instanceof Image) {
            this.imageY = t;
        } else if (parent instanceof Rectangle) {
            this.rectY = t;
        } else if (parent instanceof HorizontalLine || parent instanceof VerticalLine) {
            this.lineY = t;
        } else if (parent instanceof TableCell) {
            this.currentCellY = t;
        } else {
            LOGGER.info("Top attribute not for Text, Image, Rectangle, Line, or TableCell. Ignoring.");
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(Url url, PDPageContentStream stream) throws Exception {
        // This assumes URL is for an image. If it can be for other things, logic needs to be smarter.
        if (url.getParentElement() instanceof Image) {
            LOGGER.warn("Handling Url attribute for Image: {}. Remote image loading from URL is not yet implemented. Use ImageData instead.", url.getValue());
            // TODO: Implement remote image loading. This would involve:
            // 1. HTTP client to fetch image bytes.
            // 2. Create PDImageXObject from bytes (e.g., PDImageXObject.createFromByteArray).
            // This should ideally happen when ImageData is not present or fails.
        } else {
            LOGGER.info("Url attribute not for Image, ignoring: {}", url.getValue());
        }
        return stream;
    }

    @Override
    public PDPageContentStream handleAttribute(Width width, PDPageContentStream stream) throws Exception {
        LOGGER.info("Handling Width attribute: {}", width.getValue().getMagnitude());
        Element parent = width.getParentElement();
        float w = (float) width.getValue().getMagnitude();
        if (parent instanceof Image) {
            this.imageWidth = w;
        } else if (parent instanceof Rectangle) {
            this.rectWidth = w;
        } else if (parent instanceof HorizontalLine) {
            this.lineEndX = this.lineX + w;
        } else if (parent instanceof TableCell) {
            this.currentCellWidth = w;
        } else {
            LOGGER.info("Width attribute not for Image, Rectangle, HorizontalLine, or TableCell. Ignoring.");
        }
        return stream;
    }
}
