package com.gs.ep.docknight.translate;

import com.gs.ep.docknight.model.Length;
import com.gs.ep.docknight.model.PositionalElementList;
import com.gs.ep.docknight.model.ElementList;
import com.gs.ep.docknight.model.RectangleProperties;
import com.gs.ep.docknight.model.attribute.Height;
import com.gs.ep.docknight.model.attribute.Left;
import com.gs.ep.docknight.model.attribute.PositionalContent;
import com.gs.ep.docknight.model.attribute.Top;
import com.gs.ep.docknight.model.attribute.Width;
import com.gs.ep.docknight.model.Element;
import com.gs.ep.docknight.model.ElementGroup;
import com.gs.ep.docknight.model.PositionalContext;
import com.gs.ep.docknight.model.TabularCellElementGroup;
import com.gs.ep.docknight.model.TabularElementGroup;
import com.gs.ep.docknight.model.attribute.Text;
import com.gs.ep.docknight.model.attribute.FontSize;
import com.gs.ep.docknight.model.attribute.TextStyles;
import com.gs.ep.docknight.model.attribute.Content;
import com.gs.ep.docknight.model.converter.PdfParser;
import com.gs.ep.docknight.model.element.Document;
import com.gs.ep.docknight.model.element.GraphicalElement;
import com.gs.ep.docknight.model.element.Page;
import com.gs.ep.docknight.model.element.TextElement;
import com.gs.ep.docknight.model.transformer.PositionalTextGroupingTransformer;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.factory.Sets;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gs.ep.docknight.model.attribute.FirstLineIndent;
import com.gs.ep.docknight.model.attribute.TextAlign;
import java.util.regex.Pattern;

/**
 * Handles the translation of PDF documents while preserving layout and styles.
 */
public class PdfTranslator {
    private final SiliconFlowClient translationClient;
    private final PdfParser pdfParser;
    private final PositionalTextGroupingTransformer groupingTransformer;

    public PdfTranslator(SiliconFlowClient translationClient) {
        this.translationClient = translationClient;
        this.pdfParser = new PdfParser();
        this.groupingTransformer = new PositionalTextGroupingTransformer();
    }

    public Document translate(InputStream pdfStream, String targetLanguage) throws Exception {
        // 1. Parse PDF to Document model
        Document document = pdfParser.parse(pdfStream);

        // 2. Group elements into paragraphs and tables
        document = groupingTransformer.transform(document);

        // 3. Process each page
        for (Element pageElement : document.getContainingElements(Page.class)) {
            Page page = (Page) pageElement;
            translatePage(page, targetLanguage);
        }

        return document;
    }

