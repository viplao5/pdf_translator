package com.gs.ep.docknight.translate;

import com.gs.ep.docknight.model.Length;
import com.gs.ep.docknight.model.PositionalElementList;

import com.gs.ep.docknight.model.RectangleProperties;
import com.gs.ep.docknight.model.attribute.Height;
import com.gs.ep.docknight.model.attribute.Left;
import com.gs.ep.docknight.model.attribute.PositionalContent;
import com.gs.ep.docknight.model.attribute.Top;
import com.gs.ep.docknight.model.attribute.Width;
import com.gs.ep.docknight.model.Element;
import com.gs.ep.docknight.model.ElementGroup;

import com.gs.ep.docknight.model.TabularCellElementGroup;
import com.gs.ep.docknight.model.TabularElementGroup;
import com.gs.ep.docknight.model.attribute.Text;
import com.gs.ep.docknight.model.attribute.FontSize;
import com.gs.ep.docknight.model.attribute.TextStyles;

import com.gs.ep.docknight.model.converter.PdfParser;
import com.gs.ep.docknight.model.element.Document;
import com.gs.ep.docknight.model.element.GraphicalElement;
import com.gs.ep.docknight.model.element.Page;
import com.gs.ep.docknight.model.element.TextElement;
import com.gs.ep.docknight.model.transformer.PositionalTextGroupingTransformer;

import org.eclipse.collections.impl.factory.Lists;

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
    private final PdfLayoutAnalyzer layoutAnalyzer = new PdfLayoutAnalyzer();
    private final PositionalTextGroupingTransformer groupingTransformer;

    // Compiled Regex Patterns for Performance
    private static final Pattern LIST_ITEM_PATTERN_1 = Pattern
            .compile("(?s)^[\\(（\\[【][a-zA-Z0-9一二三四五六七八九十]{1,4}[\\)）\\]】]\\s*.*");
    private static final Pattern LIST_ITEM_PATTERN_2 = Pattern.compile("(?s)^[a-zA-Z0-9一二三四五六七八九十]{1,4}[\\.、．]\\s*.*");
    private static final Pattern SECTION_TITLE_PATTERN = Pattern.compile("(?s)^第\\d+节[:：]?\\s*[^.…]*$");
    private static final Pattern LEVEL_1_PATTERN = Pattern
            .compile("(?s)^([a-zA-Z][\\.\\.、]|•|-|[一二三四五六七八九十]+[、．.])\\s*.*");
    private static final Pattern LEVEL_2_PATTERN = Pattern.compile("(?s)^[\\(（][a-zA-Z0-9]+[\\)）]\\s*.*");
    private static final Pattern LABEL_PATTERN = Pattern.compile("(?s)^[A-Z][a-zA-Z]*:\\s+.*");
    private static final Pattern TOC_DOTS_PATTERN = Pattern.compile(".*\\.{4,}.*");

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
                        if (context.length() > 0)
                            context.append(" | ");
                        context.append(text);
                        if (context.length() > 300)
                            break; // 限制上下文长度
                    }
                }
            }
        }

        return context.toString();
    }

    private void translatePage(Page page, String targetLanguage) throws Exception {
        // 1. Analyze Layout
        List<LayoutEntity> consolidated = layoutAnalyzer.analyzePage(page);

        // Check for multi-column to pass to applyParagraphTranslation and logging
        double pageWidth = page.getAttribute(Width.class).getValue().getMagnitude();
        double pageHeight = page.getAttribute(Height.class).getValue().getMagnitude();
        boolean multiColumn = layoutAnalyzer.detectMultiColumn(consolidated, pageWidth, pageHeight);

        System.out.println("--- Final Processing Order ---");
        for (int i = 0; i < consolidated.size(); i++) {
            LayoutEntity e = consolidated.get(i);
            String fullText = layoutAnalyzer.getBlockText(e);
            String safeText = (fullText.length() > 60) ? fullText.substring(0, 60) + "..." : fullText;
            safeText = safeText.replace("\n", " ");
            System.out.printf("[%d] Area: %d, Top: %.1f, Left: %.1f, Bottom: %.1f, Right: %.1f, Text: %s%n",
                    i, layoutAnalyzer.getReadingArea(e, multiColumn), e.top, e.left, e.bottom, e.right, safeText);
        }

        // 2. Translate in reading order
        List<Element> extraElements = new ArrayList<>();
        List<LayoutEntity> paragraphEntities = new ArrayList<>();
        for (LayoutEntity entity : consolidated) {
            if (!entity.isTable) {
                paragraphEntities.add(entity);
            }
        }

        List<String> paraTexts = new ArrayList<>();
        for (LayoutEntity e : paragraphEntities) {
            paraTexts.add(layoutAnalyzer.getBlockText(e));
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

    private boolean isBold(Element e) {
        if (e == null || !e.hasAttribute(TextStyles.class))
            return false;
        List<String> styles = e.getAttribute(TextStyles.class).getValue();
        return styles != null && styles.contains(TextStyles.BOLD);
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

        // List Item Heuristic: Lines starting with list markers should never be
        // centered
        // Use (?s) to enable DOTALL mode so .* matches newlines (crucial for merged
        // multi-line blocks)
        // 同时支持中英文标点：() 和 （）, [] 和 【】
        // 注意：中文括号后可能没有空格，如 "（2）遵守..."
        String trimmedTranslatedText = translatedText.trim();
        boolean isListItem = LIST_ITEM_PATTERN_1.matcher(trimmedTranslatedText).matches() // (1) 或 （1）
                || LIST_ITEM_PATTERN_2.matcher(trimmedTranslatedText).matches() // 1. 或 一、
                || trimmedTranslatedText.startsWith("•")
                || trimmedTranslatedText.startsWith("-")
                || trimmedTranslatedText.startsWith("*");

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
        // 只有明确的 SECTION 标题才强制居中，不要基于全大写检测
        if ((translatedText.trim().startsWith("SECTION ")
                || SECTION_TITLE_PATTERN.matcher(translatedText.trim()).matches())
                && !isListItem && !isTOCLine) {
            isCentered = true;
        }

        // Right: Geometrically flush right (allow some margin)
        boolean isRightAligned = !isTOCLine && !isCentered && right > pageWidth * 0.85 && left > pageWidth * 0.3;

        // Hierarchical List Item Detection and Indent Enforcement
        // Level 1: a. | A. | • | - | 一、| 二、 -> 一级列表项
        // Level 2: (1) | (a) | （1）| （2） -> 二级列表项（更深缩进）
        // 使用 (?s) 让 .* 匹配多行文本，支持中英文标点
        String trimmedText = translatedText.trim();
        boolean isLevel1 = LEVEL_1_PATTERN.matcher(trimmedText).matches();
        boolean isLevel2 = LEVEL_2_PATTERN.matcher(trimmedText).matches();
        boolean isHierarchicalListItem = isLevel1 || isLevel2;

        // Adjust boundaries for clean layout
        // Use the Reading Area calculated from the original consolidated entity if
        // possible
        int area = layoutAnalyzer.getReadingArea(new LayoutEntity(group, pageWidth, pageHeight), multiColumn);

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
        String originalText = layoutAnalyzer.getBlockText(entity);
        // 常见的标签模式：以冒号结尾的单词开头（如 "Purpose:", "Note:", "Warning:"）
        boolean startsWithLabel = LABEL_PATTERN.matcher(originalText).matches();

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
                || TOC_DOTS_PATTERN.matcher(translatedText).matches();

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
                                if (firstCurrent.hasAttribute(FontSize.class)
                                        && firstNext.hasAttribute(FontSize.class)) {
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
