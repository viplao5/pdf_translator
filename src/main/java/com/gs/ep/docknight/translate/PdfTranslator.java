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

        // Detect figure regions (large gaps between text content) and get their boundaries
        List<double[]> figureRegions = detectFigureRegions(consolidated, pageHeight);
        
        // 诊断日志：输出每个块的详细信息
        System.out.println("=== Page Analysis (W=" + pageWidth + ", H=" + pageHeight + ", multiCol=" + multiColumn + ") ===");
        for (int i = 0; i < consolidated.size(); i++) {
            LayoutEntity e = consolidated.get(i);
            String fullText = layoutAnalyzer.getBlockText(e);
            String safeText = (fullText.length() > 50) ? fullText.substring(0, 50) + "..." : fullText;
            safeText = safeText.replace("\n", "↵");
            int area = layoutAnalyzer.getReadingArea(e, multiColumn);
            System.out.printf("[%d] L=%.1f R=%.1f T=%.1f B=%.1f H=%.1f Area=%d Table=%b Text='%s'%n",
                    i, e.left, e.right, e.top, e.bottom, e.bottom - e.top, area, e.isTable, safeText);
        }
        System.out.println("=== End Page Analysis ===");

        // 额外诊断：输出相邻块的垂直间距，用于调试URL合并问题
        System.out.println("=== Adjacent Block Gaps ===");
        for (int i = 0; i < consolidated.size() - 1; i++) {
            LayoutEntity curr = consolidated.get(i);
            LayoutEntity next = consolidated.get(i + 1);
            if (!curr.isTable && !next.isTable) {
                double gap = next.top - curr.bottom;
                String currText = layoutAnalyzer.getBlockText(curr);
                String nextText = layoutAnalyzer.getBlockText(next);
                String shortCurr = currText.length() > 30 ? currText.substring(0, 30) + "..." : currText;
                String shortNext = nextText.length() > 30 ? nextText.substring(0, 30) + "..." : nextText;
                shortCurr = shortCurr.replace("\n", "↵");
                shortNext = shortNext.replace("\n", "↵");
                System.out.printf("[%d->%d] gap=%.1f curr='%s' next='%s'%n",
                        i, i + 1, gap, shortCurr, shortNext);
            }
        }
        System.out.println("=== End Gaps ===");

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
        for (int i = 0; i < consolidated.size(); i++) {
            LayoutEntity entity = consolidated.get(i);
            if (entity.isTable) {
                translateTable((TabularElementGroup<Element>) entity.group, targetLanguage, page, extraElements);
            } else {
                // 计算下一个块的顶部位置，用于限制当前块的高度
                double nextBlockTop = pageHeight; // 默认为页面底部
                for (int j = i + 1; j < consolidated.size(); j++) {
                    LayoutEntity next = consolidated.get(j);
                    // 找到下一个在当前块下方的块
                    if (next.top > entity.top) {
                        nextBlockTop = next.top;
                        break;
                    }
                }
                applyParagraphTranslation(entity, paraTranslations.get(paraIdx++), pageWidth, pageHeight, multiColumn, figureRegions, nextBlockTop);
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
            double pageHeight, boolean multiColumn, List<double[]> figureRegions, double nextBlockTop) throws Exception {
        ElementGroup<Element> group = (ElementGroup<Element>) entity.group;
        if (translatedText.trim().isEmpty())
            return;

        RectangleProperties<Double> bbox = group.getTextBoundingBox();
        // 记录段落的整体边界（所有行的最小左边界、最大右边界）
        double overallLeft = bbox.getLeft();  // 整体最左边界
        double overallRight = bbox.getRight(); // 整体最右边界
        double top = bbox.getTop();
        double bottom = bbox.getBottom();
        
        // 记录首行位置（可能有缩进）
        double firstLineLeft = entity.firstLineLeft;
        
        // 关键修改：段落左边界使用整体左边界，首行缩进通过 FirstLineIndent 属性实现
        // 这样：首行从 firstLineLeft 开始，后续行从 overallLeft 开始
        double left = overallLeft;
        double right = overallRight;
        double originalWidth = overallRight - overallLeft;
        double centerX = (overallLeft + overallRight) / 2.0;
        
        // 保存原始位置用于调试
        double origLeft = overallLeft, origRight = overallRight, origTop = top, origBottom = bottom;

        // 计算首行缩进（相对于整体左边界）
        // 正值表示首行比后续行更靠右（如 "a. xxx" 的缩进）
        double calculatedFirstLineIndent = firstLineLeft - overallLeft;

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
        // 使用整体边界进行居中检测（而不是首行位置）
        double leftMargin = overallLeft;
        double rightMargin = pageWidth - overallRight;
        
        // 关键：从标准左边距开始的段落（left < 80pt）不是居中的
        // 这种段落即使延伸到接近右边距，也只是正常的左对齐段落
        boolean startsFromStandardMargin = leftMargin < 80;
        
        // 居中检测条件：
        // 条件1: 左右边距差异小于 15pt，且满足以下任一条件：
        //   a) 左边距大于 100pt（明显居中）
        //   b) 内容较短（< 40% 页宽，适合短标题）且不从标准边距开始
        //   c) 左右边距都大于 100pt 且差异很小（< 10pt），适合宽标题
        boolean marginsSymmetric = Math.abs(leftMargin - rightMargin) < 15;
        // 提高 verySymmetric 的阈值：左右边距都需要 > 100pt
        boolean verySymmetric = Math.abs(leftMargin - rightMargin) < 10 && leftMargin > 100 && rightMargin > 100;
        boolean isSymmetricallyCentered = marginsSymmetric && !startsFromStandardMargin
                && (leftMargin > 100 || originalWidth < pageWidth * 0.4 || verySymmetric);

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
        // 关键修复：对于双栏布局，右栏的内容不应被误认为右对齐
        // 右栏的特征是：left 在页面中间附近（约 50%），内容从左向右正常排列
        // 真正的右对齐是：页眉/页脚等短文本，位于页面右上角或右下角
        
        // 计算 area 以判断是否是右栏内容
        int areaForAlignment = layoutAnalyzer.getReadingArea(new LayoutEntity(group, pageWidth, pageHeight), multiColumn);
        
        // 双栏右栏判断：area=1 说明是右栏内容，或者 left 在典型的右栏起始位置
        boolean isRightColumnContent = multiColumn && (areaForAlignment == 1 
                || (left > pageWidth * 0.48 && left < pageWidth * 0.65));
        
        // 真正的右对齐：不是右栏内容，内容窄，且位于页面右侧
        // 通常是页眉、页脚中的日期、页码等
        boolean isRightAligned = !isTOCLine && !isCentered && !isRightColumnContent 
                && right > pageWidth * 0.85 && left > pageWidth * 0.5
                && (right - left) < pageWidth * 0.4;  // 右对齐内容通常很窄

        // Hierarchical List Item Detection - 用于样式处理，但不强制修改位置
        // Level 1: a. | A. | • | - | 一、| 二、 -> 一级列表项
        // Level 2: (1) | (a) | （1）| （2） -> 二级列表项
        // 使用 (?s) 让 .* 匹配多行文本，支持中英文标点
        String trimmedText = translatedText.trim();
        boolean isLevel1 = LEVEL_1_PATTERN.matcher(trimmedText).matches();
        boolean isLevel2 = LEVEL_2_PATTERN.matcher(trimmedText).matches();
        boolean isHierarchicalListItem = isLevel1 || isLevel2;

        // Adjust boundaries for clean layout
        // Use the Reading Area calculated from the original consolidated entity if
        // possible
        int area = layoutAnalyzer.getReadingArea(new LayoutEntity(group, pageWidth, pageHeight), multiColumn);

        // 计算基准左边距（仅用于边界检查，不强制覆盖原始位置）
        double baseLeft = 0;
        if (area == 1) { // Right column
            baseLeft = pageWidth * 0.52;
        } else { // Left/Single column
            baseLeft = 60.0;
        }

        // 不再强制设置列表项缩进，而是保持原始位置
        // 原始的 left 值已经包含了正确的位置信息
        // 只有当原始位置明显异常时才调整（如位置过于靠左或超出列边界）
        if (isHierarchicalListItem) {
            // 对于列表项，确保位置在合理范围内，但不强制修改
            // 如果原始位置已经在合理范围内，保持不变
            if (area == 1 && left < baseLeft) {
                // 右列的列表项，确保不超出左边界
                left = Math.max(left, baseLeft);
            }
            // 其他情况保持原始位置
        }

        // 调整边距（对所有内容类型）
        // 检测是否是"窄块"：原始宽度小于页面宽度的 60%，可能是与其他内容并排的
        boolean isNarrowBlock = originalWidth < pageWidth * 0.6;
        // 检测是否是"短行"：单行内容，高度较小
        boolean isShortLine = (bottom - top) < 15;
        
        // 判断是否真正与其他内容并排（如标题行的多个部分）
        // 并排块的特征：不从页面左边距开始，或不到达页面右边距
        // 独立段落：从左边距开始（left < 120pt），即使宽度较窄也应该允许扩展
        boolean startsFromLeftMargin = overallLeft < 120;
        boolean endsNearRightMargin = overallRight > pageWidth * 0.75;
        boolean isStandaloneParagraph = startsFromLeftMargin || endsNearRightMargin;
        
        // 只有真正并排的窄块才限制宽度（不从左边距开始，且不到达右边距）
        boolean preserveOriginalWidth = isNarrowBlock && isShortLine && !isCentered && !isStandaloneParagraph;
        
        {
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

                if (neededWidth > currentWidth) {
                    // 需要更多宽度，向左扩展
                    double newLeft = Math.max(60.0, right - neededWidth);
                    left = newLeft;
                }
                // 保持原始右边界（右对齐）
            } else if (preserveOriginalWidth) {
                // 窄块短行：保持原始宽度，只做最小调整
                // 这种情况通常是标题行的一部分，与其他内容并排
                // 不扩展右边界，保持原始宽度
                // 只确保有足够宽度容纳翻译后的文本
                double estimatedFontSize = Math.max(9.0, bottom - top);
                double neededWidth = translatedText.length() * estimatedFontSize * 0.8 + 10;
                if (neededWidth > originalWidth) {
                    // 需要更多宽度，适度扩展但不要太过
                    right = Math.min(left + neededWidth, pageWidth * 0.92);
                }
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
            return;
        }

        // 检测混合样式情况：如果段落中既有粗体又有非粗体元素，
        // 说明只有部分内容是粗体（如术语定义 "property loss. Defined in..."）
        // 需要移除粗体以避免整个翻译段落都变成粗体
        String originalText = layoutAnalyzer.getBlockText(entity);
        
        if (textElement.hasAttribute(TextStyles.class)) {
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

                // 检测术语定义格式：以 "术语. 定义内容" 开头
                // 如 "reconciliation. Defined in..." 或 "SNaP-IT. The electronic..."
                // 这种格式中，只有术语部分应该是粗体
                boolean isDefinitionFormat = originalText.matches("(?s)^[a-zA-Z][a-zA-Z0-9\\-]*\\.\\s+[A-Z].*");
                // 或者以冒号分隔的标签格式
                boolean isLabelFormat = LABEL_PATTERN.matcher(originalText).matches();
                
                // 如果是混合样式，或者是术语定义/标签格式，移除粗体
                boolean shouldRemoveBold = nonBoldCount > 0 || isDefinitionFormat || isLabelFormat;
                
                if (shouldRemoveBold) {
                    List<String> newStyles = Lists.mutable.ofAll(firstStyles);
                    newStyles.remove(TextStyles.BOLD);
                    textElement.removeAttribute(TextStyles.class);
                    if (!newStyles.isEmpty()) {
                        textElement.addAttribute(new TextStyles(newStyles));
                    }
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
        double originalHeight = bottom - top;
        
        // 检测紧凑单行项：原始高度小于等于 lineHeight，且翻译后文本较短
        // 这类项目（如 E1. 参考文献, E2. 定义, 附件 - 9）不应大幅增加高度
        // 不仅限于列表项，任何原始高度很小的短文本都应该保守处理
        boolean isCompactItem = originalHeight <= lineHeight * 1.2 
                && translatedText.length() < 30;
        
        // 诊断：记录高度计算关键参数
        String shortText = translatedText.length() > 30 ? translatedText.substring(0, 30) + "..." : translatedText;
        shortText = shortText.replace("\n", "↵");
        System.out.printf("  HEIGHT: '%s' origH=%.1f fontSize=%.1f lineH=%.1f W=%.1f isCompact=%b isListItem=%b%n",
                shortText, originalHeight, estimatedFontSize, lineHeight, width, isCompactItem, isListItem);
        
        if (preserveOriginalWidth) {
            // 窄块短行（如标题行的一部分）：保持原始高度或只做最小扩展
            // 避免覆盖下方内容
            // 使用原始高度 + 小量填充，但不超过 lineHeight * 1.5
            height = Math.max(originalHeight + 5, lineHeight);
            height = Math.min(height, Math.max(originalHeight + 10, lineHeight * 1.5));
        } else if (isTOCEntry) {
            // 目录条目：使用基于字体大小的固定行高，避免合并后的条目字体变大
            // 单行目录条目的标准高度约为 lineHeight (字体大小 * 1.4)
            height = Math.max(lineHeight, 15);
        } else if (isCompactItem) {
            // 紧凑单行项：只做最小高度扩展，避免覆盖下方相邻项
            height = Math.max(originalHeight + 5, lineHeight);
        } else {
            // 普通段落：估算需要的行数
            // 注意：中文字符宽度约等于字体大小，比英文宽
            boolean hasChinese = translatedText.matches(".*[\\u4e00-\\u9fa5].*");
            double avgCharWidth = hasChinese ? estimatedFontSize * 1.0 : estimatedFontSize * 0.6;
            int estimatedCharsPerLine = Math.max(1, (int) (width / avgCharWidth));
            int estimatedLines = Math.max(1, (int) Math.ceil((double) translatedText.length() / estimatedCharsPerLine));

            // 计算估算高度
            double estimatedHeight = estimatedLines * lineHeight + 5;

            // 对于列表项，平衡空间利用和避免覆盖
            if (isListItem) {
                // 列表项：使用估算高度，但限制最大增加量
                // 这样可以确保翻译后的文本有足够空间，同时避免过度膨胀
                double listItemHeight;
                if (estimatedHeight > originalHeight) {
                    // 翻译后需要更多空间，使用估算高度但不超过原始高度 + 一行
                    listItemHeight = Math.min(estimatedHeight, originalHeight + lineHeight);
                } else {
                    // 翻译后更短或相同，保持原始高度 + 小量填充
                    listItemHeight = originalHeight + 3;
                }
                height = Math.max(15, listItemHeight);
            } else {
                // 普通段落：取原始高度+填充和估算高度的较大值
                height = Math.max(Math.max(15, originalHeight + 25), estimatedHeight);
            }
            // 诊断：输出估算行数和高度
            System.out.printf("         estLines=%d estH=%.1f finalH=%.1f%n", estimatedLines, estimatedHeight, height);
        }

        // 检查是否会进入图片区域，如果会则约束高度
        double finalBottom = top + height;
        for (double[] region : figureRegions) {
            double figureTop = region[0];
            double figureBottom = region[1];
            // 如果文本块在图片上方且会延伸进入图片区域
            if (top < figureTop && finalBottom > figureTop && origBottom <= figureTop + 5) {
                // 约束高度，确保不进入图片区域（留5pt间距）
                double constrainedHeight = figureTop - top - 5;
                if (constrainedHeight > originalHeight * 0.8) { // 至少保留原始高度的80%
                    height = constrainedHeight;
                    finalBottom = top + height;
                }
                break;
            }
        }
        
        // 关键：检查是否会与下一个块重叠
        // 如果当前块的底部会超过下一个块的顶部，约束高度（留3pt间隙）
        if (finalBottom > nextBlockTop - 3 && nextBlockTop > top) {
            double maxAllowedHeight = nextBlockTop - top - 3;
            if (maxAllowedHeight > originalHeight * 0.8) { // 至少保留原始高度的80%
                System.out.printf("         ⚠️ Constraining height to avoid overlap: %.1f -> %.1f (nextTop=%.1f)%n",
                        height, maxAllowedHeight, nextBlockTop);
                height = maxAllowedHeight;
                finalBottom = top + height;
            }
        }

        updateText(textElement, translatedText);

        // Apply styles - 只对文本元素应用
        if (isCentered) {
            textElement.addAttribute(new TextAlign(TextAlign.CENTRE));
        } else if (isRightAligned) {
            textElement.addAttribute(new TextAlign(TextAlign.RIGHT));
        }

        // 应用首行缩进：当首行相对于段落左边界有明显缩进时（>2pt）
        // 适用于列表项（如 "a. xxx"）- 首行从缩进位置开始，后续行从左边距开始
        // 不应用于居中、右对齐内容
        double effectiveFirstLineIndent = 0;
        if (!isCentered && !isRightAligned && calculatedFirstLineIndent > 2.0) {
            effectiveFirstLineIndent = calculatedFirstLineIndent;
            textElement.removeAttribute(FirstLineIndent.class);
            textElement.addAttribute(new FirstLineIndent(new Length(effectiveFirstLineIndent, Length.Unit.pt)));
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

        // 诊断日志：输出表格结构
        System.out.printf("=== TABLE ANALYSIS: %d rows x %d cols ===%n", rowCount, colCount);
        for (int r = 0; r < rowCount; r++) {
            System.out.printf("  Row %d: ", r);
            for (int c = 0; c < colCount; c++) {
                TabularCellElementGroup<Element> cell = table.getMergedCell(r, c);
                StringBuilder cellText = new StringBuilder();
                for (Element e : cell.getElements()) {
                    if (e.hasAttribute(Text.class)) {
                        cellText.append(e.getAttribute(Text.class).getValue());
                    }
                }
                String text = cellText.toString().trim();
                if (text.length() > 30) text = text.substring(0, 30) + "...";
                text = text.replace("\n", "↵");
                RectangleProperties<Boolean> borders = cell.getBorderExistence();
                System.out.printf("[C%d: '%s' B=%b] ", c, text, borders.getBottom());
            }
            System.out.println();
        }

        // 1. Row/Col boundaries - 使用表格的实际单元格边界，而不仅仅是文本边界框
        org.eclipse.collections.api.tuple.Pair<double[], double[]> colBounds = table.getColumnBoundaries();
        double[] colLefts = colBounds.getOne();
        double[] colRights = colBounds.getTwo();

        // 计算行边界 - 使用所有单元格元素的完整边界，而非仅文本边界
        double[] rowTops = new double[rowCount];
        double[] rowBottoms = new double[rowCount];
        
        // 首先收集每行所有元素的边界
        for (int r = 0; r < rowCount; r++) {
            double minT = Double.MAX_VALUE, maxB = Double.MIN_VALUE;
            boolean hasContent = false;
            for (int c = 0; c < colCount; c++) {
                TabularCellElementGroup<Element> cell = table.getMergedCell(r, c);
                if (!cell.getElements().isEmpty()) {
                    // 使用每个元素的 Top 和 Height 来计算精确边界
                    for (Element elem : cell.getElements()) {
                        if (elem.hasAttribute(Top.class)) {
                            double elemTop = elem.getAttribute(Top.class).getMagnitude();
                            double elemHeight = elem.hasAttribute(Height.class) 
                                ? elem.getAttribute(Height.class).getMagnitude() : 12.0;
                            minT = Math.min(minT, elemTop);
                            maxB = Math.max(maxB, elemTop + elemHeight);
                            hasContent = true;
                        }
                    }
                }
            }
            rowTops[r] = hasContent ? minT : (r > 0 ? rowBottoms[r - 1] : 0);
            rowBottoms[r] = hasContent ? maxB : (r > 0 ? rowBottoms[r - 1] + 20 : 20);
        }
        
        // 确保行边界之间有合理的间距，避免重叠
        for (int r = 1; r < rowCount; r++) {
            if (rowTops[r] < rowBottoms[r - 1]) {
                // 行之间有重叠，调整为使用上一行底部作为当前行顶部
                double midPoint = (rowTops[r] + rowBottoms[r - 1]) / 2.0;
                rowBottoms[r - 1] = midPoint;
                rowTops[r] = midPoint;
            }
        }
        
        // 保护表格边框：检查表格的实际边界，确保最后一行底部不会截断底部边框
        // 获取表格的整体边界（包括边框）
        double tableMinTop = Double.MAX_VALUE;
        double tableMaxBottom = Double.MIN_VALUE;
        for (int r = 0; r < rowCount; r++) {
            for (int c = 0; c < colCount; c++) {
                TabularCellElementGroup<Element> cell = table.getMergedCell(r, c);
                // 检查单元格是否有底部边框
                RectangleProperties<Boolean> borders = cell.getBorderExistence();
                if (borders.getBottom() && !cell.getElements().isEmpty()) {
                    // 有底部边框的单元格，需要确保底部边界包含边框
                    RectangleProperties<Double> cellBox = cell.getTextBoundingBox();
                    // 为边框线预留空间（约 2-3pt）
                    tableMaxBottom = Math.max(tableMaxBottom, cellBox.getBottom() + 3.0);
                }
                if (!cell.getElements().isEmpty()) {
                    RectangleProperties<Double> cellBox = cell.getTextBoundingBox();
                    tableMinTop = Math.min(tableMinTop, cellBox.getTop());
                    tableMaxBottom = Math.max(tableMaxBottom, cellBox.getBottom());
                }
            }
        }
        
        // 如果最后一行的底部边界小于表格实际底部（包括边框），则扩展
        if (rowCount > 0 && tableMaxBottom > rowBottoms[rowCount - 1]) {
            rowBottoms[rowCount - 1] = tableMaxBottom;
        }
        
        // 2. Identify and group cells for integrated translation
        // Map from master cell -> its associated logical text/state
        Map<TabularCellElementGroup<Element>, String> cellToJoinedText = new HashMap<>();
        Map<TabularCellElementGroup<Element>, TabularCellElementGroup<Element>> cellToMaster = new HashMap<>();
        Set<TabularCellElementGroup<Element>> processed = new HashSet<>();

        // 判断是否为"定义列表"结构的表格
        // 这种表格的特点是：第一列是标识符，后面是描述，不应该进行跨行垂直合并
        // 包括：2列（标签-值）或 3列（标识符-分隔符-描述）格式
        boolean isDefinitionListTable = false;
        
        // 检测方法：如果表格中有多行的第一列都有短标识符内容，就认为是定义列表
        int rowsWithFirstColContent = 0;
        int rowsWithShortFirstCol = 0;
        for (int r = 0; r < rowCount; r++) {
            TabularCellElementGroup<Element> cell = table.getMergedCell(r, 0);
            if (!cell.getElements().isEmpty()) {
                StringBuilder cellText = new StringBuilder();
                for (Element e : cell.getElements()) {
                    if (e.hasAttribute(Text.class)) {
                        cellText.append(e.getAttribute(Text.class).getValue());
                    }
                }
                String text = cellText.toString().trim();
                if (!text.isEmpty()) {
                    rowsWithFirstColContent++;
                    // 第一列是短文本（<30字符），或者看起来像标识符（如 ASTM D882）
                    if (text.length() < 30 || text.matches("^[A-Z]+\\s*[A-Z]?\\d+.*")) {
                        rowsWithShortFirstCol++;
                    }
                }
            }
        }
        // 如果有多行的第一列都有短标识符，认为是定义列表
        isDefinitionListTable = (rowsWithFirstColContent >= 3 && rowsWithShortFirstCol >= rowsWithFirstColContent * 0.4);
        
        if (isDefinitionListTable) {
            System.out.println("  TABLE TYPE: Definition List (禁止垂直合并)");
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

                // Look ahead vertically (仅当不是定义列表表格时)
                if (!isDefinitionListTable) {
                    int nextR = r + 1;
                    while (nextR < rowCount) {
                        TabularCellElementGroup<Element> nextCell = table.getMergedCell(nextR, c);
                        if (!nextCell.getElements().isEmpty() && !processed.contains(nextCell)) {
                            RectangleProperties<Boolean> borders = cell.getBorderExistence();
                            RectangleProperties<Boolean> nextBorders = nextCell.getBorderExistence();

                            // Merge if no border between them and both are headers or look like text
                            boolean noBorder = !borders.getBottom() && !nextBorders.getTop();

                            // 关键检查：如果下一行的第一列有内容，说明是新条目的开始，不应该合并
                            // 这适用于定义列表格式，但即使不是定义列表，也应该遵守这个规则
                            boolean nextRowIsNewEntry = false;
                            if (c > 0) { // 只对非第一列进行此检查
                                TabularCellElementGroup<Element> nextFirstCol = table.getMergedCell(nextR, 0);
                                if (!nextFirstCol.getElements().isEmpty()) {
                                    StringBuilder firstColText = new StringBuilder();
                                    for (Element e : nextFirstCol.getElements()) {
                                        if (e.hasAttribute(Text.class)) {
                                            firstColText.append(e.getAttribute(Text.class).getValue());
                                        }
                                    }
                                    if (!firstColText.toString().trim().isEmpty()) {
                                        nextRowIsNewEntry = true;
                                    }
                                }
                            }

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

                            if (noBorder && sameStyle && !nextRowIsNewEntry) {
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
                // 同一单元格内的多行文本应使用空格连接（作为一个整体翻译）
                // 只有跨单元格合并时才使用换行分隔
                for (TabularCellElementGroup<Element> chainCell : chain) {
                    StringBuilder cellSb = new StringBuilder();
                    Element prevElemInCell = null;
                    for (Element e : chainCell.getElements()) {
                        if (e.hasAttribute(Text.class)) {
                            String textVal = e.getAttribute(Text.class).getValue();
                            if (textVal == null || textVal.trim().isEmpty()) continue;
                            
                            if (prevElemInCell != null && cellSb.length() > 0) {
                                // 同一单元格内的多行文本使用空格连接
                                // 确保不会重复添加空格
                                if (!cellSb.toString().endsWith(" ") && !textVal.startsWith(" ")) {
                                    cellSb.append(" ");
                                }
                            }
                            cellSb.append(textVal.trim());
                            prevElemInCell = e;
                        }
                    }
                    String t = cellSb.toString().trim();
                    if (!t.isEmpty()) {
                        if (sb.length() > 0) {
                            // 跨单元格（垂直合并的不同行）使用换行分隔
                            sb.append("\n");
                        }
                        sb.append(t);
                    }
                }

                if (sb.length() > 0) {
                    TabularCellElementGroup<Element> primary = chain.get(0);
                    cellToJoinedText.put(primary, sb.toString());
                    for (TabularCellElementGroup<Element> chainCell : chain) {
                        cellToMaster.put(chainCell, primary);
                    }
                    // 诊断：输出合并链
                    if (chain.size() > 1) {
                        String shortText = sb.toString();
                        if (shortText.length() > 50) shortText = shortText.substring(0, 50) + "...";
                        shortText = shortText.replace("\n", "↵");
                        System.out.printf("  TABLE MERGE: %d cells merged for col %d: '%s'%n", chain.size(), c, shortText);
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
            
            // 计算高度：使用行边界，但确保不会与下一行重叠
            double height = bottom - top;
            // 如果有下一行，确保高度不超过到下一行顶部的距离（留 1pt 间隙）
            if (maxR < rowCount - 1) {
                double maxAllowedHeight = rowTops[maxR + 1] - top - 1.0;
                if (maxAllowedHeight > 0 && height > maxAllowedHeight) {
                    height = maxAllowedHeight;
                }
            }
            // 最小高度改为 10pt，避免过小
            height = Math.max(10, height);

            // 诊断：输出单元格翻译应用
            String shortTrans = translated.length() > 40 ? translated.substring(0, 40) + "..." : translated;
            shortTrans = shortTrans.replace("\n", "↵");
            System.out.printf("  TABLE CELL [R%d-%d,C%d-%d]: L=%.1f W=%.1f T=%.1f H=%.1f Text='%s'%n",
                    minR, maxR, minC, maxC, left, width, top, height, shortTrans);

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
    
    /**
     * 检测页面中的图形区域（文本间的大间隙）
     * 这些区域可能包含矢量图形（流程图、图表等），这些图形可能不会在输出中正确渲染
     * @return 图形区域列表，每个区域是 double[]{top, bottom}
     */
    private List<double[]> detectFigureRegions(List<LayoutEntity> entities, double pageHeight) {
        List<double[]> regions = new ArrayList<>();
        if (entities.size() < 2) return regions;
        
        // 按垂直位置排序
        List<LayoutEntity> sorted = new ArrayList<>(entities);
        sorted.sort((a, b) -> Double.compare(a.top, b.top));
        
        double minGapForFigure = 100.0; // 至少 100pt 的间隙才认为是图形区域
        
        for (int i = 0; i < sorted.size() - 1; i++) {
            LayoutEntity current = sorted.get(i);
            LayoutEntity next = sorted.get(i + 1);
            
            double gap = next.top - current.bottom;
            
            if (gap > minGapForFigure) {
                // 检查下一个元素是否是图片标题（Figure X. 或 图X.）
                String nextText = layoutAnalyzer.getBlockText(next).trim().toLowerCase();
                boolean isFigureCaption = nextText.startsWith("figure ") || 
                                         nextText.startsWith("fig.") || 
                                         nextText.startsWith("图") ||
                                         nextText.matches("^图\\s*\\d+.*");
                
                if (isFigureCaption || gap > 200) {
                    // 记录图形区域边界
                    // 注意：PDFDocumentStripper.renderRegionAsImage 会给图片顶部添加 15pt 边距
                    // 所以这里的图形区域顶部也要加上这个边距，避免不必要的高度约束
                    double figureTopMargin = 15.0;
                    regions.add(new double[]{current.bottom + figureTopMargin, next.top});
                }
            }
        }
        return regions;
    }
}