    private void translatePage(Page page, String targetLanguage) throws Exception {
        double pageWidth = page.getAttribute(Width.class).getValue().getMagnitude();
        double pageHeight = page.getAttribute(Height.class).getValue().getMagnitude();
        System.out.println("Page width: " + pageWidth + ", Page height: " + pageHeight);

        // 0. Search for "GLOSSARY" in EVERYTHING recursively
        List<Element> allRaw = Lists.mutable.ofAll(page.getContainingElements(e -> true));
        System.out.println("--- Recursive Elements (all) Count: " + allRaw.size() + " ---");
        for (Element e : allRaw) {
            String txt = "";
            if (e instanceof TextElement) {
                Object t = ((TextElement) e).getText();
                txt = (t == null) ? "" : String.valueOf(t);
            } else if (e.hasAttribute(Text.class)) {
                Object t = e.getAttribute(Text.class).getValue();
                txt = (t == null) ? "" : String.valueOf(t);
            }
            if (txt.toUpperCase().contains("GLOSSARY")) {
                double t = e.hasAttribute(Top.class) ? e.getAttribute(Top.class).getMagnitude() : -1;
                double l = e.hasAttribute(Left.class) ? e.getAttribute(Left.class).getMagnitude() : -1;
                System.out.println("DIAGNOSTIC: Found 'GLOSSARY' at L=" + l + ", T=" + t + " in Type: "
                        + e.getClass().getSimpleName());
            }
        }

        // 0. Debug: Log ALL elements recursively
        // This section is removed as it's replaced by the more comprehensive GLOSSARY
        // search and the new allElements logic.

        // 1. Collect unique blocks
        List<LayoutEntity> entities = new ArrayList<>();
        MutableSet<ElementGroup<Element>> processedGroups = Sets.mutable.empty();
        MutableSet<TabularElementGroup<Element>> processedTables = Sets.mutable.empty();

        // Source of truth: PositionalContent
        List<Element> allElements = new ArrayList<>();
        if (page.hasAttribute(PositionalContent.class)) {
            allElements.addAll(page.getPositionalContent().getValue().getElements());
        }
        // Fallback: Content attribute
        if (page.hasAttribute(Content.class)) {
            ElementList contentList = page.getAttribute(Content.class).getValue();
            if (contentList != null) {
                for (Object o : contentList.getElements()) {
                    if (o instanceof Element && !allElements.contains(o))
                        allElements.add((Element) o);
                }
            }
        }

        // Also add any other text elements found recursively that we might have missed
        Set<Element> currentSet = new HashSet<>(allElements);
        for (Element e : allRaw) {
            if (e instanceof TextElement || e.hasAttribute(Text.class)) {
                if (!currentSet.contains(e)) {
                    allElements.add(e);
                    currentSet.add(e);
                }
            }
        }

        for (Element element : allElements) {
            PositionalContext<Element> context = element.getPositionalContext();
            if (context == null) {
                if (element instanceof TextElement) {
                    entities.add(
                            new LayoutEntity(new ElementGroup<>(Lists.mutable.of(element)), pageWidth, pageHeight));
                }
                continue;
            }

            TabularElementGroup<Element> tabularGroup = context.getTabularGroup();
            if (tabularGroup != null) {
                if (processedTables.add(tabularGroup)) {
                    if (isRealTable(tabularGroup, pageHeight)) {
                        entities.add(new LayoutEntity(tabularGroup, pageWidth, pageHeight));
                    } else {
                        // Flatten "layout tables" by merging cells within each row first.
                        // This preserves list item structure (bullet + content on same row).
                        // Also merge continuation rows (rows not starting with a bullet) with the
                        // previous item.
                        MutableList<Element> pendingRowElements = null;

                        for (MutableList<TabularCellElementGroup<Element>> row : tabularGroup.getCells()) {
                            MutableList<Element> rowElements = Lists.mutable.empty();
                            for (TabularCellElementGroup<Element> cell : row) {
                                if (cell != null && !cell.getElements().isEmpty()) {
                                    rowElements.addAllIterable(cell.getElements());
                                }
                            }
                            if (rowElements.isEmpty())
                                continue;

                            // Sort elements within the row by Left position for correct text flow
                            rowElements.sortThis((e1, e2) -> Double.compare(
                                    e1.getAttribute(Left.class).getMagnitude(),
                                    e2.getAttribute(Left.class).getMagnitude()));

                            // Check if first element of this row starts with a bullet
                            String firstText = rowElements.getFirst().getTextStr().trim();
                            boolean isBulletRow = firstText.matches("^(\\(?[a-zA-Z0-9]{1,3}[\\.)\\s]).*")
                                    || firstText.startsWith("•")
                                    || firstText.startsWith("-")
                                    || firstText.startsWith("*");

                            if (isBulletRow) {
                                // If there was a pending row, add it before starting a new item
                                if (pendingRowElements != null && !pendingRowElements.isEmpty()) {
                                    entities.add(new LayoutEntity(new ElementGroup<>(pendingRowElements), pageWidth,
                                            pageHeight));
                                }
                                pendingRowElements = rowElements;
                            } else {
                                // Continuation row - merge with pending
                                if (pendingRowElements != null) {
                                    pendingRowElements.addAllIterable(rowElements);
                                } else {
                                    // No pending row, this is a standalone row (shouldn't happen often)
                                    entities.add(
                                            new LayoutEntity(new ElementGroup<>(rowElements), pageWidth, pageHeight));
                                }
                            }
                        }
                        // Don't forget the last pending row
                        if (pendingRowElements != null && !pendingRowElements.isEmpty()) {
                            entities.add(
                                    new LayoutEntity(new ElementGroup<>(pendingRowElements), pageWidth, pageHeight));
                        }
                    }
                }
                continue;
            }

            // Check for other grouped structures
            ElementGroup<Element> vGroup = context.getVerticalGroup();

            if (vGroup != null) {
                if (processedGroups.add(vGroup)) {
                    entities.add(new LayoutEntity(vGroup, pageWidth, pageHeight));
                }
            } else if (element instanceof TextElement || element.hasAttribute(Text.class)) {
                entities.add(new LayoutEntity(new ElementGroup<>(Lists.mutable.of(element)), pageWidth, pageHeight));
            }
        }

        // Initial sort by top to help greedy consolidation
        entities.sort((a, b) -> Double.compare(a.top, b.top));

        // 2. Greedy spatial consolidation across the entire page
        System.out.println("--- Consolidation Start (Total " + entities.size() + " blocks) ---");
        List<LayoutEntity> consolidated = consolidateBlocks(entities);
        System.out.println("--- Consolidation End (Result " + consolidated.size() + " blocks) ---");

        // 3. Assign reading areas and sort
        boolean multiColumn = detectMultiColumn(consolidated, pageWidth, pageHeight);
        System.out.println("Page Layout: " + (multiColumn ? "Multi-Column" : "Single-Column") + " detected.");

        consolidated.sort((a, b) -> {
            int areaA = getReadingArea(a, multiColumn);
            int areaB = getReadingArea(b, multiColumn);
            if (areaA != areaB)
                return Integer.compare(areaA, areaB);
            if (Math.abs(a.top - b.top) < 3)
                return Double.compare(a.left, b.left);
            return Double.compare(a.top, b.top);
        });

        System.out.println("--- Final Processing Order ---");
        for (int i = 0; i < consolidated.size(); i++) {
            LayoutEntity e = consolidated.get(i);
            String fullText = getBlockText(e);
            String safeText = (fullText.length() > 60) ? fullText.substring(0, 60) + "..." : fullText;
            safeText = safeText.replace("\n", " ");
            System.out.printf("[%d] Area: %d, Top: %.1f, Left: %.1f, Bottom: %.1f, Right: %.1f, Text: %s%n",
                    i, getReadingArea(e, multiColumn), e.top, e.left, e.bottom, e.right, safeText);
        }

        // 4. Translate in reading order
        List<Element> extraElements = new ArrayList<>();
        List<LayoutEntity> paragraphEntities = new ArrayList<>();
        for (LayoutEntity entity : consolidated) {
            if (!entity.isTable) {
                paragraphEntities.add(entity);
            }
        }

        List<String> paraTexts = new ArrayList<>();
        for (LayoutEntity e : paragraphEntities) {
            paraTexts.add(getBlockText(e));
        }

        List<String> paraTranslations = paraTexts.isEmpty() ? new ArrayList<>()
                : translationClient.translate(paraTexts, targetLanguage);

        int paraIdx = 0;
        for (LayoutEntity entity : consolidated) {
            if (entity.isTable) {
                translateTable((TabularElementGroup<Element>) entity.group, targetLanguage, page, extraElements);
            } else {
                applyParagraphTranslation(entity, paraTranslations.get(paraIdx++), pageWidth, pageHeight, multiColumn);
            }
        }

        if (!extraElements.isEmpty()) {
            PositionalElementList<Element> current = page.getPositionalContent().getValue();
            List<Element> all = new ArrayList<>(current.getElements());
            all.addAll(extraElements);
            PositionalElementList<Element> next = new PositionalElementList<>(all, false);
            // Copy groups to avoid losing layout info
            for (ElementGroup<Element> vg : current.getVerticalGroups()) {
                next.addVerticalGroup(vg);
            }
            for (TabularElementGroup<Element> tg : current.getTabularGroups()) {
                next.addTabularGroup(tg);
            }
            PositionalContent pc = page.getAttribute(PositionalContent.class);
            if (pc != null) {
                pc.setValue(next);
            } else {
                page.addAttribute(new PositionalContent(next));
            }
        }
    }

