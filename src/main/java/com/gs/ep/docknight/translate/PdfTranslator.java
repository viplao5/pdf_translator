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
        
        // 3. 清除上一文档的上下文，设置新文档的上下文
        translationClient.clearContext();
        String docContext = extractDocumentContext(document);
        if (!docContext.isEmpty()) {
            translationClient.setDocumentContext(docContext);
            System.out.println("Document context set: " + docContext);
        }

        // 4. Process each page
        for (Element pageElement : document.getContainingElements(Page.class)) {
            Page page = (Page) pageElement;
            translatePage(page, targetLanguage);
        }

        return document;
    }
    
    /**
     * 从文档中提取上下文信息（标题、主题等）
     */
    private String extractDocumentContext(Document document) {
        StringBuilder context = new StringBuilder();
        
        // 尝试从第一页提取标题
        Page firstPage = null;
        for (Element pageElement : document.getContainingElements(Page.class)) {
            firstPage = (Page) pageElement;
            break; // 只取第一页
        }
        
        if (firstPage != null && firstPage.hasAttribute(PositionalContent.class)) {
            List<Element> elements = firstPage.getAttribute(PositionalContent.class).getValue().getElements();
            
            // 查找可能的标题（通常在页面顶部，字体较大或居中）
            for (int i = 0; i < Math.min(10, elements.size()); i++) {
                Element elem = elements.get(i);
                if (elem.hasAttribute(Text.class)) {
                    String text = elem.getAttribute(Text.class).getValue().trim();
                    // 过滤明显的非标题内容
                    if (text.length() > 5 && text.length() < 200 
                        && !text.contains("....") && !text.matches("^\\d+$")) {
                        if (context.length() > 0) context.append(" | ");
                        context.append(text);
                        if (context.length() > 300) break; // 限制上下文长度
                    }
                }
            }
        }
        
        return context.toString();
    }

    private void translatePage(Page page, String targetLanguage) throws Exception {
        double pageWidth = page.getAttribute(Width.class).getValue().getMagnitude();
        double pageHeight = page.getAttribute(Height.class).getValue().getMagnitude();
        System.out.println("Page width: " + pageWidth + ", Page height: " + pageHeight);

        // 获取页面中的所有元素
        List<Element> allRaw = Lists.mutable.ofAll(page.getContainingElements(e -> true));

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
                    boolean isReal = isRealTable(tabularGroup, pageHeight);
                    if (isReal) {
                        entities.add(new LayoutEntity(tabularGroup, pageWidth, pageHeight));
                    } else {
                        // Flatten "layout tables" by processing each row independently
                        // 对于词汇表等内容，每一行都应该是独立的条目
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

                            // 每行作为独立块（适用于词汇表、参考文献等）
                            entities.add(new LayoutEntity(new ElementGroup<>(rowElements), pageWidth, pageHeight));
                        }
                    }
                }
                continue;
            }

            // Check for other grouped structures
            ElementGroup<Element> vGroup = context.getVerticalGroup();

            if (vGroup != null) {
                if (processedGroups.add(vGroup)) {
                    // 尝试拆分包含多个列表项的 VerticalGroup
                    List<ElementGroup<Element>> splitGroups = splitGroupByListItems(vGroup);
                    for (ElementGroup<Element> g : splitGroups) {
                        entities.add(new LayoutEntity(g, pageWidth, pageHeight));
                    }
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

        // 检测列表型表格：2列结构，第一列是编号如 (1), (2), a., b. 等
        if (cols == 2) {
            int listMarkerCount = 0;
            for (MutableList<TabularCellElementGroup<Element>> row : table.getCells()) {
                if (row.size() >= 1 && !row.get(0).getElements().isEmpty()) {
                    // 获取第一列的文本
                    StringBuilder cellText = new StringBuilder();
                    for (Element elem : row.get(0).getElements()) {
                        if (elem.hasAttribute(Text.class)) {
                            cellText.append(elem.getAttribute(Text.class).getValue());
                        }
                    }
                    String text = cellText.toString().trim();
                    // 检查是否是列表标记: (1), (2), (a), a., b., •, - 等
                    if (text.matches("^\\(\\d+\\)$") || text.matches("^\\([a-zA-Z]\\)$") ||
                        text.matches("^[a-zA-Z]\\.$") || text.matches("^\\d+\\.$") ||
                        text.equals("•") || text.equals("-") || text.equals("*")) {
                        listMarkerCount++;
                    }
                }
            }
            // 如果大部分第一列都是列表标记，则视为列表而非表格
            if (listMarkerCount > 0 && listMarkerCount >= rows * 0.5) {
                return false; // 扁平化为列表
            }
        }

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
        
        // 检测是否是居中块：如果块明显居中，使用空格连接多行而非换行
        double blockWidth = e.right - e.left;
        double leftMargin = e.left;
        double rightMargin = e.pageWidth - e.right;
        // 居中块检测条件收紧：
        // 1. 左右边距差异小于 15pt
        // 2. 左边距 > 100pt 或 内容宽度 < 40% 页宽
        boolean isCenteredBlock = Math.abs(leftMargin - rightMargin) < 15 
                && (leftMargin > 100 || blockWidth < e.pageWidth * 0.4);
        
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
                    // 居中块使用空格连接（保持为一个完整标题）
                    // 非居中块在垂直间隙大于5时使用双换行（表示新段落）
                    if (isCenteredBlock) {
                        sb.append(" ");
                    } else {
                        sb.append(vGap > 5 ? "\n\n" : " ");
                    }
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

        double vGap = Math.max(0, Math.max(b.top - a.bottom, a.top - b.bottom));
        double hOverlap = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        double minWidth = Math.min(a.right - a.left, b.right - b.left);
        
        // 计算页面右边距（假设标准边距约 54-72pt）
        double rightMargin = a.pageWidth - 54;
        
        // === 1. SAME LINE: Bullet + Content ===
        // bullet 和内容可能被分到不同的 Area，需要合并
        if (vGap < 4 && Math.abs(a.top - b.top) < 5) {
            if (isStandaloneBullet(a) && isToRightOf(b, a) && !startsWithBullet(b))
                return true;
            if (isStandaloneBullet(b) && isToRightOf(a, b) && !startsWithBullet(a))
                return true;
        }

        // === 2. PREVENT MERGING: 新列表项/段落开始 ===
        if (startsWithBullet(b))
            return false;

        // 检查 Area 是否相同
        if (getReadingArea(a, false) != getReadingArea(b, false))
            return false;

        // === 3. SAME LINE: 水平片段合并 ===
        if (vGap < 4 && hOverlap > -5 && Math.abs(a.top - b.top) < 3)
            return true;

        // === 4. 特殊内容检测 ===
        String textA = getBlockText(a).trim();
        String textB = getBlockText(b).trim();
        
        // TOC 续行检测：目录条目可能跨多行
        // 特征：A 以章节编号开头（如 "2.2."）且没到达页面右边缘，B 是续行（可能带引导点号）
        boolean isTocEntryStart = textA.matches("^\\d+\\.\\d*\\.?\\s+.*") || textA.matches("^[A-Z]\\.\\d+\\.?\\s+.*")
                || textA.matches("^SECTION\\s+\\d+.*") || textA.matches("^GLOSSARY.*") || textA.matches("^REFERENCES.*");
        boolean aEndsNearRightEdge = a.right > a.pageWidth * 0.85;
        boolean bHasLeaderDots = textB.contains("....") || textB.contains("…");
        boolean bStartsWithSectionNumber = textB.matches("^\\d+\\.\\d*\\.?\\s+.*") || textB.matches("^[A-Z]\\.\\d+\\.?\\s+.*")
                || textB.matches("^SECTION\\s+\\d+.*");
        
        // 如果 A 是目录条目开头、没到右边缘、B 有引导点号且不是新条目、间距紧密 -> 合并为同一条目
        if (isTocEntryStart && !aEndsNearRightEdge && bHasLeaderDots && !bStartsWithSectionNumber && vGap < 8) {
            return true;
        }
        
        // TOC: 两行都包含引导点号且都是独立条目 -> 不合并
        boolean aHasLeaderDots = textA.contains("....") || textA.contains("…");
        if (aHasLeaderDots && bHasLeaderDots)
            return false;
        
        // 词汇表条目
        if (isGlossaryEntry(textA) && isGlossaryEntry(textB))
            return false;
        
        // 定义词条检测 - "术语. 定义内容" 格式
        // 例如: "acceptance. Defined in DoDI 5000.64."
        //       "amortization. The process of allocating..."
        if (isDefinitionEntry(textA) && isDefinitionEntry(textB))
            return false;
        
        // 参考文献/引用条目检测 - 每个引用应该独立
        // 模式：以标准名称、机构名称、法规名称开头
        if (isReferenceEntry(textA) && isReferenceEntry(textB))
            return false;

        // 右对齐窄块（页眉）
        boolean isRightSideA = a.left > a.pageWidth * 0.5 && (a.right - a.left) < a.pageWidth * 0.4;
        boolean isRightSideB = b.left > b.pageWidth * 0.5 && (b.right - b.left) < b.pageWidth * 0.4;
        if (isRightSideA && isRightSideB && vGap > 2)
            return false;

        // === 5. 样式检测 ===
        Element firstA = ((ElementGroup<Element>) a.group).getFirst();
        Element firstB = ((ElementGroup<Element>) b.group).getFirst();
        if (firstA != null && firstB != null) {
            if (firstA.hasAttribute(FontSize.class) && firstB.hasAttribute(FontSize.class)) {
                double sizeA = firstA.getAttribute(FontSize.class).getValue().getMagnitude();
                double sizeB = firstB.getAttribute(FontSize.class).getValue().getMagnitude();
                if (Math.abs(sizeA - sizeB) > 1.2)
                    return false;
            }
            if (isBold(firstA) != isBold(firstB))
                return false;
        }
        
        // === 5.5 居中对齐检测 - 两行都居中时应该合并 ===
        // 居中对齐的特征：左右边距相近且较大，内容宽度较窄
        double leftMarginA = a.left;
        double rightMarginA = a.pageWidth - a.right;
        double leftMarginB = b.left;
        double rightMarginB = b.pageWidth - b.right;
        double widthA = a.right - a.left;
        double widthB = b.right - b.left;
        
        // 居中检测条件收紧：
        // 1. 左右边距差异小于 15pt
        // 2. 左边距 > 100pt 或 内容宽度 < 40% 页宽
        boolean aCentered = Math.abs(leftMarginA - rightMarginA) < 15 
                && (leftMarginA > 100 || widthA < a.pageWidth * 0.4);
        boolean bCentered = Math.abs(leftMarginB - rightMarginB) < 15 
                && (leftMarginB > 100 || widthB < b.pageWidth * 0.4);
        
        // 如果两行都是居中的，且垂直间距很小，应该合并（如多行标题）
        if (aCentered && bCentered && vGap < 15) {
            return true;
        }

        // === 6. 自然段落换行检测 ===
        // 关键原则：
        // - 自然换行：上一行填满到右边距，下一行从左边距开始
        // - 段落结束：上一行没填满（提前换行），不应与下一行合并
        // - 缩进变化：如果 b.left > a.left，说明 b 是新段落/子项的开始
        
        // 计算上一行是否"填满"（右边接近页面右边距）
        // 使用页面宽度的 80% 作为阈值，更宽松地判断
        boolean aLineIsFull = a.right > a.pageWidth * 0.80;
        
        // 检测 A 是否是右对齐块（右侧接近边缘，但左侧远离左边距）
        // 右对齐块不应与居中/左对齐块合并
        boolean aIsRightAligned = a.right > a.pageWidth * 0.85 && a.left > a.pageWidth * 0.35;
        
        // 计算缩进关系
        boolean bIsMoreIndented = b.left > a.left + 5;  // b 比 a 缩进更多
        boolean bIsLessIndented = b.left < a.left - 5;  // b 比 a 缩进更少（续行特征）
        boolean sameIndent = Math.abs(a.left - b.left) <= 5;  // 相同缩进
        
        // 检测左侧位置差异过大（如右对齐页眉 vs 居中标题）
        // 如果 b 的左侧比 a 的左侧向左偏移超过 100pt，这通常不是续行
        boolean largeLeftShift = a.left - b.left > 100;
        
        // 垂直间距阈值（基于行高估算）
        double estimatedLineHeight = 12.0;
        if (firstA != null && firstA.hasAttribute(FontSize.class)) {
            estimatedLineHeight = firstA.getAttribute(FontSize.class).getValue().getMagnitude() * 1.4;
        }
        boolean tightVerticalGap = vGap < estimatedLineHeight * 0.6;  // 紧密的行间距（收紧）
        boolean veryTightGap = vGap < 5;  // 非常紧密的间距
        
        // 情况 A: 续行检测 - 上一行填满 + 下一行缩进更少 + 紧密间距
        // 这是最典型的段落内换行：(3) Are responsible for... [填满]
        //                        government property...  [从左边开始]
        // 但要排除：右对齐页眉 + 居中标题（左侧偏移过大）
        if (aLineIsFull && bIsLessIndented && tightVerticalGap && !aIsRightAligned && !largeLeftShift) {
            return true;
        }
        
        // 情况 B: 上一行填满 + 下一行相同缩进 + 紧密间距 → 同一段落
        // 但要排除右对齐块
        if (aLineIsFull && sameIndent && tightVerticalGap && !aIsRightAligned) {
            return true;
        }
        
        // 情况 C: 非常紧密的间距 + 下一行缩进更少 → 很可能是续行
        // 即使上一行没完全填满，如果间距很小且 b 从更左的位置开始，也是续行
        // 但要排除左侧偏移过大的情况（如右对齐页眉 vs 居中标题）
        if (veryTightGap && bIsLessIndented && !largeLeftShift) {
            return true;
        }
        
        // 情况 D: b 比 a 缩进更多 → 新段落/子项，不合并
        if (bIsMoreIndented)
            return false;
        
        // === 7. 保守的垂直合并 ===
        if (vGap > 6)
            return false;
        
        // 相同缩进且间距很小
        if (veryTightGap && sameIndent && hOverlap > minWidth * 0.5)
            return true;

        return false;
    }

    /**
     * 拆分包含多个独立内容块的 VerticalGroup
     * 拆分条件：
     * 1. 列表项开始（如 (1), a., • 等）
     * 2. 字体大小显著变化（> 1.5pt）
     * 3. 粗体/斜体样式变化
     * 4. 水平位置大幅变化（> 100pt，如右对齐 -> 居中）
     */
    private List<ElementGroup<Element>> splitGroupByListItems(ElementGroup<Element> group) {
        List<ElementGroup<Element>> result = new java.util.ArrayList<>();
        MutableList<Element> elements = group.getElements();
        
        if (elements.size() <= 1) {
            result.add(group);
            return result;
        }
        
        // 按 top 位置排序元素
        MutableList<Element> sortedElements = elements.toSortedListBy(e -> {
            if (e.hasAttribute(Top.class)) {
                return e.getAttribute(Top.class).getMagnitude();
            }
            return 0.0;
        });
        
        MutableList<Element> currentGroup = Lists.mutable.empty();
        double lastBottom = -1;
        Element lastElem = null;
        
        for (Element elem : sortedElements) {
            String text = "";
            if (elem.hasAttribute(Text.class)) {
                text = elem.getAttribute(Text.class).getValue().trim();
            }
            
            double elemTop = elem.hasAttribute(Top.class) ? elem.getAttribute(Top.class).getMagnitude() : 0;
            double elemLeft = elem.hasAttribute(Left.class) ? elem.getAttribute(Left.class).getMagnitude() : 0;
            
            boolean shouldSplit = false;
            
            if (!currentGroup.isEmpty() && lastElem != null && elemTop > lastBottom + 2) {
                // 检测是否是新的列表项开始
                if (isListItemStart(text)) {
                    shouldSplit = true;
                }
                
                // 检测是否是新的词汇表/缩略语条目开始
                if (isGlossaryEntryStart(text)) {
                    shouldSplit = true;
                }
                
                // 检测是否是新的定义词条开始
                // 例如: "acceptance.  Defined in...", "amortization.  The process..."
                if (isDefinitionEntry(text)) {
                    shouldSplit = true;
                }
                
                // 检测字体大小变化
                double lastFontSize = getElementFontSize(lastElem);
                double currFontSize = getElementFontSize(elem);
                if (lastFontSize > 0 && currFontSize > 0 && Math.abs(lastFontSize - currFontSize) > 1.5) {
                    shouldSplit = true;
                }
                
                // 检测粗体/斜体变化
                boolean lastBold = isBold(lastElem);
                boolean currBold = isBold(elem);
                boolean lastItalic = isItalic(lastElem);
                boolean currItalic = isItalic(elem);
                if (lastBold != currBold || lastItalic != currItalic) {
                    shouldSplit = true;
                }
                
                // 检测水平位置大幅变化（如右对齐页眉 -> 居中标题）
                double lastLeft = lastElem.hasAttribute(Left.class) ? lastElem.getAttribute(Left.class).getMagnitude() : 0;
                if (Math.abs(lastLeft - elemLeft) > 100) {
                    shouldSplit = true;
                }
            }
            
            if (shouldSplit) {
                // 保存当前组，开始新组
                if (!currentGroup.isEmpty()) {
                    result.add(new ElementGroup<>(currentGroup));
                    currentGroup = Lists.mutable.empty();
                }
            }
            
            currentGroup.add(elem);
            lastElem = elem;
            
            // 更新 lastBottom
            if (elem.hasAttribute(Top.class) && elem.hasAttribute(Height.class)) {
                double bottom = elemTop + elem.getAttribute(Height.class).getMagnitude();
                if (bottom > lastBottom) lastBottom = bottom;
            } else if (elem.hasAttribute(Top.class)) {
                if (elemTop > lastBottom) lastBottom = elemTop + 10; // 估计高度
            }
        }
        
        // 添加最后一组
        if (!currentGroup.isEmpty()) {
            result.add(new ElementGroup<>(currentGroup));
        }
        
        return result;
    }
    
    /**
     * 获取元素的字体大小
     */
    private double getElementFontSize(Element elem) {
        if (elem.hasAttribute(FontSize.class)) {
            return elem.getAttribute(FontSize.class).getMagnitude();
        }
        return 0;
    }
    
    /**
     * 检测元素是否为斜体
     */
    private boolean isItalic(Element elem) {
        if (elem.hasAttribute(TextStyles.class)) {
            List<String> styles = elem.getAttribute(TextStyles.class).getValue();
            return styles != null && styles.contains(TextStyles.ITALIC);
        }
        return false;
    }
    
    /**
     * 检测文本是否是列表项的开始
     * 注意：需要区分列表标记和缩写
     * 列表标记：(1), (a), (i), (ii) 等
     * 缩写（不是列表标记）：(IT), (IUS), (DoD), (CFO) 等
     */
    private boolean isListItemStart(String text) {
        if (text == null || text.isEmpty()) return false;
        
        // 检测括号标记，但要排除缩写
        if ((text.startsWith("(") || text.startsWith("（")) && text.length() >= 3) {
            int closeIdx = text.indexOf(')');
            if (closeIdx == -1) closeIdx = text.indexOf('）');
            if (closeIdx > 1 && closeIdx < 10) {
                String inside = text.substring(1, closeIdx);
                // 纯数字 (1), (10), (99)
                if (inside.matches("\\d{1,3}")) return true;
                // 单个字母 (a), (b), (A), (B)
                if (inside.matches("[a-zA-Z]")) return true;
                // 罗马数字 (i), (ii), (iii), (iv), (v), (vi), (vii), (viii), (ix), (x), (xi), (xii)
                if (inside.matches("(?i)^(i{1,3}|iv|v|vi{0,3}|ix|x|xi{0,2})$")) return true;
                // 数字+字母组合 (1a), (2b)
                if (inside.matches("\\d+[a-zA-Z]")) return true;
                // 排除：两个或更多大写字母的缩写 (IT), (IUS), (DoD), (CFO)
                // 这些不是列表标记，不要在这里返回 true
            }
        }
        
        // 匹配 a., b., 1., 2. 等点号列表标记
        if (text.matches("^[a-zA-Z0-9][\\.\\.、].*")) return true;
        // 匹配 • - * 等符号列表标记
        if (text.startsWith("•") || text.startsWith("-") || text.startsWith("*")) return true;
        return false;
    }
    
    /**
     * 检测文本是否是词汇表/缩略语条目
     * 模式：大写缩写 + 空格 + 解释文本
     * 例如：AIT automatic identification technologies
     *       FAR Federal Acquisition Regulations
     *       DD DoD (form)
     *       iRAPT Invoice, Receipt...
     */
    private boolean isGlossaryEntry(String text) {
        if (text == null || text.length() < 3) return false;
        
        // 模式1：以大写缩写开头（2-15个字符，包含括号、斜杠等），后跟空格和任意文字
        // 例如：AIT auto..., USD(A&S) Under..., USD(C)/CFO Under..., U.S.C. United..., DD DoD...
        if (text.matches("^[A-Z][A-Z0-9\\.\\(\\)&/]{0,14}\\s+[A-Za-z].*")) return true;
        
        // 模式2：以小写+大写混合缩写开头，后跟空格（如 iRAPT, DoDI, DoDM）
        if (text.matches("^[a-zA-Z]{2,10}\\s+[A-Z][a-z].*")) return true;
        
        return false;
    }
    
    /**
     * 检测文本是否是词汇表/缩略语条目的开始
     * 用于在初始块拆分时识别新的词汇表条目
     * 模式：缩写 + 空格 + 定义
     * 例如：APO Accountable Property Officer
     *       DoD CIO Department of Defense Chief Information Officer
     *       USD(A&S) Under Secretary of Defense for Acquisition and Sustainment
     */
    private boolean isGlossaryEntryStart(String text) {
        if (text == null || text.length() < 3) return false;
        
        // 获取第一个"单词"（空格前的部分）
        String[] parts = text.split("\\s+", 2);
        if (parts.length < 2) return false;  // 需要有缩写和定义
        
        String abbr = parts[0];
        String definition = parts[1];
        
        // 定义部分需要以字母开头（排除纯数字等情况）
        if (!definition.matches("^[A-Za-z].*")) return false;
        
        // 纯大写缩写 (2-15字符，包含可能的括号和斜杠)
        // 例如：APO, APSR, COTS, DoD CIO, USD(A&S), USD(C)/CFO
        if (abbr.matches("^[A-Z][A-Z0-9\\.\\(\\)&/]{0,14}$")) {
            return true;
        }
        
        // 混合大小写缩写 (含至少一个大写，2-12字符)
        // 例如：DoD, DoDI, DoDD, iRAPT, SNaP-IT
        if (abbr.matches("^[A-Za-z][A-Za-z0-9\\-]{1,11}$") && abbr.matches(".*[A-Z].*")) {
            return true;
        }
        
        // 带括号/斜杠的缩写 USD(A&S), USD(C)/CFO
        if (abbr.matches("^[A-Z]{2,6}\\([A-Za-z&/]+\\)(/[A-Z]+)?$")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 检测文本是否是参考文献/引用条目
     * 模式：以标准组织、法规名称、指令编号等开头
     * 例如：ASTM International E-2132-11, "Standard..."
     *       DoD Directive 5134.01, "Under Secretary..."
     *       DoD Instruction 4140.01, "DoD Supply..."
     *       Defense Federal Acquisition Regulation...
     */
    private boolean isReferenceEntry(String text) {
        if (text == null || text.length() < 10) return false;
        
        // 常见的参考文献开头模式
        String[] referencePatterns = {
            "^ASTM\\s+",           // ASTM International...
            "^DoD\\s+Directive",   // DoD Directive 5134.01...
            "^DoD\\s+Instruction", // DoD Instruction 4140.01...
            "^DoD\\s+Manual",      // DoD Manual 4100.39...
            "^DoD\\s+\\d",         // DoD 5220.22-M, DoD 7000.14-R...
            "^Defense\\s+Federal", // Defense Federal Acquisition...
            "^Deputy\\s+Secretary",// Deputy Secretary of Defense...
            "^Section\\s+\\d",     // Section 503 of Title 40...
            "^Title\\s+\\d",       // Title 40, United States Code
            "^Public\\s+Law",      // Public Law 115-91...
            "^Executive\\s+Order", // Executive Order 13514...
            "^OMB\\s+",            // OMB Circular A-123...
            "^\\d+\\s+U\\.?S\\.?C", // 10 U.S.C., 31 USC...
        };
        
        for (String pattern : referencePatterns) {
            if (text.matches("(?i)" + pattern + ".*")) return true;
        }
        
        return false;
    }
    
    /**
     * 检测是否是定义词条
     * 格式：术语.  定义内容
     * 例如：
     *   - acceptance.  Defined in DoDI 5000.64.
     *   - amortization.  The process of allocating the cost...
     *   - bulk license purchase.  A one-time purchase...
     *   - capitalized IUS.  IUS which meets or exceeds...
     */
    private boolean isDefinitionEntry(String text) {
        if (text == null || text.length() < 5) return false;
        
        // 定义词条模式：以小写字母开头的术语，后跟句点和空格，然后是定义
        // 术语可以是单个单词或多个单词（如 "bulk license purchase"）
        // 注意：术语以小写字母开头，与词汇表缩写（大写开头）区分
        
        // 模式1: 小写开头的术语 + .  + 定义文字
        // 例如: "acceptance.  Defined in..."
        //       "amortization.  The process..."
        if (text.matches("^[a-z][a-z\\s\\-]+\\.\\s{1,3}[A-Z].*")) return true;
        
        // 模式2: 带有专有名词的术语（如 "capitalized IUS"）
        // 例如: "capitalized IUS.  IUS which meets..."
        if (text.matches("^[a-z][a-z\\s\\-]*[A-Z]+[a-zA-Z]*\\.\\s{1,3}[A-Z].*")) return true;
        
        // 模式3: 大写缩写开头但后面是小写描述词的术语
        // 例如: "APO.  Defined in..." 或 "APSR.  Defined in..."
        if (text.matches("^[A-Z]{2,6}\\.\\s{1,3}(Defined|See|As defined).*")) return true;
        
        // 模式4: 专有名词开头的定义（如机构名称）
        // 例如: "Defense Logistics Agency Disposition Services.  The Defense..."
        if (text.matches("^[A-Z][a-zA-Z\\s]+\\.\\s{1,3}(The|A|An|See|As|Services).*")) return true;
        
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

        // Pattern: (xxx) or （xxx） - 括号包围的字母/数字（支持多字符如 (10), (12)）
        // 支持中英文括号
        if ((first == '(' || first == '（') && text.length() >= 3) {
            int closeIdx = text.indexOf(')');
            if (closeIdx == -1) closeIdx = text.indexOf('）');
            if (closeIdx > 1 && closeIdx < 10) { // 合理的括号闭合位置
                String inside = text.substring(1, closeIdx);
                if (inside.matches("[a-zA-Z0-9]+")) {
                    return true;
                }
            }
        }

        // Pattern: a. or 1. or a) or 一、 - 单字符或中文数字后跟标点
        if (text.length() >= 2 && (Character.isLetterOrDigit(first) || isChinese(first))) {
            char second = text.charAt(1);
            if (second == '.' || second == ')' || second == '。' || second == '、' || second == '．') {
                return true;
            }
            // 多位数字后跟点号，如 "10." "12."
            if (Character.isDigit(first) && text.length() >= 3) {
                int dotIdx = text.indexOf('.');
                if (dotIdx > 0 && dotIdx < 4 && text.substring(0, dotIdx).matches("\\d+")) {
                    return true;
                }
            }
        }

        return false;
    }
    
    private boolean isChinese(char c) {
        return c >= '\u4e00' && c <= '\u9fff';
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
        
        // 合并时保留首行起始位置：选择top更小的段落的首行位置
        double mergedFirstLineLeft;
        if (a.top <= b.top) {
            mergedFirstLineLeft = a.firstLineLeft;
        } else {
            mergedFirstLineLeft = b.firstLineLeft;
        }
        
        return new LayoutEntity(new ElementGroup<>(allElems), a.pageWidth, a.pageHeight, mergedFirstLineLeft);
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
        final double firstLineLeft;  // 首行的实际起始位置

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
                this.firstLineLeft = this.left;  // 表格使用段落左边界
            } else {
                this.isTable = false;
                ElementGroup<Element> elemGroup = (ElementGroup<Element>) group;
                RectangleProperties<Double> bbox = elemGroup.getTextBoundingBox();
                this.left = bbox.getLeft();
                this.right = bbox.getRight();
                this.top = bbox.getTop();
                this.bottom = bbox.getBottom();
                
                // 计算首行的实际起始位置
                // 找到top最小（最上面）的元素的left作为首行起始位置
                double minTop = Double.MAX_VALUE;
                double firstLeft = this.left;
                for (Element elem : elemGroup.getElements()) {
                    if (elem.hasAttribute(Top.class)) {
                        double elemTop = elem.getAttribute(Top.class).getMagnitude();
                        if (elemTop < minTop) {
                            minTop = elemTop;
                            if (elem.hasAttribute(Left.class)) {
                                firstLeft = elem.getAttribute(Left.class).getMagnitude();
                            }
                        }
                    }
                }
                this.firstLineLeft = firstLeft;
            }
        }
        
        // 带有明确首行位置的构造函数，用于合并时保留首行位置
        LayoutEntity(Object group, double pageWidth, double pageHeight, double firstLineLeft) {
            this.group = group;
            this.pageWidth = pageWidth;
            this.pageHeight = pageHeight;
            this.isTable = false;
            RectangleProperties<Double> bbox = ((ElementGroup<Element>) group).getTextBoundingBox();
            this.left = bbox.getLeft();
            this.right = bbox.getRight();
            this.top = bbox.getTop();
            this.bottom = bbox.getBottom();
            this.firstLineLeft = firstLineLeft;
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
        
        // 计算首行缩进：首行起始位置 - 段落最左边界
        double calculatedFirstLineIndent = entity.firstLineLeft - left;

        // Detect horizontal alignment intent
        // TOC Heuristic: Lines with leader dots "...." are almost always Left Aligned
        // with a specific intent
        boolean isTOCLine = translatedText.contains("....") || translatedText.contains("…");

        // List Item Heuristic: Lines starting with list markers should never be centered
        // Use (?s) to enable DOTALL mode so .* matches newlines (crucial for merged multi-line blocks)
        // 同时支持中英文标点：() 和 （）, [] 和 【】
        // 注意：中文括号后可能没有空格，如 "（2）遵守..."
        boolean isListItem = translatedText.trim().matches("(?s)^[\\(（\\[【][a-zA-Z0-9一二三四五六七八九十]{1,4}[\\)）\\]】]\\s*.*")  // (1) 或 （1）
                || translatedText.trim().matches("(?s)^[a-zA-Z0-9一二三四五六七八九十]{1,4}[\\.、．]\\s*.*")  // 1. 或 一、
                || translatedText.trim().startsWith("•")
                || translatedText.trim().startsWith("-")
                || translatedText.trim().startsWith("*");

        // Center: Geometrically centered content - 使用对称边距检测
        // 真正居中的内容应该：1) 左右边距对称 2) 不是列表项 3) 不是从页面左边缘开始
        double leftMargin = left;
        double rightMargin = pageWidth - right;
        // 对称居中检测条件收紧：
        // 1. 左右边距差异小于 15pt
        // 2. 左边距大于 100pt（明显居中，排除标准文档边距 ~72pt 的内容）
        // 3. 或者内容较短（< 40% 页宽，适合标题）
        boolean isSymmetricallyCentered = Math.abs(leftMargin - rightMargin) < 15 
                && (leftMargin > 100 || originalWidth < pageWidth * 0.4);
        
        boolean isCentered = !isTOCLine && !isListItem && originalWidth > pageWidth * 0.15
                && isSymmetricallyCentered;
        
        // 只有明确的 SECTION 标题才强制居中，不要基于全大写检测
        if ((translatedText.trim().startsWith("SECTION ") 
            || translatedText.trim().matches("(?s)^第\\d+节[:：]?\\s*[^.…]*$"))
            && !isListItem && !isTOCLine) {
            isCentered = true;
        }

        // Right: Geometrically flush right (allow some margin)
        boolean isRightAligned = !isTOCLine && !isCentered && right > pageWidth * 0.85 && left > pageWidth * 0.3;

        // Hierarchical List Item Detection and Indent Enforcement
        // Level 1: a. | A. | • | - | 一、| 二、 -> 一级列表项
        // Level 2: (1) | (a) | （1）| （2） -> 二级列表项（更深缩进）
        // 使用 (?s) 让 .* 匹配多行文本，支持中英文标点
        Pattern level1Pattern = Pattern.compile("(?s)^([a-zA-Z][\\.\\.、]|•|-|[一二三四五六七八九十]+[、．.])\\s*.*");
        // Level 2: 括号后空格可选，支持中英文括号如 "(1) Require" 或 "（1）要求"
        Pattern level2Pattern = Pattern.compile("(?s)^[\\(（][a-zA-Z0-9]+[\\)）]\\s*.*");

        String trimmedText = translatedText.trim();
        boolean isLevel1 = level1Pattern.matcher(trimmedText).matches();
        boolean isLevel2 = level2Pattern.matcher(trimmedText).matches();
        boolean isHierarchicalListItem = isLevel1 || isLevel2;

        // Adjust boundaries for clean layout
        // Use the Reading Area calculated from the original consolidated entity if
        // possible
        int area = getReadingArea(new LayoutEntity(group, pageWidth, pageHeight), multiColumn);

        // 计算基准左边距
        double baseLeft = 0;
        if (area == 1) { // Right column
            baseLeft = pageWidth * 0.52;
        } else { // Left/Single column
            baseLeft = 60.0;
        }

        // 记录是否已强制设置了列表缩进
        boolean listIndentApplied = false;
        
        if (isHierarchicalListItem) {
            // Enforce indentation hierarchy to fix incorrect source bboxes
            if (isLevel2) {
                // Level 2: Base + 48pt (二级列表项缩进)
                left = baseLeft + 48.0;
                listIndentApplied = true;
            } else if (isLevel1) {
                // Level 1: Base + 12pt (一级列表项缩进)
                left = baseLeft + 12.0;
                listIndentApplied = true;
            }
        }
        
        // 只有在未强制设置列表缩进时才调整边距
        if (!listIndentApplied) {
            if (isCentered) {
                // 居中内容：使用对称的页面边距，让 TextAlign.CENTRE 生效
                double margin = 60.0;
                left = margin;
                right = pageWidth - margin;
            } else if (isRightAligned) {
                // 右对齐内容：向左扩展宽度以避免折行，但保持右边界
                // 估算翻译后文本需要的宽度（中文字符宽度约等于字体大小）
                double estimatedFontSize = Math.max(9.0, bottom - top); // 从行高估算字体大小
                // 中文字符宽度接近字体大小，英文约为 0.5-0.6 倍
                // 对于混合文本，使用 0.9 作为平均系数，并加一些余量
                double neededWidth = translatedText.length() * estimatedFontSize * 0.9 + 20;
                double currentWidth = right - left;
                
                System.out.printf("  -> Right-aligned: need=%.1f, current=%.1f, fontSize=%.1f%n", 
                    neededWidth, currentWidth, estimatedFontSize);
                
                if (neededWidth > currentWidth) {
                    // 需要更多宽度，向左扩展
                    double newLeft = Math.max(60.0, right - neededWidth);
                    left = newLeft;
                    System.out.printf("  -> Expanded left from %.1f to %.1f%n", entity.left, left);
                }
                // 保持原始右边界（右对齐）
            } else if (multiColumn) {
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
                // Single-column: preserve wide bounds
                if (area == 1) {
                    left = Math.max(pageWidth * 0.52, left);
                } else if (left > 55) {
                    left = Math.max(60.0, left);
                }
                right = pageWidth * 0.92;
            }
        } else {
            // 列表项已设置缩进，只调整右边界
            right = pageWidth * 0.92;
        }

        if (isListItem) {
            // Compact vertical spacing for lists: Replace double newlines with single to
            // prevent excessive height requirements that cause font shrinking or overlaps
            translatedText = translatedText.replaceAll("\n\\s*\n", "\n");
        }

        // 找到第一个 TextElement，跳过 Image 等非文本元素
        Element textElement = null;
        int textElementIndex = -1;
        for (int i = 0; i < group.getElements().size(); i++) {
            Element elem = group.getElements().get(i);
            if (elem instanceof TextElement || elem.hasAttribute(Text.class)) {
                textElement = elem;
                textElementIndex = i;
                break;
            }
        }
        
        // 如果没有找到文本元素，跳过此段落的翻译
        if (textElement == null) {
            System.out.println("  -> Skipping: No text element found in group (may be image-only)");
            return;
        }

        // 检测混合样式情况：如果段落以标签开头（如 "Purpose:", "Note:"），且第一个元素是粗体，
        // 通常只有标签部分是粗体，需要移除粗体以避免整个翻译段落都变成粗体
        // 使用原始文本检测标签模式
        String originalText = getBlockText(entity);
        // 常见的标签模式：以冒号结尾的单词开头（如 "Purpose:", "Note:", "Warning:"）
        boolean startsWithLabel = originalText.matches("(?s)^[A-Z][a-zA-Z]*:\\s+.*");
        
        if (startsWithLabel && textElement.hasAttribute(TextStyles.class)) {
            List<String> firstStyles = textElement.getAttribute(TextStyles.class).getValue();
            if (firstStyles != null && firstStyles.contains(TextStyles.BOLD)) {
                // 统计段落中粗体和非粗体文本元素的数量
                int boldCount = 0;
                int nonBoldCount = 0;
                for (Element elem : group.getElements()) {
                    if (elem instanceof TextElement || elem.hasAttribute(Text.class)) {
                        if (elem.hasAttribute(TextStyles.class)) {
                            List<String> styles = elem.getAttribute(TextStyles.class).getValue();
                            if (styles != null && styles.contains(TextStyles.BOLD)) {
                                boldCount++;
                            } else {
                                nonBoldCount++;
                            }
                        } else {
                            nonBoldCount++;
                        }
                    }
                }
                
                System.out.printf("  -> Label detected: '%s', Bold=%d, NonBold=%d%n", 
                    originalText.substring(0, Math.min(20, originalText.length())), boldCount, nonBoldCount);
                
                // 如果非粗体元素存在，说明是混合样式，移除粗体
                if (nonBoldCount > 0) {
                    List<String> newStyles = Lists.mutable.ofAll(firstStyles);
                    newStyles.remove(TextStyles.BOLD);
                    textElement.removeAttribute(TextStyles.class);
                    if (!newStyles.isEmpty()) {
                        textElement.addAttribute(new TextStyles(newStyles));
                    }
                    System.out.println("  -> Removed BOLD from label paragraph, preserving normal style");
                }
            }
        }

        double width = Math.max(20, right - left);
        
        // 估算翻译文本需要的高度
        // 获取原始字体大小
        double estimatedFontSize = 10.0;
        if (textElement.hasAttribute(FontSize.class)) {
            estimatedFontSize = textElement.getAttribute(FontSize.class).getMagnitude();
        }
        double lineHeight = estimatedFontSize * 1.4;
        
        // 目录行检测：包含连续点号的行是目录条目
        boolean isTOCEntry = translatedText.contains("....") || translatedText.contains("…") 
                || translatedText.matches(".*\\.{4,}.*");
        
        double height;
        if (isTOCEntry) {
            // 目录条目：使用基于字体大小的固定行高，避免合并后的条目字体变大
            // 单行目录条目的标准高度约为 lineHeight (字体大小 * 1.4)
            height = Math.max(lineHeight, 15);
        } else {
            // 普通段落：估算需要的行数
            double avgCharWidth = estimatedFontSize * 0.8;
            int estimatedCharsPerLine = Math.max(1, (int) (width / avgCharWidth));
            int estimatedLines = Math.max(1, (int) Math.ceil((double) translatedText.length() / estimatedCharsPerLine));
            
            // 计算估算高度，确保有足够空间
            double estimatedHeight = estimatedLines * lineHeight + 10;
            
            // 取原始高度+填充和估算高度的较大值
            height = Math.max(Math.max(15, (bottom - top) + 25), estimatedHeight);
        }

        System.out.printf("Paragraph (Area %d) bbox: L=%.1f, T=%.1f, W=%.1f, H=%.1f, Text: %s%n",
                area, left, top, width, height,
                (translatedText.length() > 20 ? translatedText.substring(0, 20) : translatedText));
        
        updateText(textElement, translatedText);

        // Apply styles - 只对文本元素应用
        if (isCentered) {
            textElement.addAttribute(new TextAlign(TextAlign.CENTRE));
        } else if (isRightAligned) {
            textElement.addAttribute(new TextAlign(TextAlign.RIGHT));
        }

        // 应用首行缩进：当首行相对于段落左边界有明显缩进时（>2pt）
        // 但不应用于居中、右对齐或列表项（列表项使用整体缩进而非首行缩进）
        double effectiveFirstLineIndent = 0;
        if (!isCentered && !isRightAligned && !isHierarchicalListItem && calculatedFirstLineIndent > 2.0) {
            effectiveFirstLineIndent = calculatedFirstLineIndent;
            textElement.removeAttribute(FirstLineIndent.class);
            textElement.addAttribute(new FirstLineIndent(new Length(effectiveFirstLineIndent, Length.Unit.pt)));
            System.out.printf("  -> Applied FirstLineIndent: %.1fpt%n", effectiveFirstLineIndent);
        }

        // Update text element to cover the entire paragraph area
        textElement.removeAttribute(Left.class);
        textElement.addAttribute(new Left(new Length(left, Length.Unit.pt)));
        textElement.removeAttribute(Top.class);
        textElement.addAttribute(new Top(new Length(top, Length.Unit.pt)));
        textElement.removeAttribute(Width.class);
        // 宽度需要考虑首行缩进，确保文本不会超出边界
        textElement.addAttribute(new Width(new Length(width, Length.Unit.pt)));
        textElement.removeAttribute(Height.class);
        textElement.addAttribute(new Height(new Length(height, Length.Unit.pt)));

        // 清除其他文本元素的内容
        for (int i = 0; i < group.getElements().size(); i++) {
            if (i != textElementIndex) {
                Element elem = group.getElements().get(i);
                if (elem instanceof TextElement || elem.hasAttribute(Text.class)) {
                    updateText(elem, "");
                }
            }
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

        // 判断是否为标签-值结构的表格（2列，第一列通常是短标签）
        // 这种表格不应该进行垂直合并
        boolean isLabelValueTable = (colCount == 2);
        if (isLabelValueTable) {
            // 检查第一列是否都是较短的标签
            int shortLabelCount = 0;
            for (int r = 0; r < rowCount; r++) {
                TabularCellElementGroup<Element> cell = table.getMergedCell(r, 0);
                if (!cell.getElements().isEmpty()) {
                    StringBuilder cellText = new StringBuilder();
                    for (Element e : cell.getElements()) {
                        if (e.hasAttribute(Text.class)) {
                            cellText.append(e.getAttribute(Text.class).getValue());
                        }
                    }
                    // 如果第一列文本较短（<50字符）或以冒号结尾，可能是标签
                    String text = cellText.toString().trim();
                    if (text.length() < 50 || text.endsWith(":") || text.endsWith("：")) {
                        shortLabelCount++;
                    }
                }
            }
            isLabelValueTable = (shortLabelCount >= rowCount * 0.5);
        }

        for (int c = 0; c < colCount; c++) {
            for (int r = 0; r < rowCount; r++) {
                TabularCellElementGroup<Element> cell = table.getMergedCell(r, c);
                if (cell.getElements().isEmpty() || processed.contains(cell))
                    continue;

                // Simple vertical merger for headers or textual cells in the same column
                // 但对于标签-值表格，不进行垂直合并
                StringBuilder sb = new StringBuilder();
                List<TabularCellElementGroup<Element>> chain = new ArrayList<>();
                chain.add(cell);
                processed.add(cell);

                // Look ahead vertically (仅当不是标签-值表格时)
                if (!isLabelValueTable) {
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
