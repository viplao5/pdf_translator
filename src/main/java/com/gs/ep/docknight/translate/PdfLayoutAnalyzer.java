package com.gs.ep.docknight.translate;

import com.gs.ep.docknight.model.Element;
import com.gs.ep.docknight.model.ElementGroup;
import com.gs.ep.docknight.model.ElementList;
import com.gs.ep.docknight.model.PositionalContext;
import com.gs.ep.docknight.model.RectangleProperties;
import com.gs.ep.docknight.model.TabularCellElementGroup;
import com.gs.ep.docknight.model.TabularElementGroup;
import com.gs.ep.docknight.model.attribute.Color;
import com.gs.ep.docknight.model.attribute.Content;
import com.gs.ep.docknight.model.attribute.FontSize;
import com.gs.ep.docknight.model.attribute.Height;
import com.gs.ep.docknight.model.attribute.Left;
import com.gs.ep.docknight.model.attribute.PositionalContent;
import com.gs.ep.docknight.model.attribute.Text;
import com.gs.ep.docknight.model.attribute.TextStyles;
import com.gs.ep.docknight.model.attribute.Top;
import com.gs.ep.docknight.model.attribute.Width;
import com.gs.ep.docknight.model.element.Image;
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
 * 
 * <p>
 * 现在支持基于策略模式的页面分析。可以使用 {@link #analyzePageWithStrategy(Page)}
 * 方法自动检测页面类型并应用相应的处理策略。
 * </p>
 * 
 * <p>
 * 可用的策略类型：
 * </p>
 * <ul>
 * <li>{@link PageType#SINGLE_COLUMN} - 单栏布局</li>
 * <li>{@link PageType#MULTI_COLUMN} - 多栏布局</li>
 * <li>{@link PageType#TABLE_DOMINANT} - 表格主导布局</li>
 * </ul>
 * 
 * @see PageLayoutStrategy
 * @see PageLayoutStrategyFactory
 */
public class PdfLayoutAnalyzer {

    // 策略工厂实例
    private final PageLayoutStrategyFactory strategyFactory;

    // 用于在 consolidation 过程中防止跨栏合并的预检测结果
    private boolean preDetectedMultiColumn = false;
    private double currentPageWidth = 0;

    // 页面的最大右边界（用于约束合并判断和翻译输出）
    private double maxPageRightBoundary = 0;

    public PdfLayoutAnalyzer() {
        this.strategyFactory = new PageLayoutStrategyFactory();
    }

    /**
     * 使用策略模式分析页面（推荐方式）
     *
     * 自动检测页面类型并应用相应的处理策略。
     *
     * @param page 要分析的页面
     * @return 提取的布局实体列表
     */
    public List<LayoutEntity> analyzePageWithStrategy(Page page) {
        double pageWidth = page.getAttribute(Width.class).getValue().getMagnitude();
        double pageHeight = page.getAttribute(Height.class).getValue().getMagnitude();

        // 计算最大右边界
        List<Element> allRaw = Lists.mutable.ofAll(page.getContainingElements(e -> true));
        this.maxPageRightBoundary = calculateMaxRightBoundary(page, allRaw);
        System.out.println("=== Max Right Boundary: " + maxPageRightBoundary + " (pageWidth=" + pageWidth + ") ===");

        PageLayoutStrategy strategy = strategyFactory.createStrategy(page);
        return strategy.analyzePage(page, pageWidth, pageHeight);
    }

    /**
     * 使用指定策略类型分析页面
     *
     * @param page     要分析的页面
     * @param pageType 指定的页面类型
     * @return 提取的布局实体列表
     */
    public List<LayoutEntity> analyzePageWithStrategy(Page page, PageType pageType) {
        double pageWidth = page.getAttribute(Width.class).getValue().getMagnitude();
        double pageHeight = page.getAttribute(Height.class).getValue().getMagnitude();

        // 计算最大右边界
        List<Element> allRaw = Lists.mutable.ofAll(page.getContainingElements(e -> true));
        this.maxPageRightBoundary = calculateMaxRightBoundary(page, allRaw);
        System.out.println("=== Max Right Boundary: " + maxPageRightBoundary + " (pageWidth=" + pageWidth + ") ===");

        PageLayoutStrategy strategy = strategyFactory.getStrategy(pageType);
        return strategy.analyzePage(page, pageWidth, pageHeight);
    }

    /**
     * 获取页面的检测类型（不执行分析）
     * 
     * @param page 要检测的页面
     * @return 检测到的页面类型
     */
    public PageType detectPageType(Page page) {
        return strategyFactory.detectPageType(page);
    }

    public List<LayoutEntity> analyzePage(Page page) {
        System.out.println("\n \n ============ Analyzing page..." + page.getName() + "===========");
        double pageWidth = page.getAttribute(Width.class).getValue().getMagnitude();
        double pageHeight = page.getAttribute(Height.class).getValue().getMagnitude();

        // 获取页面中的所有元素
        List<Element> allRaw = Lists.mutable.ofAll(page.getContainingElements(e -> true));

        // 计算页面的最大右边界（所有文本元素的最大right值）
        this.maxPageRightBoundary = calculateMaxRightBoundary(page, allRaw);
        System.out.println("=== Max Right Boundary: " + maxPageRightBoundary + " (pageWidth=" + pageWidth + ") ===");

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

        // 过滤掉图片元素 - 它们不需要布局分析，由 PdfRenderer 单独处理
        allElements.removeIf(e -> e instanceof Image);

        // 早期预检测：在处理 VerticalGroup 之前，检测是否可能是双栏布局
        // 这样可以在分组时就按列拆分
        this.currentPageWidth = pageWidth;
        this.preDetectedMultiColumn = earlyDetectMultiColumn(allElements, pageWidth, pageHeight);

        for (Element element : allElements) {
            // 跳过图片元素 - 图片不需要文本翻译，由 PdfRenderer 单独处理
            if (element instanceof Image) {
                continue;
            }

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
                    // 如果预检测到双栏布局，先按列拆分组
                    List<ElementGroup<Element>> columnSplitGroups;
                    if (preDetectedMultiColumn) {
                        columnSplitGroups = splitGroupByColumns(vGroup, pageWidth);
                    } else {
                        columnSplitGroups = Lists.mutable.of(vGroup);
                    }

                    // 然后对每个列组进行列表项拆分
                    for (ElementGroup<Element> colGroup : columnSplitGroups) {
                        List<ElementGroup<Element>> splitGroups = splitGroupByListItems(colGroup);
                        for (ElementGroup<Element> g : splitGroups) {
                            entities.add(new LayoutEntity(g, pageWidth, pageHeight));
                        }
                    }
                }
            } else if (element instanceof TextElement || element.hasAttribute(Text.class)) {
                entities.add(new LayoutEntity(new ElementGroup<>(Lists.mutable.of(element)), pageWidth, pageHeight));
            }
        }

        // Initial sort by top to help greedy consolidation
        entities.sort((a, b) -> Double.compare(a.top, b.top));

        // 预检测双栏布局：在 consolidation 之前，使用原始块列表进行检测
        // 这样可以在合并过程中阻止跨栏合并
        this.currentPageWidth = pageWidth;
        this.preDetectedMultiColumn = preDetectMultiColumn(entities, pageWidth, pageHeight);

        // 2. Greedy spatial consolidation across the entire page
        List<LayoutEntity> consolidated = consolidateBlocks(entities);

        // 3. Assign reading areas and sort
        boolean multiColumn = detectMultiColumn(consolidated, pageWidth, pageHeight);

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

    /**
     * 早期预检测：基于原始元素列表检测双栏布局
     * 在处理 VerticalGroup 之前调用
     */
    private boolean earlyDetectMultiColumn(List<Element> elements, double pageWidth, double pageHeight) {
        int leftElements = 0;
        int rightElements = 0;

        for (Element elem : elements) {
            if (!elem.hasAttribute(Left.class) || !elem.hasAttribute(Top.class))
                continue;

            double left = elem.getAttribute(Left.class).getMagnitude();
            double top = elem.getAttribute(Top.class).getMagnitude();

            // 忽略页眉页脚区域
            if (top < pageHeight * 0.08 || top > pageHeight * 0.92)
                continue;

            double centerX = left;
            if (elem.hasAttribute(Width.class)) {
                centerX = left + elem.getAttribute(Width.class).getMagnitude() / 2.0;
            }

            if (centerX < pageWidth * 0.45) {
                leftElements++;
            } else if (centerX > pageWidth * 0.55) {
                rightElements++;
            }
        }

        // 如果左右两边都有至少 3 个元素，很可能是双栏
        return leftElements >= 3 && rightElements >= 3;
    }

    /**
     * 按列拆分 VerticalGroup：将同一组中位于不同列的元素拆分
     * 用于处理 PDF 解析器错误地将左右栏内容分组到同一个 VerticalGroup 的情况
     */
    private List<ElementGroup<Element>> splitGroupByColumns(ElementGroup<Element> group, double pageWidth) {
        MutableList<Element> elements = group.getElements();
        if (elements.size() <= 1) {
            return Lists.mutable.of(group);
        }

        // 按水平位置分成左栏和右栏
        MutableList<Element> leftElements = Lists.mutable.empty();
        MutableList<Element> rightElements = Lists.mutable.empty();
        double columnBoundary = pageWidth * 0.5; // 使用页面中点作为列边界

        for (Element elem : elements) {
            if (!elem.hasAttribute(Left.class)) {
                leftElements.add(elem); // 没有位置信息的元素放到左栏
                continue;
            }

            double left = elem.getAttribute(Left.class).getMagnitude();

            // Fix: Use Left position instead of CenterX.
            // Wide indented blocks (like References) might have CenterX > boundary
            // but they start on the left and should belong to the left column.
            if (left < columnBoundary) {
                leftElements.add(elem);
            } else {
                rightElements.add(elem);
            }
        }

        List<ElementGroup<Element>> result = new ArrayList<>();

        // 如果所有元素都在同一侧，返回原始组
        if (leftElements.isEmpty()) {
            result.add(new ElementGroup<>(rightElements));
        } else if (rightElements.isEmpty()) {
            result.add(new ElementGroup<>(leftElements));
        } else {
            // 有跨栏元素，需要拆分
            // 按 top 排序各组
            leftElements.sortThis((e1, e2) -> Double.compare(
                    e1.getAttribute(Top.class).getMagnitude(),
                    e2.getAttribute(Top.class).getMagnitude()));
            rightElements.sortThis((e1, e2) -> Double.compare(
                    e1.getAttribute(Top.class).getMagnitude(),
                    e2.getAttribute(Top.class).getMagnitude()));

            result.add(new ElementGroup<>(leftElements));
            result.add(new ElementGroup<>(rightElements));
        }

        return result;
    }

    /**
     * 预检测双栏布局：在 consolidation 之前使用原始块列表
     * 更激进的检测，用于指导 shouldMerge 的行为
     */
    private boolean preDetectMultiColumn(List<LayoutEntity> blocks, double pageWidth, double pageHeight) {
        // 统计左半边和右半边的块数量
        int leftBlocks = 0;
        int rightBlocks = 0;
        int parallelPairings = 0;

        for (LayoutEntity block : blocks) {
            // 忽略页眉页脚区域
            if (block.top < pageHeight * 0.08 || block.bottom > pageHeight * 0.92)
                continue;
            // 忽略跨越整个页面的宽块
            if ((block.right - block.left) > pageWidth * 0.65)
                continue;

            double centerX = (block.left + block.right) / 2.0;
            if (centerX < pageWidth * 0.45) {
                leftBlocks++;
            } else if (centerX > pageWidth * 0.55) {
                rightBlocks++;
            }
        }

        // 如果左右两边都有至少 2 个块，很可能是双栏
        if (leftBlocks >= 2 && rightBlocks >= 2) {
            return true;
        }

        // 检查平行块对
        for (int i = 0; i < blocks.size(); i++) {
            LayoutEntity a = blocks.get(i);
            if (a.top < pageHeight * 0.1 && (a.bottom - a.top) < 30)
                continue;

            for (int j = i + 1; j < blocks.size(); j++) {
                LayoutEntity b = blocks.get(j);
                if (b.top < pageHeight * 0.1 && (b.bottom - b.top) < 30)
                    continue;

                // 检查窄块对（宽度 < 55%）
                if ((a.right - a.left) < pageWidth * 0.55 && (b.right - b.left) < pageWidth * 0.55) {
                    double overlap = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
                    double hGap = Math.abs((a.left + a.right) / 2.0 - (b.left + b.right) / 2.0);
                    // 明显的水平分离和垂直重叠
                    if (overlap > 5 && hGap > pageWidth * 0.30) {
                        parallelPairings++;
                    }
                }
            }
        }

        return parallelPairings >= 1;
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

        // Gutter spanning check:
        // If a block clearly starts in the left half and ends in the right half,
        // it spans the column gap and should be treated as a full-width block (Area 0).
        if (entity.left < entity.pageWidth * 0.45 && entity.right > entity.pageWidth * 0.55) {
            return 0;
        }

        // Left-margin start check:
        // If a block starts near the left margin (within first 25% of page),
        // it's likely main content that flows left-to-right, not a right column.
        // This handles indented lists like References that start at left but extend
        // right.
        if (entity.left < entity.pageWidth * 0.25) {
            return 0;
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
        // 居中块检测条件放宽：
        // 条件1: 左右边距差异小于 15pt，且满足以下任一条件：
        // a) 左边距大于 100pt（明显居中）
        // b) 内容较短（< 40% 页宽，适合短标题）
        // c) 左右边距都大于 60pt 且差异很小（< 5pt），适合宽标题
        boolean verySymmetric = Math.abs(leftMargin - rightMargin) < 5 && leftMargin > 60 && rightMargin > 60;
        boolean isCenteredBlock = Math.abs(leftMargin - rightMargin) < 15
                && (leftMargin > 100 || blockWidth < e.pageWidth * 0.4 || verySymmetric);

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
        int iteration = 0;
        do {
            merged = false;
            iteration++;
            System.out.printf("  [Iteration %d] Checking %d blocks for merge...%n", iteration, current.size());

            // 检查所有块对，不只是i+1
            for (int i = 0; i < current.size(); i++) {
                for (int j = 0; j < current.size(); j++) {
                    if (i == j)
                        continue; // 不与自己比较

                    LayoutEntity a = current.get(i);
                    LayoutEntity b = current.get(j);

                    // 确保a在b上方（处理顺序）
                    if (a.top > b.top)
                        continue;

                    if (shouldMerge(a, b)) {
                        // 诊断日志：记录合并操作
                        String textA = getBlockText(a);
                        String textB = getBlockText(b);
                        String shortA = (textA.length() > 25 ? textA.substring(0, 25) : textA).replace("\n", "↵");
                        String shortB = (textB.length() > 25 ? textB.substring(0, 25) : textB).replace("\n", "↵");
                        System.out.printf("  MERGE: [%s] + [%s]%n", shortA, shortB);

                        // 合并后，保留上方的块（a），删除下方的块（b）
                        LayoutEntity mergedEntity = merge(a, b);
                        int removeIndex = (i < j) ? j : i;
                        int setIndex = (i < j) ? i : j;
                        current.set(setIndex, mergedEntity);
                        current.remove(removeIndex);
                        merged = true;
                        break; // 合并后重新开始
                    }
                }
                if (merged)
                    break;
            }
        } while (merged);
        return current;
    }

    boolean shouldMerge(LayoutEntity a, LayoutEntity b) {
        if (a.isTable || b.isTable)
            return false;

        double vGap = Math.max(0, Math.max(b.top - a.bottom, a.top - b.bottom));
        double hOverlap = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        double minWidth = Math.min(a.right - a.left, b.right - b.left);

        // === 1. EARLY URL CONTINUATION DETECTION（最高优先级）===
        // 必须在所有其他检查之前，包括段落分隔检测和跨栏检查
        // 原因：URL拆分的情况即使"有空间但没放"也应该合并，否则会被段落分隔规则错误阻止
        // 同时，URL续行即使跨越了双栏中线（可能是由于解析误差）也应该合并
        String textA_trim = getBlockText(a).trim();
        String textB_trim = getBlockText(b).trim();

        // 获取A的末尾部分（最后30个字符）和B的开头部分（前20个字符）
        String aTail = textA_trim.length() > 30 ? textA_trim.substring(textA_trim.length() - 30) : textA_trim;
        String bHead = textB_trim.length() > 20 ? textB_trim.substring(0, 20) : textB_trim;

        // 检测1: A包含URL/路径部分
        boolean aContainsUrlPart = textA_trim.matches(".*(?:https?://|www\\.|ftp://)[^\\s]{5,}.*");

        // 检测2: A末尾包含URL域名或路径特征
        boolean aContainsDotMil = aTail.contains(".mil/");
        boolean aContainsDotCom = aTail.contains(".com/");
        boolean aContainsDotGov = aTail.contains(".gov/");
        boolean aContainsDotOrg = aTail.contains(".org/");
        boolean aContainsDotNet = aTail.contains(".net/");
        boolean aContainsAcqOsd = aTail.contains("acq.osd.mil");
        boolean aContainsDodToolbox = aTail.contains("dodprocurementtoolbox.com");
        boolean aContainsDla = aTail.contains("dla.mil");
        boolean aContainsLogSci = aTail.contains("/log/sci/");
        boolean aContainsDownloads = aTail.contains("/downloads/");
        boolean aContainsHttp = aTail.contains("http://");
        boolean aContainsWww = aTail.contains("www.");

        boolean aEndsWithUrlDomain = aContainsDotMil || aContainsDotCom || aContainsDotGov ||
                aContainsDotOrg || aContainsDotNet || aContainsAcqOsd ||
                aContainsDodToolbox || aContainsDla;
        boolean aEndsWithPath = aContainsLogSci || aContainsDownloads ||
                aContainsHttp || aContainsWww;
        boolean aEndsWithUrlPart = aEndsWithUrlDomain || aEndsWithPath;

        // 检测3: A末尾没有URL结束标记（句号、逗号、分号等）
        boolean aEndsWithPeriod = aTail.endsWith(".");
        boolean aEndsWithComma = aTail.endsWith(",");
        boolean aEndsWithSemicolon = aTail.endsWith(";");
        boolean aEndsWithSentenceEnd = aEndsWithPeriod || aEndsWithComma ||
                aEndsWithSemicolon || aTail.endsWith(" ");

        // 关键：只要包含URL部分且不以句子结束标记结尾，就认为未正确结束
        boolean aUrlNotProperlyEnded = !aEndsWithSentenceEnd && aContainsUrlPart;

        // 检测4: B开头是URL的扩展
        String bHeadTrimmed = bHead.trim();
        boolean bStartsWithHtml = bHeadTrimmed.matches("^(?:html?|\\.[a-z]{3,4}).*");
        boolean bStartsWithHtmlDot = bHeadTrimmed.matches("^(?:html?\\.?).*");
        boolean bStartsWithPathSlash = bHeadTrimmed.matches("^(?:/|downloads/|packag|uidtools).*");
        boolean bStartsWithSlashDot = bHeadTrimmed.matches("^(?:/\\.).*");
        boolean bIsUrlExtension = bStartsWithHtml || bStartsWithHtmlDot || bStartsWithPathSlash || bStartsWithSlashDot;

        // 检测5: 特殊情况 - A末尾是句号结尾的URL域名，B是"html"
        boolean aEndsWithDotAfterUrl = aTail.matches(".*(?:mil|com|gov|org|net)\\.$") && aContainsUrlPart;

        // 检测6: B开头是URL协议或域名
        boolean bStartsWithUrlProtocol = bHeadTrimmed.matches("^(?:https?://|www\\.|ftp://).*");

        // 综合判断：A包含URL部分且未正确结束，B是URL的后续
        // 改进：如果B是URL协议开始，或者B是URL扩展且A结束于可能的分离点，则认为是续行
        boolean isUrlContinuation = (aContainsUrlPart || aEndsWithUrlPart
                || aTail.matches(".*(?:at|to|from|index\\.)$")) &&
                (aUrlNotProperlyEnded || aEndsWithDotAfterUrl || bIsUrlExtension || bStartsWithUrlProtocol) &&
                (bIsUrlExtension || bStartsWithUrlProtocol);

        // === 改进：URL续行检测强化 ===
        // 如果 A 看起来像未完成的 URL（以 / 或 - 结尾），且 B 看起来像 URL 的一部分
        boolean aEndsWithSlashOrHyphen = aTail.matches(".*[/-]$") && aContainsUrlPart;
        // B 开始为大写字母时通常不是 URL 续行，除非它是明确的 URL 路径
        boolean bStartsWithSentenceCap = bHeadTrimmed.matches("^[A-Z][a-z].*");
        if (aEndsWithSlashOrHyphen && (bIsUrlExtension || bStartsWithPathSlash || !bStartsWithSentenceCap)) {
            isUrlContinuation = true;
        }

        // 如果 A 不包含 URL 但 B 是极其显著的 URL 扩展（如 .html），在间距很小时也认为是续行
        if (!isUrlContinuation && bIsUrlExtension && vGap < 10) {
            isUrlContinuation = true;
        }

        // 获取组中的第一个元素，用于字体大小检测
        Element earlyFirstA = a.group instanceof ElementGroup ? ((ElementGroup<Element>) a.group).getFirst() : null;

        // 如果是URL续行，直接返回true，不进行其他任何检查
        // 使用更宽松的垂直间距检测（1.5倍行高）
        double earlyEstimatedLineHeight = 12.0;
        double earlyFontSize = 10.0;
        if (earlyFirstA != null && earlyFirstA.hasAttribute(FontSize.class)) {
            earlyFontSize = earlyFirstA.getAttribute(FontSize.class).getValue().getMagnitude();
            earlyEstimatedLineHeight = earlyFontSize * 1.4;
        }

        if (isUrlContinuation && vGap < earlyEstimatedLineHeight * 1.5) {
            System.out.printf("   -> EARLY MERGE: URL/path continuation detected (vGap=%.1f < %.1f)%n",
                    vGap, earlyEstimatedLineHeight * 1.5);
            return true;
        }

        // === 关键：跨栏合并早期阻止 ===
        // ... (keep existing column logic)
        if (preDetectedMultiColumn) {
            double columnBoundary = a.pageWidth * 0.5; // 列边界
            double leftColumnMax = a.pageWidth * 0.48; // 左栏最大中心点

            double aCenterX = (a.left + a.right) / 2.0;
            double bCenterX = (b.left + b.right) / 2.0;

            // 计算块宽度
            double aWidth = a.right - a.left;
            double bWidth = b.right - b.left;

            // 关键修复：宽块（跨越页面大部分宽度）不应被视为"跨栏"
            // 这是单栏布局中的正常段落，即使它跨越了页面中线
            boolean aIsWideBlock = aWidth > a.pageWidth * 0.55;
            boolean bIsWideBlock = bWidth > b.pageWidth * 0.55;

            // 检查块是否已经跨栏（右边界超过列边界但左边界在左栏）
            // 但排除宽块，因为宽块是单栏布局的正常段落
            boolean aIsCrossColumn = a.left < columnBoundary && a.right > columnBoundary && !aIsWideBlock;
            boolean bIsCrossColumn = b.left < columnBoundary && b.right > columnBoundary && !bIsWideBlock;

            // 如果任一块是真正的跨栏块（窄块跨越中线），不允许进一步合并
            if (aIsCrossColumn || bIsCrossColumn) {
                return false;
            }

            // 如果两个块都是宽块，允许正常合并（它们是单栏内容）
            if (aIsWideBlock && bIsWideBlock) {
                // 不阻止合并，继续后续检查
            } else if (aIsWideBlock || bIsWideBlock) {
                // 一个是宽块，一个不是：检查是否在同一垂直区域
                // 宽块可以与其下方/上方紧邻的窄块合并（如标题与正文）
                if (vGap > 15) {
                    // 垂直间距较大，可能不是同一段落
                    // 但不在这里阻止，让后续逻辑决定
                }
            } else {
                // 两个都是窄块，执行原有的跨栏检查
                // 检查是否位于不同的半边
                boolean aInLeftHalf = aCenterX < leftColumnMax;
                boolean bInLeftHalf = bCenterX < leftColumnMax;

                // 额外检查：如果块的边界跨越了列边界（但中心点没有），也阻止合并
                boolean aReachesRight = a.right > columnBoundary;
                boolean bReachesRight = b.right > columnBoundary;

                // 如果 A 延伸到右半边，B 在左半边开始，可能是跨栏情况
                if ((aReachesRight && bInLeftHalf) || (bReachesRight && aInLeftHalf)) {
                    if (Math.abs(a.top - b.top) < 5) {
                        return false;
                    }
                }
            }
        }

        // 计算页面右边距（假设标准边距约 54-72pt）

        // === 2. SAME LINE: Bullet + Content ===
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

        // === 2.3 PREVENT MERGING: 新章节/节标题开始 ===
        // 检查 B 是否是新的章节标题（如 "SECTION 2:", "GLOSSARY", "REFERENCES"）
        String textB_early = getBlockText(b).trim();
        boolean bIsNewSectionTitle = textB_early.matches("(?i)^SECTION\\s+\\d+.*")
                || textB_early.matches("(?i)^GLOSSARY.*")
                || textB_early.matches("(?i)^REFERENCES.*")
                || textB_early.matches("(?i)^APPENDIX.*")
                || textB_early.matches("(?i)^TABLE OF CONTENTS.*")
                || textB_early.matches("(?i)^INDEX.*");
        if (bIsNewSectionTitle)
            return false;

        // === 2.4 PREVENT MERGING: 颜色不同 ===
        // 不同颜色的文本通常不属于同一段落（如蓝色标题 vs 黑色正文）
        Element firstElemA = ((ElementGroup<Element>) a.group).getFirst();
        Element firstElemB = ((ElementGroup<Element>) b.group).getFirst();
        if (firstElemA != null && firstElemB != null) {
            if (firstElemA.hasAttribute(Color.class) && firstElemB.hasAttribute(Color.class)) {
                java.awt.Color colorA = firstElemA.getAttribute(Color.class).getValue();
                java.awt.Color colorB = firstElemB.getAttribute(Color.class).getValue();
                if (colorA != null && colorB != null && !colorA.equals(colorB)) {
                    // 颜色不同，不合并
                    return false;
                }
            }
        }

        // === 2.45 PREVENT MERGING: B 是新的参考文献条目 ===
        // 参考文献检测必须在自然续行检测之前，否则会被错误合并
        // 检查 B 是否以参考文献开头模式开始，且 B 从左边距开始（不是缩进的续行）
        String earlyTextB = getBlockText(b).trim();
        boolean bStartsFromLeftMargin = b.left < 80; // 从左边距开始
        boolean bIsIndentedContinuation = b.left > a.left + 10; // 明显缩进的续行

        if (bStartsFromLeftMargin && !bIsIndentedContinuation && isReferenceEntry(earlyTextB)) {
            // B 是新的参考文献条目（从左边距开始），不应与 A 合并
            return false;
        }

        // === 2.5 自然段落换行检测（优先于 Area 检查）===
        // 关键原则：自然续行应该合并，即使 Area 不同
        // earlyFirstA 和 earlyFirstB 已在上面定义

        // 计算上一行是否"填满"
        // boolean earlyALineIsFull = a.right > a.pageWidth * 0.80; // Removed as unused
        boolean earlyAIsRightAligned = a.right > a.pageWidth * 0.85 && a.left > a.pageWidth * 0.35;
        boolean earlySameIndent = Math.abs(a.left - b.left) <= 5;
        boolean earlyBIsLessIndented = b.left < a.left - 5;
        boolean earlyLargeLeftShift = a.left - b.left > 100;

        // 关键规则：检测"有空间但没放"的情况
        // 如果上一行末尾还有足够空间容纳下一行的首单词，但却另起一行，说明是新段落
        // 使用 A 块的 lastLineRight（最后一行的右边界）而不是整个块的 right
        // 估算首单词宽度：取 B 的第一个单词长度 * 字符平均宽度（约 0.6 * 字体大小）
        String textBForCheck = getBlockText(b).trim();
        int firstWordLength = 0;
        for (int i = 0; i < textBForCheck.length() && i < 20; i++) {
            char c = textBForCheck.charAt(i);
            if (Character.isWhitespace(c))
                break;
            firstWordLength++;
        }
        double estimatedFirstWordWidth = firstWordLength * earlyFontSize * 0.6;

        // 计算有效右边界（Effective Right Edge）
        // 取 A 和 B 中较宽的那个作为参考右边界
        // 这样可以自适应处理缩进块（如 "(Copies of ...)"）
        double effectiveRightEdge = Math.max(a.right, b.right);

        // 关键修复：对于明显的缩进块，使用更精确的有效右边界
        // 检查是否是缩进块（从页面中间偏右位置开始）
        boolean aIsIndented = a.left > a.pageWidth * 0.3;
        boolean bIsIndented = b.left > b.pageWidth * 0.3;

        // 检查是否是悬挂缩进格式（第一行靠左，后续行缩进）
        // 通过比较 firstLineLeft 和 left 来判断
        boolean aIsHangingIndent = aIsIndented && a.firstLineLeft < a.left - 10;
        boolean bIsHangingIndent = bIsIndented && b.firstLineLeft < b.left - 10;

        // 诊断：输出块的详细信息（简化版，只输出位置和文本）
        if (vGap < 20) { // 只输出相邻的块对
            String textA = getBlockText(a);
            String textB = getBlockText(b);
            String shortTextA = textA.length() > 80 ? textA.substring(0, 80) + "..." : textA;
            String shortTextB = textB.length() > 80 ? textB.substring(0, 80) + "..." : textB;
            
            // System.out.printf("BLOCK PAIR: vGap=%.1f%n", vGap);
            // System.out.printf("  A: L=%.1f R=%.1f T=%.1f B=%.1f W=%.1f H=%.1f | Text='%s'%n",
            //         a.left, a.right, a.top, a.bottom, a.right - a.left, a.bottom - a.top, 
            //         shortTextA.replace("\n", "↵").replace("\r", ""));
            // System.out.printf("  B: L=%.1f R=%.1f T=%.1f B=%.1f W=%.1f H=%.1f | Text='%s'%n",
            //         b.left, b.right, b.top, b.bottom, b.right - b.left, b.bottom - b.top, 
            //         shortTextB.replace("\n", "↵").replace("\r", ""));
        }

        // 如果两个块都是从缩进位置开始（如多行缩进段落），
        // 且它们的右边界都接近或超过最大右边界，
        // 则使用实际的最大右边界作为参考，而不是 Math.max(a.right, b.right)
        if (aIsIndented && bIsIndented) {
            // 检查块的右边界是否已经接近最大右边界（相差<10pt）
            boolean aReachesMaxEdge = Math.abs(a.right - this.maxPageRightBoundary) < 10;
            boolean bReachesMaxEdge = Math.abs(b.right - this.maxPageRightBoundary) < 10;

            if (aReachesMaxEdge || bReachesMaxEdge) {
                // 使用最大右边界作为参考，因为块已经延伸到了文本区域的右边缘
                effectiveRightEdge = this.maxPageRightBoundary;
            }
        }

        // 如果两者都很短（小于页面宽度的 40%），强制扩展参考边界以避免过度合并列表项
        // 但约束在最大右边界内
        if (effectiveRightEdge < a.pageWidth * 0.4) {
            effectiveRightEdge = Math.max(effectiveRightEdge, Math.min(a.pageWidth * 0.5, this.maxPageRightBoundary));
        }

        // 确保有效右边界不超过页面的最大右边界
        effectiveRightEdge = Math.min(effectiveRightEdge, this.maxPageRightBoundary);

        // 关键修复：对于悬挂缩进或多行段落，使用块的右边界而不是lastLineRight
        // 因为 lastLineRight 只计算最后一行的右边界，而实际文本可能在前面几行就到达右边缘
        double referenceRightForA;
        if (aIsHangingIndent) {
            // 悬挂缩进：使用整个块的右边界（因为第一行是"悬挂"的）
            referenceRightForA = effectiveRightEdge;
        } else if (aIsIndented) {
            // 纯缩进块：检查块的右边界是否接近最大右边界
            if (Math.abs(a.right - this.maxPageRightBoundary) < 20) {
                // 块已经接近右边缘，使用块的右边界
                referenceRightForA = effectiveRightEdge;
            } else {
                // 块没有到达右边缘，使用lastLineRight
                referenceRightForA = a.lastLineRight;
            }
        } else {
            // 标准块：使用lastLineRight
            referenceRightForA = a.lastLineRight;
        }

        // 使用参考右边界来计算剩余空间
        double remainingSpaceInA = effectiveRightEdge - referenceRightForA;

        // 如果上一行剩余空间 > 首单词宽度 + 间距（20pt safety buffer），说明有空间但没放，是新段落
        boolean hasSpaceButNotUsed = remainingSpaceInA > estimatedFirstWordWidth + 20;

        // 关键修复：检查是否是不同缩进层级的块
        // 如果 A 和 B 的左边界差异超过阈值（如 50pt），说明它们属于不同的段落/层级
        // 即使 vGap 很小也不应该合并
        // 例如：主段落 (left=108.0) + 缩进后续行 (left=216.1) = 差异108pt
        double indentDifference = Math.abs(a.left - b.left);
        boolean isDifferentIndentLevel = indentDifference > 50; // 50pt 以上认为是不同层级

        // 特殊情况：如果是悬挂缩进格式（第一行左，后续行缩进），允许合并
        // 但需要满足：A 是主段落，B 是缩进行，且 B 的 firstLineLeft == B.left
        // 或者 B 的 right 明显小于 A 的 right（说明是后续短行）
        boolean isHangingIndentPattern = !aIsIndented && bIsIndented 
                && Math.abs(b.firstLineLeft - b.left) < 5  // B 的第一行就是缩进行
                && b.right < a.right - 30; // B 明显比 A 短

        // 关键修复：如果 A 和 B 都是缩进块（或都不是），且缩进差异在合理范围内（<120pt），
        // 则允许合并。只有当"主段落（非缩进）vs"缩进段落"时才严格拒绝。
        // 这避免了错误地阻止两个相同缩进位置的块（如都是从216.1开始）合并
        boolean bothHaveSameIndentStatus = aIsIndented == bIsIndented;
        boolean bothAreMainParagraphs = !aIsIndented && !bIsIndented;
        boolean bothAreIndented = aIsIndented && bIsIndented;

        // 拒绝条件：
        // 1. 明显不同层级（差异 > 50pt）
        // 2. 不是悬挂缩进模式
        // 3. 且（都不是主段落 OR 不都是缩进块）
        //    这意味着：一个主段落 vs 一个缩进块 = 拒绝
        //    但：两个都是主段落 OR 两个都是缩进块 = 可能允许（如果差异合理）
        boolean shouldRejectIndent = isDifferentIndentLevel && !isHangingIndentPattern;
        
        // 如果差异 > 100pt，总是拒绝（明显不同层级）
        if (indentDifference > 100) {
            shouldRejectIndent = true;
        } 
        // 如果差异在50-100pt之间，只有在"主段落 vs 缩进块"时才拒绝
        else if (indentDifference > 50) {
            if (bothHaveSameIndentStatus) {
                // 都是缩进块或都不是缩进块，允许合并（可能是同一段落的不同行）
                shouldRejectIndent = false;
            } else {
                // 一个主段落 vs 一个缩进块，拒绝
                shouldRejectIndent = true;
            }
        }

        if (shouldRejectIndent) {
            // 不输出调试信息，保持日志简洁
            return false;
        }

        // 关键：区分段落内换行 vs 段落间分隔
        boolean earlyTightVerticalGap = vGap < earlyEstimatedLineHeight * 0.5;
        boolean earlyVeryTightGap = vGap < 5;
        boolean isParagraphSeparation = vGap > earlyEstimatedLineHeight * 0.8;

        // 新段落检测：有空间但没用 且 缩进相同（不是悬挂缩进的续行）
        boolean likelyNewParagraph = hasSpaceButNotUsed && earlySameIndent;

        // 自然续行检测：只要行看起来是"填满"的（没有未使用的空间），就认为是续行
        boolean isLineWrapped = !hasSpaceButNotUsed;

        // === CRITICAL: 如果检测到新段落（有空间但没使用），且不是非常紧密的行（vGap > 2），直接阻止合并 ===
        // 这是最重要的段落分隔规则
        if (likelyNewParagraph && vGap > 2) {
            return false;
        }

        if (isLineWrapped && !earlyAIsRightAligned && earlyTightVerticalGap && !isParagraphSeparation
                && !likelyNewParagraph) {
            if (earlySameIndent || (earlyBIsLessIndented && !earlyLargeLeftShift)) {
                // 不输出调试信息
                return true; // 自然续行，即使 Area 不同也合并
            }
        }
        if (earlyVeryTightGap && earlyBIsLessIndented && !earlyLargeLeftShift && !isParagraphSeparation) {
            // 不输出调试信息
            return true; // 非常紧密的续行（悬挂缩进的续行，不受新段落检测影响）
        }

        // 检查 Area 是否相同（对于非自然续行的块）
        // 关键修复：使用预检测结果来判断 Area，而不是硬编码 false
        if (getReadingArea(a, preDetectedMultiColumn) != getReadingArea(b, preDetectedMultiColumn)) {
            // 不输出调试信息
            return false;
        }

        // === 3. SAME LINE: 水平片段合并 ===
        // 关键修复：检查水平间隙，避免跨栏合并
        // 对于双栏布局，左栏和右栏的顶部内容可能在同一水平线上，但不应合并
        double hGap = 0;
        if (a.right < b.left) {
            hGap = b.left - a.right; // A 在左，B 在右
        } else if (b.right < a.left) {
            hGap = a.left - b.right; // B 在左，A 在右
        }

        // 如果预检测到双栏布局，使用更严格的阈值
        double hGapThreshold = preDetectedMultiColumn ? 15.0 : 20.0;
        double hGapPercentThreshold = preDetectedMultiColumn ? 0.03 : 0.05;

        // 如果水平间隙超过阈值，说明可能是不同栏的内容
        boolean hasLargeHorizontalGap = hGap > hGapThreshold || hGap > a.pageWidth * hGapPercentThreshold;

        // 额外检查：如果两个块明显位于页面的不同半边，不应合并
        double aCenterX = (a.left + a.right) / 2.0;
        double bCenterX = (b.left + b.right) / 2.0;
        // 对于预检测到的双栏布局，使用更宽松的"不同半边"判断
        double leftBoundary = preDetectedMultiColumn ? 0.48 : 0.45;
        double rightBoundary = preDetectedMultiColumn ? 0.52 : 0.55;
        boolean inDifferentHalves = (aCenterX < a.pageWidth * leftBoundary && bCenterX > a.pageWidth * rightBoundary)
                || (bCenterX < a.pageWidth * leftBoundary && aCenterX > a.pageWidth * rightBoundary);

        if (vGap < 4 && hOverlap > -5 && Math.abs(a.top - b.top) < 3 && !hasLargeHorizontalGap && !inDifferentHalves)
            return true;

        // === 4. 特殊内容检测 ===
        String textA = getBlockText(a).trim();
        String textB = getBlockText(b).trim();

        // TOC 续行检测：目录条目可能跨多行
        // 特征：A 以章节编号开头（如 "2.2."）且没到达页面右边缘，B 是续行（可能带引导点号）
        boolean isTocEntryStart = textA.matches("^\\d+\\.\\d*\\.?\\s+.*") || textA.matches("^[A-Z]\\.\\d+\\.?\\s+.*")
                || textA.matches("^SECTION\\s+\\d+.*") || textA.matches("^GLOSSARY.*")
                || textA.matches("^REFERENCES.*")
                // Add FIGURE and TABLE as TOC entry starts
                || textA.matches("^(?i)FIGURE\\s+\\d+.*") || textA.matches("^(?i)TABLE\\s+\\d+.*");

        boolean aEndsNearRightEdge = a.right > a.pageWidth * 0.85;

        // Update leader dots to include dashes "----" or "____"
        boolean bHasLeaderDots = textB.contains("....") || textB.contains("…") || textB.contains("----")
                || textB.contains("____");

        boolean bStartsWithSectionNumber = textB.matches("^\\d+\\.\\d*\\.?\\s+.*")
                || textB.matches("^[A-Z]\\.\\d+\\.?\\s+.*")
                || textB.matches("^SECTION\\s+\\d+.*")
                // Add FIGURE and TABLE checking for B as well
                || textB.matches("^(?i)FIGURE\\s+\\d+.*") || textB.matches("^(?i)TABLE\\s+\\d+.*");

        // 如果 A 是目录条目开头、没到右边缘、B 有引导点号且不是新条目、间距紧密 -> 合并为同一条目
        if (isTocEntryStart && !aEndsNearRightEdge && bHasLeaderDots && !bStartsWithSectionNumber && vGap < 8) {
            return true;
        }

        // TOC: 两行都包含引导点号且都是独立条目 -> 不合并
        boolean aHasLeaderDots = textA.contains("....") || textA.contains("…") || textA.contains("----")
                || textA.contains("____");
        if (aHasLeaderDots && bHasLeaderDots)
            return false;

        // TOC protection: If both look like TOC entries (start with explicit
        // indicators), do not merge
        // This is important because they might look "centered" due to full width
        if (isTocEntryStart && bStartsWithSectionNumber)
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

        // === 5. 居中对齐检测（优先于样式检测）===
        // 居中标题可能有不同字体大小，但应该合并
        // 居中对齐的特征：左右边距相近且较大，内容宽度较窄
        double leftMarginA = a.left;
        double rightMarginA = a.pageWidth - a.right;
        double leftMarginB = b.left;
        double rightMarginB = b.pageWidth - b.right;
        double widthA = a.right - a.left;
        double widthB = b.right - b.left;

        // 居中检测条件放宽：
        // 条件1: 左右边距差异小于 15pt，且满足以下任一条件：
        // a) 左边距大于 100pt（明显居中）
        // b) 内容较短（< 40% 页宽，适合短标题）
        // c) 左右边距都大于 60pt 且差异很小（< 5pt），适合宽标题
        boolean aVerySymmetric = Math.abs(leftMarginA - rightMarginA) < 5 && leftMarginA > 60 && rightMarginA > 60;
        boolean bVerySymmetric = Math.abs(leftMarginB - rightMarginB) < 5 && leftMarginB > 60 && rightMarginB > 60;
        boolean aCentered = Math.abs(leftMarginA - rightMarginA) < 15
                && (leftMarginA > 100 || widthA < a.pageWidth * 0.4 || aVerySymmetric);
        boolean bCentered = Math.abs(leftMarginB - rightMarginB) < 15
                && (leftMarginB > 100 || widthB < b.pageWidth * 0.4 || bVerySymmetric);

        // 如果两行都是居中的，且垂直间距很小，应该合并（如多行标题）
        // 这个检测优先于样式检测，因为居中标题可能有不同字体大小
        // Guard: Do not merge if they are distinct TOC entries (even if they look
        // centered/symmetric)
        boolean areDistinctTocEntries = isTocEntryStart && bStartsWithSectionNumber;
        if (aCentered && bCentered && vGap < 15 && !areDistinctTocEntries) {
            return true;
        }

        // === 5.5 样式检测（对于非自然续行的内容）===
        // 注意：自然续行已在 Area 检查之前处理，这里仅处理剩余情况
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

        // 计算缩进关系
        boolean bIsMoreIndented = b.left > a.left + 5;
        boolean sameIndent = Math.abs(a.left - b.left) <= 5;
        boolean veryTightGap = vGap < 5;

        // b 比 a 缩进更多的情况：
        // 1. 通常表示新段落/子项，不应合并
        // 2. 但如果 A 是列表项开始（如 (a), (b)）且 B 不是新列表项，
        // 则 B 是 A 的悬挂缩进续行，应该合并
        // b 比 a 缩进更多的情况：
        // 1. 通常表示新段落/子项，不应合并
        // 2. 但如果 A 是列表项开始（如 (a), (b)）且 B 不是新列表项，
        // 则 B 是 A 的悬挂缩进续行，应该合并
        if (bIsMoreIndented) {
            boolean aIsListItem = startsWithBullet(a);
            boolean bIsNewListItem = startsWithBullet(b);

            // Check if A is a Technical Document ID (e.g. "NASA-HDBK-6003") which acts like
            // a list item header
            boolean aIsTechnicalDocID = isTechnicalDocID(textA);

            // 列表项续行条件：A是列表项或文档ID，B不是新列表项，且缩进差不超过50pt（典型悬挂缩进）
            boolean isListItemContinuation = (aIsListItem || aIsTechnicalDocID) && !bIsNewListItem
                    && (b.left - a.left) < 150;

            if (isListItemContinuation && vGap < 12) { // Relaxed vGap for titles
                // 列表项续行：直接合并（悬挂缩进格式）
                return true;
            }
            // 非列表项续行，且B更缩进，不合并
            return false;
        }

        // === 6. 保守的垂直合并 ===
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

        // 过滤掉图片元素
        elements = elements.reject(e -> e instanceof Image);

        if (elements.size() <= 1) {
            if (!elements.isEmpty()) {
                result.add(new ElementGroup<>(elements));
            }
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
                // 首先检测是否是列表项续行
                // 列表项续行不应该被拆分，即使看起来像词汇表条目或样式变化
                String lastText = "";
                if (lastElem.hasAttribute(Text.class)) {
                    lastText = lastElem.getAttribute(Text.class).getValue().trim();
                }
                boolean lastWasListItemStart = isListItemStart(lastText);
                boolean currentIsListItemStart = isListItemStart(text);

                // 列表项续行：上一行是列表项开始，当前行不是新列表项
                boolean isListItemContinuation = lastWasListItemStart && !currentIsListItemStart;
                // 也考虑多行续行：当前组的第一个元素是列表项开始或章节编号开始
                boolean groupStartsWithSection = false;
                if (!currentGroup.isEmpty()) {
                    Element firstInGroup = currentGroup.get(0);
                    if (firstInGroup.hasAttribute(Text.class)) {
                        String firstText = firstInGroup.getAttribute(Text.class).getValue().trim();
                        // 检查是否以章节编号开始（如 E2.1., 6.1.2., 1.1.）
                        groupStartsWithSection = isSectionNumberStart(firstText);
                        if ((isListItemStart(firstText) || groupStartsWithSection) && !currentIsListItemStart) {
                            isListItemContinuation = true;
                        }
                    }
                }

                // 如果是列表项/章节续行，不检测词汇表、定义或样式拆分条件
                // 只在遇到新的列表项或章节编号时才拆分
                if (!isListItemContinuation) {
                    // 检测是否是新的列表项开始
                    if (isListItemStart(text)) {
                        shouldSplit = true;
                    }

                    // 检测是否是新的词汇表/缩略语条目开始
                    // 但如果当前组是章节段落（以 E2.1. 等开头），不要因为缩写而拆分
                    if (!groupStartsWithSection && isGlossaryEntryStart(text)) {
                        shouldSplit = true;
                    }

                    // 检测是否是新的定义词条开始
                    if (!groupStartsWithSection && isDefinitionEntry(text)) {
                        shouldSplit = true;
                    }

                    // 检测字体大小变化（仅对非章节段落）
                    if (!groupStartsWithSection) {
                        double lastFontSize = getElementFontSize(lastElem);
                        double currFontSize = getElementFontSize(elem);
                        if (lastFontSize > 0 && currFontSize > 0 && Math.abs(lastFontSize - currFontSize) > 1.5) {
                            shouldSplit = true;
                        }
                    }

                    // 检测粗体/斜体变化（仅对非章节段落）
                    if (!groupStartsWithSection) {
                        boolean lastBold = isBold(lastElem);
                        boolean currBold = isBold(elem);
                        boolean lastItalic = isItalic(lastElem);
                        boolean currItalic = isItalic(elem);
                        if (lastBold != currBold || lastItalic != currItalic) {
                            shouldSplit = true;
                        }
                    }
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

        // 检测括号标记，但要排除缩写和引用续行
        if ((text.startsWith("(") || text.startsWith("（")) && text.length() >= 3) {
            int closeIdx = text.indexOf(')');
            if (closeIdx == -1)
                closeIdx = text.indexOf('）');
            if (closeIdx > 1 && closeIdx < 10) {
                // 关键检查：如果紧跟着另一个右括号，这是引用续行如 "(k)), the..."
                // 这种情况出现在 "Reference (k)" 换行后变成 "(k)), ..."
                if (closeIdx + 1 < text.length()) {
                    char nextChar = text.charAt(closeIdx + 1);
                    if (nextChar == ')' || nextChar == '）') {
                        // 这是引用标记的结尾，不是列表项
                        return false;
                    }
                }

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
        // 匹配多级章节编号：E2.1., 6.1.2., 1.1., A1.2. 等
        // 格式：可选字母 + 数字 + (点 + 数字)* + 点
        if (text.matches("^[A-Za-z]?\\d+(\\.\\d+)*\\.\\s+.*"))
            return true;
        // 匹配 • - * 等符号列表标记
        if (text.startsWith("•") || text.startsWith("-") || text.startsWith("*"))
            return true;
        return false;
    }

    /**
     * 检测文本是否以多级章节编号开头
     * 如：E2.1., 6.1.2., 1.1., A1.2., 2.1. 等
     */
    private boolean isSectionNumberStart(String text) {
        if (text == null || text.length() < 3)
            return false;
        // 格式：可选字母 + 数字 + (点 + 数字)+ + 点 + 空格
        // 匹配：E2.1. xxx, 6.1.2. xxx, 1.1. xxx, A1.2. xxx
        return text.matches("^[A-Za-z]?\\d+(\\.\\d+)+\\.\\s+.*");
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

    private boolean isTechnicalDocID(String text) {
        if (text == null || text.length() < 5)
            return false;
        // Matches typical document IDs: NASA-*, MIL-*, DoD *, ANSI/..., ISO ...
        return text.matches("^(?i)(NASA|MIL|DoD|ANSI|ISO|ASTM|IEEE|SAE)[-\\s].*") ||
                text.matches("^[A-Z]{2,}-\\w+-\\d+.*") || // General pattern like AB-CDE-123
                text.matches("^\\d+\\.\\d+\\s+.*"); // Section numbers like 2.3
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

        // Pattern: (x) or (xx) - 括号包围的短标记（1-2个字符，如 (a), (1), (10)）
        // 不匹配缩写词如 (PPBS), (GIG), (USD(I)) 等
        // 支持中英文括号
        if ((first == '(' || first == '（') && text.length() >= 3) {
            int closeIdx = text.indexOf(')');
            if (closeIdx == -1)
                closeIdx = text.indexOf('）');
            if (closeIdx > 1 && closeIdx <= 4) { // 最多3个字符在括号内，如 (10), (aa)
                // 排除引用续行：如 "(k)), the..." 是 "Reference (k)" 换行后的续行
                // 这种情况第一个右括号后面紧跟另一个右括号
                if (closeIdx + 1 < text.length()) {
                    char nextChar = text.charAt(closeIdx + 1);
                    if (nextChar == ')' || nextChar == '）') {
                        // 这是引用标记的结尾，不是列表项
                        return false;
                    }
                }

                String inside = text.substring(1, closeIdx);
                // 只匹配：单个字母、单个数字、或最多2位数字
                if (inside.matches("[a-zA-Z]|\\d{1,2}")) {
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

    /**
     * 计算页面中所有文本元素的最大右边界
     * 用于约束合并判断（"有空间但没放"）和翻译输出
     */
    private double calculateMaxRightBoundary(Page page, List<Element> allElements) {
        double maxRight = 0;

        // 从所有元素中获取最大右边界
        for (Element element : allElements) {
            if (element.hasAttribute(Left.class) && element.hasAttribute(Width.class)) {
                double left = element.getAttribute(Left.class).getMagnitude();
                double width = element.getAttribute(Width.class).getMagnitude();
                double right = left + width;
                if (right > maxRight) {
                    maxRight = right;
                }
            }
        }

        // 如果没有找到元素，返回页面宽度的92%作为默认值
        if (maxRight == 0) {
            double pageWidth = page.getAttribute(Width.class).getValue().getMagnitude();
            maxRight = pageWidth * 0.92;
        }

        return maxRight;
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

        // 创建合并后的LayoutEntity
        LayoutEntity merged = new LayoutEntity(new ElementGroup<>(allElems), a.pageWidth, a.pageHeight);

        // 关键修复：正确设置lastLineRight为两个原始块的lastLineRight的最大值
        // 因为LayoutEntity的构造函数会重新计算lastLineRight，只取最后一行，
        // 但对于合并的块（如长段落后面跟短段落），最后一行可能是短段落，
        // 而实际文本的最大右边界应该来自长段落的第一行
        // 例如：长段落 A (right=523.7) + 短段落 B (right=167.6)
        // 合并后 lastLineRight = 523.7 (来自A) 或 167.6 (来自B)，
        // 但构造函数可能选择B，导致错误判断
        double maxLastLineRight = Math.max(a.lastLineRight, b.lastLineRight);

        // 检查是否需要修正：如果构造函数计算的lastLineRight明显小于maxLastLineRight
        // 且块的右边界接近或大于maxLastLineRight，说明构造函数选择了错误的行
        if (merged.lastLineRight < maxLastLineRight - 10 && merged.right >= maxLastLineRight - 10) {
            // 手动修正lastLineRight为最大值
            merged.lastLineRight = maxLastLineRight;
        }

        return merged;
    }
}