    private boolean isRealTable(TabularElementGroup<Element> table, double pageHeight) {
        int rows = table.numberOfRows();
        int cols = table.numberOfColumns();

        // Calculate complexity metrics: find bounding box, border density, and
        // backgrounds
        double minT = Double.MAX_VALUE, maxB = Double.MIN_VALUE;
        int cellsWithBorders = 0;
        int nonEmptyCells = 0;

        for (MutableList<TabularCellElementGroup<Element>> row : table.getCells()) {
            for (TabularCellElementGroup<Element> cell : row) {
                if (!cell.getElements().isEmpty()) {
                    nonEmptyCells++;
                    RectangleProperties<Double> cBox = cell.getTextBoundingBox();
                    minT = Math.min(minT, cBox.getTop());
                    maxB = Math.max(maxB, cBox.getBottom());

                    RectangleProperties<Boolean> borders = cell.getBorderExistence();
                    // count cells that are fully or partially bordered
                    if (borders.getTop() || borders.getBottom() || borders.getLeft() || borders.getRight()) {
                        cellsWithBorders++;
                    }
                }
            }
        }
        double tableHeight = (maxB == Double.MIN_VALUE) ? 0 : (maxB - minT);

        // 1. FLATTEN: Large containers with low border density
        // Flatten huge page-wrappers or containers that are primarily background boxes
        // (low border density)
        if (tableHeight > pageHeight * 0.4) {
            // Force flatten if it looks like a multi-column page layout (tall, 1-2 columns)
            if (cols <= 2)
                return false;

            double borderRatio = (nonEmptyCells == 0) ? 0 : (double) cellsWithBorders / nonEmptyCells;
            if (borderRatio < 0.25)
                return false;
        }

        // 2. DATA TABLE: Highly structured or small dense tables
        // 2. DATA TABLE: Highly structured or small dense tables
        if (rows > 8 || cols > 4) {
            // Exception for List-like tables: 2 columns, First column is very narrow
            // (bullets)
            if (cols == 2) {
                double totalCol1Width = 0;
                int validRows = 0;
                for (MutableList<TabularCellElementGroup<Element>> row : table.getCells()) {
                    if (row.size() >= 2 && !row.get(0).getElements().isEmpty()) {
                        RectangleProperties<Double> cBox = row.get(0).getTextBoundingBox();
                        totalCol1Width += (cBox.getRight() - cBox.getLeft());
                        validRows++;
                    }
                }
                double avgCol1Width = (validRows == 0) ? 0 : totalCol1Width / validRows;
                // If first column is consistently narrow (< 60pt or < 10% of page), it's likely
                // a list bullet column
                if (validRows > rows * 0.5 && avgCol1Width < 60.0) {
                    return false; // Treat as List -> Flatten
                }
            }
            return true;
        }
        if (nonEmptyCells > 0 && (double) cellsWithBorders / nonEmptyCells > 0.5)
            return true;

        // Flatten small 1x1 or 2x2 boxes that identify as tables but have no borders
        // (likely layout boxes)
        if (cellsWithBorders == 0 && (rows <= 2 && cols <= 2))
            return false;

        return rows >= 2 && cols >= 2;
    }

    private boolean detectMultiColumn(List<LayoutEntity> blocks, double pageWidth, double pageHeight) {
        int parallelPairings = 0;
        for (int i = 0; i < blocks.size(); i++) {
            LayoutEntity a = blocks.get(i);
            // Ignore small noise in headers, but include tall blocks
            if (a.top < pageHeight * 0.1 && (a.bottom - a.top) < 30)
                continue;
            if (a.bottom > pageHeight * 0.9 && (a.bottom - a.top) < 30)
                continue;

            for (int j = i + 1; j < blocks.size(); j++) {
                LayoutEntity b = blocks.get(j);
                if (b.top < pageHeight * 0.1 && (b.bottom - b.top) < 30)
                    continue;

                // Check for parallel narrow blocks (width < 60%)
                if ((a.right - a.left) < pageWidth * 0.6 && (b.right - b.left) < pageWidth * 0.6) {
                    double overlap = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
                    double hGap = Math.abs((a.left + a.right) / 2.0 - (b.left + b.right) / 2.0);
                    // Clear horizontal separation and vertical overlap
                    if (overlap > 10 && hGap > pageWidth * 0.35) {
                        parallelPairings++;
                    }
                }
            }
        }
        // Even one strong column-pairing indicates multi-column layout intent
        return parallelPairings >= 1;
    }

