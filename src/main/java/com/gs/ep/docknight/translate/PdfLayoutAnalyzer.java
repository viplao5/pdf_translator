package com.gs.ep.docknight.translate;

import com.gs.ep.docknight.model.Element;
import com.gs.ep.docknight.model.ElementGroup;
import com.gs.ep.docknight.model.ElementList;
import com.gs.ep.docknight.model.PositionalContext;
import com.gs.ep.docknight.model.RectangleProperties;
import com.gs.ep.docknight.model.TabularCellElementGroup;
import com.gs.ep.docknight.model.TabularElementGroup;
import com.gs.ep.docknight.model.attribute.Content;
import com.gs.ep.docknight.model.attribute.FontSize;
import com.gs.ep.docknight.model.attribute.Height;
import com.gs.ep.docknight.model.attribute.Left;
import com.gs.ep.docknight.model.attribute.PositionalContent;
import com.gs.ep.docknight.model.attribute.Text;
import com.gs.ep.docknight.model.attribute.TextStyles;
import com.gs.ep.docknight.model.attribute.Top;
import com.gs.ep.docknight.model.attribute.Width;
import com.gs.ep.docknight.model.element.Page;
import com.gs.ep.docknight.model.element.TextElement;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.factory.Sets;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Encapsulates the logic for analyzing page layout, identifying columns,
 * and consolidating text blocks into paragraphs.
 */
public class PdfLayoutAnalyzer {

    public List<LayoutEntity> analyzePage(Page page) {
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

        return consolidated;
    }

    public boolean detectMultiColumn(List<LayoutEntity> blocks, double pageWidth, double pageHeight) {
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

    public int getReadingArea(LayoutEntity entity, boolean multiColumn) {
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

    public String getBlockText(LayoutEntity e) {
        if (e.getCachedText() != null) {
            return e.getCachedText();
        }

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
        String result = sb.toString().trim();
        e.setCachedText(result);
        return result;
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
                || textA.matches("^SECTION\\s+\\d+.*") || textA.matches("^GLOSSARY.*")
                || textA.matches("^REFERENCES.*");
        boolean aEndsNearRightEdge = a.right > a.pageWidth * 0.85;
        boolean bHasLeaderDots = textB.contains("....") || textB.contains("…");
        boolean bStartsWithSectionNumber = textB.matches("^\\d+\\.\\d*\\.?\\s+.*")
                || textB.matches("^[A-Z]\\.\\d+\\.?\\s+.*")
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
        // "amortization. The process of allocating..."
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
        boolean bIsMoreIndented = b.left > a.left + 5; // b 比 a 缩进更多
        boolean bIsLessIndented = b.left < a.left - 5; // b 比 a 缩进更少（续行特征）
        boolean sameIndent = Math.abs(a.left - b.left) <= 5; // 相同缩进

        // 检测左侧位置差异过大（如右对齐页眉 vs 居中标题）
        // 如果 b 的左侧比 a 的左侧向左偏移超过 100pt，这通常不是续行
        boolean largeLeftShift = a.left - b.left > 100;

        // 垂直间距阈值（基于行高估算）
        double estimatedLineHeight = 12.0;
        if (firstA != null && firstA.hasAttribute(FontSize.class)) {
            estimatedLineHeight = firstA.getAttribute(FontSize.class).getValue().getMagnitude() * 1.4;
        }
        boolean tightVerticalGap = vGap < estimatedLineHeight * 0.6; // 紧密的行间距（收紧）
        boolean veryTightGap = vGap < 5; // 非常紧密的间距

        // 情况 A: 续行检测 - 上一行填满 + 下一行缩进更少 + 紧密间距
        // 这是最典型的段落内换行：(3) Are responsible for... [填满]
        // government property... [从左边开始]
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
                double lastLeft = lastElem.hasAttribute(Left.class) ? lastElem.getAttribute(Left.class).getMagnitude()
                        : 0;
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
                if (bottom > lastBottom)
                    lastBottom = bottom;
            } else if (elem.hasAttribute(Top.class)) {
                if (elemTop > lastBottom)
                    lastBottom = elemTop + 10; // 估计高度
            }
        }

        // 添加最后一组
        if (!currentGroup.isEmpty()) {
            result.add(new ElementGroup<>(currentGroup));
        }

        return result;
    }

    private double getElementFontSize(Element elem) {
        if (elem.hasAttribute(FontSize.class)) {
            return elem.getAttribute(FontSize.class).getMagnitude();
        }
        return 0;
    }

    private boolean isItalic(Element elem) {
        if (elem.hasAttribute(TextStyles.class)) {
            List<String> styles = elem.getAttribute(TextStyles.class).getValue();
            return styles != null && styles.contains(TextStyles.ITALIC);
        }
        return false;
    }