    private String getBlockText(LayoutEntity e) {
        if (e.isTable)
            return "[TABLE]";
        StringBuilder sb = new StringBuilder();
        Element prev = null;
        for (Element el : ((ElementGroup<Element>) e.group).getElements()) {
            String text = "";
            if (el instanceof TextElement) {
                Object t = ((TextElement) el).getText();
                text = (t == null) ? "" : String.valueOf(t);
            } else if (el.hasAttribute(Text.class)) {
                Object t = el.getAttribute(Text.class);
                text = (t == null) ? "" : String.valueOf(t);
            }
            if (text == null)
                text = "";

            if (!text.isEmpty()) {
                if (prev != null) {
                    double vGap = el.getAttribute(Top.class).getMagnitude()
                            - (prev.getAttribute(Top.class).getMagnitude()
                                    + prev.getAttribute(Height.class).getMagnitude());
                    sb.append(vGap > 5 ? "\n\n" : " ");
                }
                sb.append(text);
                prev = el;
            }
        }
        return sb.toString().trim();
    }

    private int getReadingArea(LayoutEntity entity, boolean multiColumn) {
        double width = entity.right - entity.left;
        double height = entity.bottom - entity.top;
        double centerX = (entity.left + entity.right) / 2.0;

        // Header/Footer check: Wide and short blocks at extremes
        if (width > entity.pageWidth * 0.4 && height < 100) {
            if (entity.top < entity.pageHeight * 0.12)
                return -1;
            if (entity.bottom > entity.pageHeight * 0.88)
                return 2;
        }

        if (!multiColumn) {
            // Persistent area -1/2 for narrow but clear headers/footers
            if (entity.top < entity.pageHeight * 0.08 && width < entity.pageWidth * 0.4)
                return -1;
            return 0;
        }

        // Multi-column logic: assign Areas for Column-wise sorting
        // Wide blocks (titles) or Centered content: Area 0 to keep top-to-bottom flow
        if (width > entity.pageWidth * 0.65) {
            return 0; // Wide spanning block
        }
        if (Math.abs(centerX - entity.pageWidth * 0.5) < 15 && width < entity.pageWidth * 0.5) {
            return 0; // Centered block
        }

        // Clearly on the left or right
        int area = (centerX < entity.pageWidth * 0.52) ? 0 : 1; // Slight bias to left for gutter center
        return area;
    }

    private List<LayoutEntity> consolidateBlocks(List<LayoutEntity> blocks) {
        if (blocks.size() < 2)
            return blocks;
        List<LayoutEntity> current = new ArrayList<>(blocks);
        boolean merged;
        do {
            merged = false;
            for (int i = 0; i < current.size(); i++) {
                for (int j = i + 1; j < current.size(); j++) {
                    LayoutEntity a = current.get(i);
                    LayoutEntity b = current.get(j);
                    if (shouldMerge(a, b)) {
                        LayoutEntity mergedEntity = merge(a, b);
                        String rawA = getBlockText(a);
                        String rawB = getBlockText(b);
                        String textA = rawA.substring(0, Math.min(15, rawA.length())).replace("\n", " ");
                        String textB = rawB.substring(0, Math.min(15, rawB.length())).replace("\n", " ");
                        System.out.println("Merging: [" + textA + "] and [" + textB + "]");
                        current.set(i, mergedEntity);
                        current.remove(j);
                        merged = true;
                        break;
                    }
                }
                if (merged)
                    break;
            }
        } while (merged);
        return current;
    }

    private boolean shouldMerge(LayoutEntity a, LayoutEntity b) {
        if (a.isTable || b.isTable)
            return false;
        if (getReadingArea(a, false) != getReadingArea(b, false)) // Use single-column logic for merging
            return false;

        double vGap = Math.max(0, Math.max(b.top - a.bottom, a.top - b.bottom));
        double hOverlap = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        double minWidth = Math.min(a.right - a.left, b.right - b.left);

        // A. SAME LINE: Bullet + Content or fragments on same Y
        if (vGap < 4) {
            if (isStandaloneBullet(a) && isToRightOf(b, a))
                return true;
            if (isStandaloneBullet(b) && isToRightOf(a, b))
                return true;
            if (hOverlap > -5 && Math.abs(a.top - b.top) < 3)
                return true; // horizontal join
        }

        // B. PREVENT MERGING: Style or Gap thresholds
        // If 'b' starts with a bullet, it is a new list item/paragraph.
        // We should NOT merge it with 'a', even if the vertical gap is small.
        if (startsWithBullet(b))
            return false;

        // TOC/Index Heuristic: Don't merge if lines contain leader dots "...."
        // This prevents Table of Contents entries from merging into one block
        if (getBlockText(a).contains("....") || getBlockText(b).contains("....") || getBlockText(a).contains("…")
                || getBlockText(b).contains("…"))
            return false;

        // C. CONTINUATION LINE MERGING
        // If 'b' is indented relative to 'a', doesn't start with a bullet, and is close
        // vertically,
        // it's likely a continuation of 'a'. Force merge even if styles slightly
        // differ.
        boolean isContinuationLine = !startsWithBullet(b) && b.left > a.left + 10 && vGap < 10
                && hOverlap > minWidth * 0.3;
        if (isContinuationLine) {
            return true; // Force merge continuation lines
        }

        // Style check: Don't merge if font sizes differ significantly (>1pt)
        Element firstA = ((ElementGroup<Element>) a.group).getFirst();
        Element firstB = ((ElementGroup<Element>) b.group).getFirst();
        if (firstA != null && firstB != null) {
            if (firstA.hasAttribute(FontSize.class) && firstB.hasAttribute(FontSize.class)) {
                double sizeA = firstA.getAttribute(FontSize.class).getValue().getMagnitude();
                double sizeB = firstB.getAttribute(FontSize.class).getValue().getMagnitude();
                if (Math.abs(sizeA - sizeB) > 1.2)
                    return false;
            }
            // Style check: Don't merge Bold with Normal text
            if (isBold(firstA) != isBold(firstB))
                return false;
        }

        if (vGap > 6)
            return false; // Tightized threshold (from 8) to prevent merging distinct paragraphs (tuned
                          // from 8)

        // C. VERTICAL STACKING: Lines within the same paragraph
        // Consistent left alignment and tight vertical gap
        if (vGap < 6 && (hOverlap > minWidth * 0.4 || Math.abs(a.left - b.left) < 10))
            return true;

        // Slightly looser but still very close and aligned
        if (vGap < 8 && Math.abs(a.left - b.left) < 6 && hOverlap > minWidth * 0.8)
            return true;

        return false;
    }

    private boolean isStandaloneBullet(LayoutEntity e) {
        return (e.right - e.left) < 30 && startsWithBullet(e);
    }

    private boolean startsWithBullet(LayoutEntity e) {
        String text = getBlockText(e).trim();
        if (text.isEmpty())
            return false;
        char first = text.charAt(0);
        // Common bullets: •, -, *
        if (first == '•' || first == '-' || first == '*')
            return true;

        // Pattern: (a) or (1) - parenthesized single char
        if (first == '(' && text.length() >= 3) {
            if (Character.isLetterOrDigit(text.charAt(1)) && text.charAt(2) == ')') {
                return true;
            }
        }

        // Pattern: a. or 1. or a) - SINGLE char followed by . or )
        if (text.length() >= 2 && Character.isLetterOrDigit(first)) {
            char second = text.charAt(1);
            if (second == '.' || second == ')') {
                return true;
            }
        }

        return false;
    }

    private boolean isBold(Element e) {
        if (e == null || !e.hasAttribute(TextStyles.class))
            return false;
        List<String> styles = e.getAttribute(TextStyles.class).getValue();
        return styles != null && styles.contains(TextStyles.BOLD);
    }

    private boolean isToRightOf(LayoutEntity target, LayoutEntity bullet) {
        return target.left >= bullet.right - 15 && target.left < bullet.right + 50;
    }

    private LayoutEntity merge(LayoutEntity a, LayoutEntity b) {
        MutableList<Element> allElems = Lists.mutable.ofAll(((ElementGroup<Element>) a.group).getElements());
        allElems.addAllIterable(((ElementGroup<Element>) b.group).getElements());
        // Sort elements to ensure text flow is Top-to-Bottom, then Left-to-Right
        allElems.sortThis((e1, e2) -> {
            double t1 = e1.getAttribute(Top.class).getMagnitude();
            double t2 = e2.getAttribute(Top.class).getMagnitude();
            if (Math.abs(t1 - t2) < 5)
                return Double.compare(e1.getAttribute(Left.class).getMagnitude(),
                        e2.getAttribute(Left.class).getMagnitude());
            return Double.compare(t1, t2);
        });
        return new LayoutEntity(new ElementGroup<>(allElems), a.pageWidth, a.pageHeight);
    }

    private static class LayoutEntity {
        final Object group; // ElementGroup or TabularElementGroup
        final boolean isTable;
        final double left;
        final double right;
        final double top;
        final double bottom;
        final double pageWidth;
        final double pageHeight;

        LayoutEntity(Object group, double pageWidth, double pageHeight) {
            this.group = group;
            this.pageWidth = pageWidth;
            this.pageHeight = pageHeight;
            if (group instanceof TabularElementGroup) {
                this.isTable = true;
                TabularElementGroup<Element> table = (TabularElementGroup<Element>) group;
                double minL = Double.MAX_VALUE, maxR = Double.MIN_VALUE, minT = Double.MAX_VALUE,
                        maxB = Double.MIN_VALUE;
                for (MutableList<TabularCellElementGroup<Element>> row : table.getCells()) {
                    for (TabularCellElementGroup<Element> cell : row) {
                        if (!cell.getElements().isEmpty()) {
                            RectangleProperties<Double> cBox = cell.getTextBoundingBox();
                            minL = Math.min(minL, cBox.getLeft());
                            maxR = Math.max(maxR, cBox.getRight());
                            minT = Math.min(minT, cBox.getTop());
                            maxB = Math.max(maxB, cBox.getBottom());
                        }
                    }
                }
                this.left = (minL == Double.MAX_VALUE) ? 0 : minL;
                this.right = (maxR == Double.MIN_VALUE) ? 0 : maxR;
                this.top = (minT == Double.MAX_VALUE) ? 0 : minT;
                this.bottom = (maxB == Double.MIN_VALUE) ? 0 : maxB;
            } else {
                this.isTable = false;
                RectangleProperties<Double> bbox = ((ElementGroup<Element>) group).getTextBoundingBox();
                this.left = bbox.getLeft();
                this.right = bbox.getRight();
                this.top = bbox.getTop();
                this.bottom = bbox.getBottom();
            }
        }
    }