    private boolean isListItemStart(String text) {
        if (text == null || text.isEmpty())
            return false;

        // 检测括号标记，但要排除缩写
        if ((text.startsWith("(") || text.startsWith("（")) && text.length() >= 3) {
            int closeIdx = text.indexOf(')');
            if (closeIdx == -1)
                closeIdx = text.indexOf('）');
            if (closeIdx > 1 && closeIdx < 10) {
                String inside = text.substring(1, closeIdx);
                // 纯数字 (1), (10), (99)
                if (inside.matches("\\d{1,3}"))
                    return true;
                // 单个字母 (a), (b), (A), (B)
                if (inside.matches("[a-zA-Z]"))
                    return true;
                // 罗马数字 (i), (ii), (iii), (iv), (v), (vi), (vii), (viii), (ix), (x), (xi), (xii)
                if (inside.matches("(?i)^(i{1,3}|iv|v|vi{0,3}|ix|x|xi{0,2})$"))
                    return true;
                // 数字+字母组合 (1a), (2b)
                if (inside.matches("\\d+[a-zA-Z]"))
                    return true;
                // 排除：两个或更多大写字母的缩写 (IT), (IUS), (DoD), (CFO)
                // 这些不是列表标记，不要在这里返回 true
            }
        }

        // 匹配 a., b., 1., 2. 等点号列表标记
        if (text.matches("^[a-zA-Z0-9][\\.\\.、].*"))
            return true;
        // 匹配 • - * 等符号列表标记
        if (text.startsWith("•") || text.startsWith("-") || text.startsWith("*"))
            return true;
        return false;
    }

    private boolean isGlossaryEntry(String text) {
        if (text == null || text.length() < 3)
            return false;

        // 模式1：以大写缩写开头（2-15个字符，包含括号、斜杠等），后跟空格和任意文字
        if (text.matches("^[A-Z][A-Z0-9\\.\\(\\)&/]{0,14}\\s+[A-Za-z].*"))
            return true;

        // 模式2：以小写+大写混合缩写开头，后跟空格（如 iRAPT, DoDI, DoDM）
        if (text.matches("^[a-zA-Z]{2,10}\\s+[A-Z][a-z].*"))
            return true;

        return false;
    }

    private boolean isGlossaryEntryStart(String text) {
        if (text == null || text.length() < 3)
            return false;

        // 获取第一个"单词"（空格前的部分）
        String[] parts = text.split("\\s+", 2);
        if (parts.length < 2)
            return false; // 需要有缩写和定义

        String abbr = parts[0];
        String definition = parts[1];

        // 定义部分需要以字母开头（排除纯数字等情况）
        if (!definition.matches("^[A-Za-z].*"))
            return false;

        // 纯大写缩写 (2-15字符，包含可能的括号和斜杠)
        if (abbr.matches("^[A-Z][A-Z0-9\\.\\(\\)&/]{0,14}$")) {
            return true;
        }

        // 混合大小写缩写 (含至少一个大写，2-12字符)
        if (abbr.matches("^[A-Za-z][A-Za-z0-9\\-]{1,11}$") && abbr.matches(".*[A-Z].*")) {
            return true;
        }

        // 带括号/斜杠的缩写 USD(A&S), USD(C)/CFO
        if (abbr.matches("^[A-Z]{2,6}\\([A-Za-z&/]+\\)(/[A-Z]+)?$")) {
            return true;
        }

        return false;
    }

    private boolean isReferenceEntry(String text) {
        if (text == null || text.length() < 10)
            return false;

        // 常见的参考文献开头模式
        String[] referencePatterns = {
                "^ASTM\\s+", // ASTM International...
                "^DoD\\s+Directive", // DoD Directive 5134.01...
                "^DoD\\s+Instruction", // DoD Instruction 4140.01...
                "^DoD\\s+Manual", // DoD Manual 4100.39...
                "^DoD\\s+\\d", // DoD 5220.22-M, DoD 7000.14-R...
                "^Defense\\s+Federal", // Defense Federal Acquisition...
                "^Deputy\\s+Secretary", // Deputy Secretary of Defense...
                "^Section\\s+\\d", // Section 503 of Title 40...
                "^Title\\s+\\d", // Title 40, United States Code
                "^Public\\s+Law", // Public Law 115-91...
                "^Executive\\s+Order", // Executive Order 13514...
                "^OMB\\s+", // OMB Circular A-123...
                "^\\d+\\s+U\\.?S\\.?C", // 10 U.S.C., 31 USC...
        };

        for (String pattern : referencePatterns) {
            if (text.matches("(?i)" + pattern + ".*"))
                return true;
        }

        return false;
    }

    private boolean isDefinitionEntry(String text) {
        if (text == null || text.length() < 5)
            return false;

        // 模式1: 小写开头的术语 + . + 定义文字
        if (text.matches("^[a-z][a-z\\s\\-]+\\.\\s{1,3}[A-Z].*"))
            return true;

        // 模式2: 带有专有名词的术语（如 "capitalized IUS"）
        if (text.matches("^[a-z][a-z\\s\\-]*[A-Z]+[a-zA-Z]*\\.\\s{1,3}[A-Z].*"))
            return true;

        // 模式3: 大写缩写开头但后面是小写描述词的术语
        if (text.matches("^[A-Z]{2,6}\\.\\s{1,3}(Defined|See|As defined).*"))
            return true;

        // 模式4: 专有名词开头的定义（如机构名称）
        if (text.matches("^[A-Z][a-zA-Z\\s]+\\.\\s{1,3}(The|A|An|See|As|Services).*"))
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

        // Pattern: (xxx) or （xxx） - 括号包围的字母/数字（支持多字符如 (10), (12)）
        // 支持中英文括号
        if ((first == '(' || first == '（') && text.length() >= 3) {
            int closeIdx = text.indexOf(')');
            if (closeIdx == -1)
                closeIdx = text.indexOf('）');
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

        return new LayoutEntity(new ElementGroup<>(allElems), a.pageWidth, a.pageHeight);
    }
}