    private void applyParagraphTranslation(LayoutEntity entity, String translatedText, double pageWidth,
            double pageHeight, boolean multiColumn) throws Exception {
        ElementGroup<Element> group = (ElementGroup<Element>) entity.group;
        if (translatedText.trim().isEmpty())
            return;

        RectangleProperties<Double> bbox = group.getTextBoundingBox();
        double left = bbox.getLeft();
        double right = bbox.getRight();
        double top = bbox.getTop();
        double bottom = bbox.getBottom();
        double originalWidth = right - left;
        double centerX = (left + right) / 2.0;

        // Detect horizontal alignment intent
        // TOC Heuristic: Lines with leader dots "...." are almost always Left Aligned
        // with a specific intent
        boolean isTOCLine = translatedText.contains("....") || translatedText.contains("…");

        // List Item Heuristic: Lines starting with list markers should never be
        // centered
        boolean isListItem = translatedText.trim().matches("^[\\(\\[]?[a-zA-Z0-9]{1,3}[\\)\\]\\.\\s].*")
                || translatedText.trim().startsWith("•")
                || translatedText.trim().startsWith("-")
                || translatedText.trim().startsWith("*");

        // Center: Significant width but NOT full width, and geometrically centered
        boolean isCentered = !isTOCLine && !isListItem && originalWidth > pageWidth * 0.15
                && originalWidth < pageWidth * 0.75
                && Math.abs(centerX - pageWidth * 0.5) < pageWidth * 0.05;

        // Right: Geometrically flush right (allow some margin)
        boolean isRightAligned = !isTOCLine && !isCentered && right > pageWidth * 0.85 && left > pageWidth * 0.3;

        // Hierarchical List Item Detection and Indent Enforcement
        // Level 1: a. | A. | • | - -> Enforce Min Left ~ 72pt (Flush with main text
        // usually, but ensure consistency)
        // Level 2: (1) | (a) -> Enforce Min Left ~ 108pt (Indented 36pt)
        Pattern level1Pattern = Pattern.compile("^([a-zA-Z]\\.|•|-)\\s+.*");
        Pattern level2Pattern = Pattern.compile("^\\(\\d+\\)\\s+.*");

        boolean isLevel1 = level1Pattern.matcher(translatedText).matches();
        boolean isLevel2 = level2Pattern.matcher(translatedText).matches();
        boolean isHierarchicalListItem = isLevel1 || isLevel2;

        // Adjust boundaries for clean layout
        // Use the Reading Area calculated from the original consolidated entity if
        // possible
        int area = getReadingArea(new LayoutEntity(group, pageWidth, pageHeight), multiColumn);

        if (isHierarchicalListItem) {
            // Enforce indentation hierarchy to fix incorrect source bboxes (e.g. (2) flush
            // left or c. indented too much)
            // Determine base left margin based on column area
            double baseLeft = 0;
            if (area == 1) { // Right column
                baseLeft = pageWidth * 0.52;
            } else { // Left/Single column (using 55-60 safety margin as base)
                baseLeft = 60.0;
            }

            // STRICTLY enforce indentation levels to ensure alignment
            if (isLevel2) {
                // Level 2: Base + 48pt (Indent w.r.t Level 1)
                left = baseLeft + 48.0;
            } else if (isLevel1) {
                // Level 1: Base + 12pt (Standard list indent)
                left = baseLeft + 12.0;
            }
        }

        Element first = group.getFirst();
        if (multiColumn && !isCentered) {
            // Strict column boundaries for narrow multi-column content
            if (area == 0 && right < pageWidth * 0.55) {
                // Sidebar-aware margin: Keep very-left elements (like page numbers) but push
                // main text
                if (left > 55)
                    left = Math.max(60.0, left);
                right = pageWidth * 0.48;
            } else if (area == 1) {
                left = Math.max(pageWidth * 0.52, left);
                right = pageWidth * 0.92;
            } else {
                // Area -1, 0 (Wide/Title), or 2
                if (left > 55)
                    left = Math.max(60.0, left);
                right = pageWidth * 0.92;
            }
        } else {
            // Centered or Single-column: preserve wide bounds
            if (area == 1) {
                left = Math.max(pageWidth * 0.52, left);
            } else if (left > 55) {
                left = Math.max(60.0, left);
            }
            right = pageWidth * 0.92;
        }

        if (isListItem) {
            // Enforce indentation hierarchy to fix incorrect source bboxes (e.g. (2) flush
            // left)
            // Determine base left margin based on column area
            double baseLeft = 0;
            if (area == 1) { // Right column
                baseLeft = pageWidth * 0.52;
            } else { // Left/Single column (using 55-60 safety margin as base)
                baseLeft = 60.0;
                // If the detected left is significantly larger (e.g. 72 vs 60), trust it?
                // But for Level 2 enforcement we want relative to strict margins.
            }

            if (isLevel2) {
                // Enforce at least base + 48pt (approx 108pt for single column)
                left = baseLeft + 48.0;
            } else if (isLevel1) {
                // Enforce at least base + 12pt (approx 72pt for single column)
                left = baseLeft + 12.0;
            }
        }

        double width = Math.max(20, right - left);
        // Give 25% extra height for Chinese wrapping and prevent aggressive scaling
        double height = Math.max(15, (bottom - top) * 1.25);

        System.out.printf("Paragraph (Area %d) bbox: L=%.1f, T=%.1f, W=%.1f, H=%.1f, Text: %s%n",
                area, left, top, width, height,
                (translatedText.length() > 20 ? translatedText.substring(0, 20) : translatedText));
        updateText(first, translatedText);

        // Apply styles
        if (isCentered) {
            first.addAttribute(new TextAlign(TextAlign.CENTRE));
        } else if (isRightAligned) {
            first.addAttribute(new TextAlign(TextAlign.RIGHT));
        }

        if (isListItem) {
            // Standard Chinese paragraph-like indent for list items as per user request
            // 20pt First Line Indent
            first.addAttribute(new FirstLineIndent(new Length(20, Length.Unit.pt)));
        }

        // Update first element to cover the entire paragraph area
        first.removeAttribute(Left.class);
        first.addAttribute(new Left(new Length(left, Length.Unit.pt)));
        first.removeAttribute(Top.class);
        first.addAttribute(new Top(new Length(top, Length.Unit.pt)));
        first.removeAttribute(Width.class);
        first.addAttribute(new Width(new Length(width, Length.Unit.pt)));
        first.removeAttribute(Height.class);
        first.addAttribute(new Height(new Length(height, Length.Unit.pt)));

        for (int i = 1; i < group.getElements().size(); i++) {
            updateText(group.getElements().get(i), ""); // Clear others
        }
    }

    private void translateTable(TabularElementGroup<Element> table, String targetLanguage, Page page,
            List<Element> extraElements) throws Exception {
        int rowCount = table.numberOfRows();
        int colCount = table.numberOfColumns();

        // 1. Row/Col boundaries
        org.eclipse.collections.api.tuple.Pair<double[], double[]> colBounds = table.getColumnBoundaries();
        double[] colLefts = colBounds.getOne();
        double[] colRights = colBounds.getTwo();

        double[] rowTops = new double[rowCount];
        double[] rowBottoms = new double[rowCount];
        for (int r = 0; r < rowCount; r++) {
            double minT = Double.MAX_VALUE, maxB = Double.MIN_VALUE;
            boolean hasContent = false;
            for (int c = 0; c < colCount; c++) {
                TabularCellElementGroup<Element> cell = table.getMergedCell(r, c);
                if (!cell.getElements().isEmpty()) {
                    RectangleProperties<Double> cBox = cell.getTextBoundingBox();
                    minT = Math.min(minT, cBox.getTop());
                    maxB = Math.max(maxB, cBox.getBottom());
                    hasContent = true;
                }
            }
            rowTops[r] = hasContent ? minT : (r > 0 ? rowBottoms[r - 1] : 0);
            rowBottoms[r] = hasContent ? maxB : (r > 0 ? rowBottoms[r - 1] + 20 : 20);
        }

        // 2. Identify and group cells for integrated translation
        // Map from master cell -> its associated logical text/state
        Map<TabularCellElementGroup<Element>, String> cellToJoinedText = new HashMap<>();
        Map<TabularCellElementGroup<Element>, TabularCellElementGroup<Element>> cellToMaster = new HashMap<>();
        Set<TabularCellElementGroup<Element>> processed = new HashSet<>();

        for (int c = 0; c < colCount; c++) {
            for (int r = 0; r < rowCount; r++) {
                TabularCellElementGroup<Element> cell = table.getMergedCell(r, c);
                if (cell.getElements().isEmpty() || processed.contains(cell))
                    continue;

                // Simple vertical merger for headers or textual cells in the same column
                StringBuilder sb = new StringBuilder();
                List<TabularCellElementGroup<Element>> chain = new ArrayList<>();
                chain.add(cell);
                processed.add(cell);

                // Look ahead vertically
                int nextR = r + 1;
                while (nextR < rowCount) {
                    TabularCellElementGroup<Element> nextCell = table.getMergedCell(nextR, c);
                    if (!nextCell.getElements().isEmpty() && !processed.contains(nextCell)) {
                        RectangleProperties<Boolean> borders = cell.getBorderExistence();
                        RectangleProperties<Boolean> nextBorders = nextCell.getBorderExistence();

                        // Merge if no border between them and both are headers or look like text
                        boolean noBorder = !borders.getBottom() && !nextBorders.getTop();

                        // Style check: Don't merge cells with different styles in the same logical
                        // block
                        boolean sameStyle = true;
                        Element firstCurrent = cell.getFirst();
                        Element firstNext = nextCell.getFirst();
                        if (firstCurrent != null && firstNext != null) {
                            if (firstCurrent.hasAttribute(FontSize.class) && firstNext.hasAttribute(FontSize.class)) {
                                double s1 = firstCurrent.getAttribute(FontSize.class).getValue().getMagnitude();
                                double s2 = firstNext.getAttribute(FontSize.class).getValue().getMagnitude();
                                if (Math.abs(s1 - s2) > 1.2)
                                    sameStyle = false;
                            }
                            if (isBold(firstCurrent) != isBold(firstNext))
                                sameStyle = false;
                        }

                        if (noBorder && sameStyle) {
                            chain.add(nextCell);
                            processed.add(nextCell);
                            cell = nextCell; // move down the chain
                            nextR++;
                        } else {
                            break;
                        }
                    } else {
                        break;
                    }
                }

                // Join text for the chain
                Element prevElem = null;
                for (TabularCellElementGroup<Element> chainCell : chain) {
                    StringBuilder cellSb = new StringBuilder();
                    for (Element e : chainCell.getElements()) {
                        if (e.hasAttribute(Text.class)) {
                            if (prevElem != null) {
                                double vGap = e.getAttribute(Top.class).getMagnitude()
                                        - (prevElem.getAttribute(Top.class).getMagnitude()
                                                + prevElem.getAttribute(Height.class).getMagnitude());
                                cellSb.append(vGap > 5 ? "\n\n" : " ");
                            }
                            cellSb.append(e.getAttribute(Text.class).getValue());
                            prevElem = e;
                        }
                    }
                    String t = cellSb.toString().trim();
                    if (!t.isEmpty()) {
                        if (sb.length() > 0)
                            sb.append("\n\n"); // Chain cells usually separate logical blocks if vertically merged
                        sb.append(t);
                    }
                }

                if (sb.length() > 0) {
                    TabularCellElementGroup<Element> primary = chain.get(0);
                    cellToJoinedText.put(primary, sb.toString());
                    for (TabularCellElementGroup<Element> chainCell : chain) {
                        cellToMaster.put(chainCell, primary);
                    }
                }
            }
        }

        if (cellToJoinedText.isEmpty())
            return;

        // 3. Batch translate
        List<TabularCellElementGroup<Element>> primaries = new ArrayList<>(cellToJoinedText.keySet());
        List<String> rawTexts = new ArrayList<>();
        for (TabularCellElementGroup<Element> p : primaries) {
            rawTexts.add(cellToJoinedText.get(p));
        }

        List<String> translations = translationClient.translate(rawTexts, targetLanguage);

        // 4. Update elements
        for (int i = 0; i < primaries.size(); i++) {
            TabularCellElementGroup<Element> primary = primaries.get(i);
            String translated = translations.get(i);

            // Find full bounding box for the chain associated with this primary
            int minR = 1000, maxR = -1;
            int minC = 1000, maxC = -1;
            for (int r = 0; r < rowCount; r++) {
                for (int c = 0; c < colCount; c++) {
                    if (table.getMergedCell(r, c) == primary) {
                        minR = Math.min(minR, r);
                        maxR = Math.max(maxR, r);
                        minC = Math.min(minC, c);
                        maxC = Math.max(maxC, c);
                    }
                }
            }
            // Also include cells that were merged into this primary via my vertical merger
            for (int r = 0; r < rowCount; r++) {
                for (int c = 0; c < colCount; c++) {
                    if (cellToMaster.get(table.getMergedCell(r, c)) == primary) {
                        minR = Math.min(minR, r);
                        maxR = Math.max(maxR, r);
                        minC = Math.min(minC, c);
                        maxC = Math.max(maxC, c);
                    }
                }
            }

            double left = colLefts[minC];
            double right = colRights[maxC];
            double top = rowTops[minR];
            double bottom = rowBottoms[maxR];
            double width = Math.max(20, right - left);
            double height = Math.max(15, (bottom - top));

            Element first = primary.getFirst();
            updateText(first, translated);
            first.removeAttribute(Left.class);
            first.addAttribute(new Left(new Length(left, Length.Unit.pt)));
            first.removeAttribute(Top.class);
            first.addAttribute(new Top(new Length(top, Length.Unit.pt)));
            first.removeAttribute(Width.class);
            first.addAttribute(new Width(new Length(width, Length.Unit.pt)));
            first.removeAttribute(Height.class);
            first.addAttribute(new Height(new Length(height, Length.Unit.pt)));

            // Empty all other elements in all cells of this logical block
            for (int r = minR; r <= maxR; r++) {
                for (int c = minC; c <= maxC; c++) {
                    TabularCellElementGroup<Element> cell = table.getMergedCell(r, c);
                    for (int j = 0; j < cell.getElements().size(); j++) {
                        Element e = cell.getElements().get(j);
                        if (e != first)
                            updateText(e, "");
                    }
                }
            }
        }
    }

    private void translateSingleElement(Element element, String targetLanguage) throws Exception {
        if (!element.hasAttribute(Text.class))
            return;
        String originalText = element.getAttribute(Text.class).getValue();
        if (originalText.trim().isEmpty())
            return;

        List<String> translations = translationClient.translate(Lists.mutable.of(originalText), targetLanguage);
        updateText(element, translations.get(0));
    }

    private void updateText(Element element, String translatedText) {
        if (element instanceof TextElement) {
            ((TextElement) element).removeAttribute(Text.class);
            ((TextElement) element).add(new Text(translatedText));
        } else if (element.hasAttribute(Text.class)) {
            element.removeAttribute(Text.class);
            element.addAttribute(new Text(translatedText));
        }
    }
}
