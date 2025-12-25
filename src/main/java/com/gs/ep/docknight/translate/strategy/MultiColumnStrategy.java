package com.gs.ep.docknight.translate.strategy;

import com.gs.ep.docknight.model.Element;
import com.gs.ep.docknight.model.ElementGroup;
import com.gs.ep.docknight.model.TabularElementGroup;
import com.gs.ep.docknight.model.attribute.Color;
import com.gs.ep.docknight.model.attribute.FontSize;
import com.gs.ep.docknight.model.attribute.Left;
import com.gs.ep.docknight.model.attribute.Top;
import com.gs.ep.docknight.model.attribute.Width;
import com.gs.ep.docknight.model.element.Page;
import com.gs.ep.docknight.translate.AbstractPageLayoutStrategy;
import com.gs.ep.docknight.translate.LayoutEntity;
import com.gs.ep.docknight.translate.PageType;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.factory.Lists;

import java.util.ArrayList;
import java.util.List;

/**
 * 多栏布局策略
 * 
 * 适用于双栏或多栏文档，如学术论文、报纸等。
 * 主要特点：
 * - 内容分布在多个列中
 * - 阅读顺序：先左栏从上到下，再右栏从上到下
 * - 合并规则严格，防止跨栏合并
 */
public class MultiColumnStrategy extends AbstractPageLayoutStrategy {

    @Override
    public PageType getPageType() {
        return PageType.MULTI_COLUMN;
    }

    @Override
    protected MergeContext createMergeContext(List<LayoutEntity> blocks) {
        double pageWidth = blocks.isEmpty() ? 0 : blocks.get(0).pageWidth;
        double pageHeight = blocks.isEmpty() ? 0 : blocks.get(0).pageHeight;
        return new MergeContext(pageWidth, pageHeight, true);
    }

    @Override
    protected void processVerticalGroup(ElementGroup<Element> vGroup,
            List<LayoutEntity> entities,
            double pageWidth, double pageHeight) {
        // 多栏布局：先按列拆分组，再按列表项拆分
        List<ElementGroup<Element>> columnSplitGroups = splitGroupByColumns(vGroup, pageWidth);

        for (ElementGroup<Element> colGroup : columnSplitGroups) {
            List<ElementGroup<Element>> splitGroups = splitGroupByListItems(colGroup);
            for (ElementGroup<Element> g : splitGroups) {
                entities.add(new LayoutEntity(g, pageWidth, pageHeight));
            }
        }
    }

    /**
     * 按列拆分 VerticalGroup
     */
    private List<ElementGroup<Element>> splitGroupByColumns(ElementGroup<Element> group, double pageWidth) {
        MutableList<Element> elements = group.getElements();
        if (elements.size() <= 1) {
            return Lists.mutable.of(group);
        }

        MutableList<Element> leftElements = Lists.mutable.empty();
        MutableList<Element> rightElements = Lists.mutable.empty();
        double columnBoundary = pageWidth * 0.5;

        for (Element elem : elements) {
            if (!elem.hasAttribute(Left.class)) {
                leftElements.add(elem);
                continue;
            }

            double left = elem.getAttribute(Left.class).getMagnitude();

            // Fix: Use Left position instead of CenterX.
            if (left < columnBoundary) {
                leftElements.add(elem);
            } else {
                rightElements.add(elem);
            }
        }

        List<ElementGroup<Element>> result = new ArrayList<>();

        if (leftElements.isEmpty()) {
            result.add(new ElementGroup<>(rightElements));
        } else if (rightElements.isEmpty()) {
            result.add(new ElementGroup<>(leftElements));
        } else {
            // 有跨栏元素，需要拆分
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

    @Override
    public boolean shouldMerge(LayoutEntity a, LayoutEntity b, MergeContext context) {
        if (a.isTable || b.isTable)
            return false;

        double vGap = Math.max(0, Math.max(b.top - a.bottom, a.top - b.bottom));
        double hOverlap = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        double minWidth = Math.min(a.right - a.left, b.right - b.left);

        // === 关键：跨栏合并早期阻止 ===
        if (shouldPreventCrossColumnMerge(a, b, context)) {
            return false;
        }

        // === 1. 同一行：Bullet + Content ===
        if (vGap < 4 && Math.abs(a.top - b.top) < 5) {
            if (isStandaloneBullet(a) && isToRightOf(b, a) && !startsWithBullet(b))
                return true;
            if (isStandaloneBullet(b) && isToRightOf(a, b) && !startsWithBullet(a))
                return true;
        }

        // === 2. 阻止合并条件 ===
        if (startsWithBullet(b))
            return false;

        String textB = getBlockText(b).trim();
        if (isNewSectionTitle(textB))
            return false;
        if (hasDifferentColors(a, b))
            return false;
        if (b.left < 80 && !(b.left > a.left + 10) && isReferenceEntry(textB))
            return false;

        // === 2.5 自然段落换行检测 ===
        MergeDecision naturalMerge = checkNaturalLineWrap(a, b, vGap);
        if (naturalMerge == MergeDecision.MERGE)
            return true;
        if (naturalMerge == MergeDecision.PREVENT)
            return false;

        // === Area 检查（多栏特有）===
        if (getReadingArea(a) != getReadingArea(b))
            return false;

        // === 3. 同一行：水平片段合并（增加跨栏检查）===
        double hGap = calculateHorizontalGap(a, b);
        boolean hasLargeHorizontalGap = hGap > 15.0 || hGap > context.pageWidth * 0.03;
        boolean inDifferentHalves = areInDifferentHalves(a, b, context.pageWidth);

        if (vGap < 4 && hOverlap > -5 && Math.abs(a.top - b.top) < 3
                && !hasLargeHorizontalGap && !inDifferentHalves) {
            return true;
        }

        // === 4. 特殊内容检测 ===
        String textA = getBlockText(a).trim();
        if (shouldPreventSpecialContentMerge(textA, textB, a, b, vGap))
            return false;

        // === 5. 居中对齐检测 ===
        if (areBothCentered(a, b) && vGap < 15)
            return true;

        // === 5.5 样式检测 ===
        if (hasDifferentStyles(a, b))
            return false;

        // === 6. 保守的垂直合并 ===
        if (vGap > 6)
            return false;

        boolean sameIndent = Math.abs(a.left - b.left) <= 5;
        boolean veryTightGap = vGap < 5;
        boolean bIsMoreIndented = b.left > a.left + 5;

        if (bIsMoreIndented)
            return false;
        if (veryTightGap && sameIndent && hOverlap > minWidth * 0.5)
            return true;

        return false;
    }

    /**
     * 检测是否应该阻止跨栏合并
     */
    private boolean shouldPreventCrossColumnMerge(LayoutEntity a, LayoutEntity b, MergeContext context) {
        double columnBoundary = context.pageWidth * 0.5;
        double leftColumnMax = context.pageWidth * 0.48;
        double rightColumnMin = context.pageWidth * 0.52;

        double aCenterX = (a.left + a.right) / 2.0;
        double bCenterX = (b.left + b.right) / 2.0;

        // 计算块宽度
        double aWidth = a.right - a.left;
        double bWidth = b.right - b.left;

        // 关键修复：宽块（跨越页面大部分宽度）不应被视为"跨栏"
        // 这是单栏布局中的正常段落，即使它跨越了页面中线
        boolean aIsWideBlock = aWidth > context.pageWidth * 0.55;
        boolean bIsWideBlock = bWidth > context.pageWidth * 0.55;

        // 检查块是否已经跨栏（排除宽块）
        boolean aIsCrossColumn = a.left < columnBoundary && a.right > columnBoundary && !aIsWideBlock;
        boolean bIsCrossColumn = b.left < columnBoundary && b.right > columnBoundary && !bIsWideBlock;

        if (aIsCrossColumn || bIsCrossColumn) {
            return true;
        }

        // 如果两个块都是宽块，不阻止合并
        if (aIsWideBlock && bIsWideBlock) {
            return false;
        }

        // 如果有一个是宽块，也不阻止合并（允许标题与正文合并）
        if (aIsWideBlock || bIsWideBlock) {
            return false;
        }

        // 两个都是窄块，检查是否位于不同的半边
        boolean aInLeftHalf = aCenterX < leftColumnMax;
        boolean aInRightHalf = aCenterX > rightColumnMin;
        boolean bInLeftHalf = bCenterX < leftColumnMax;
        boolean bInRightHalf = bCenterX > rightColumnMin;

        if ((aInLeftHalf && bInRightHalf) || (aInRightHalf && bInLeftHalf)) {
            return true;
        }

        // 额外检查边界跨越
        boolean aReachesRight = a.right > columnBoundary;
        boolean bReachesRight = b.right > columnBoundary;

        if ((aReachesRight && bInLeftHalf) || (bReachesRight && aInLeftHalf)) {
            if (Math.abs(a.top - b.top) < 5) {
                return true;
            }
        }

        return false;
    }

    @Override
    public int getReadingArea(LayoutEntity entity) {
        double width = entity.right - entity.left;
        double height = entity.bottom - entity.top;
        double centerX = (entity.left + entity.right) / 2.0;

        // 页眉/页脚检测
        if (width > entity.pageWidth * 0.4 && height < 100) {
            if (entity.top < entity.pageHeight * 0.12)
                return -1;
            if (entity.bottom > entity.pageHeight * 0.88)
                return 2;
        }

        // 宽块（标题）或居中内容：Area 0
        if (width > entity.pageWidth * 0.65) {
            return 0;
        }

        // Gutter spanning check:
        // If a block clearly starts in the left half and ends in the right half,
        // it spans the column gap and should be treated as a full-width block (Area 0).
        // This handles wide indented lists (like References).
        if (entity.left < entity.pageWidth * 0.45 && entity.right > entity.pageWidth * 0.55) {
            return 0;
        }

        // Left-margin start check:
        // If a block starts near the left margin (within first 25% of page),
        // it's likely main content that flows left-to-right, not a right column.
        // This handles indented lists like References that start at left but extend right.
        if (entity.left < entity.pageWidth * 0.25) {
            return 0;
        }

        if (Math.abs(centerX - entity.pageWidth * 0.5) < 15 && width < entity.pageWidth * 0.5) {
            return 0;
        }

        // 明确在左边或右边
        return (centerX < entity.pageWidth * 0.52) ? 0 : 1;
    }

    // ==================== 私有辅助方法 ====================

    private enum MergeDecision {
        MERGE, PREVENT, CONTINUE
    }

    private MergeDecision checkNaturalLineWrap(LayoutEntity a, LayoutEntity b, double vGap) {
        Element firstA = ((ElementGroup<Element>) a.group).getFirst();

        boolean aLineIsFull = a.right > a.pageWidth * 0.80;
        boolean aIsRightAligned = a.right > a.pageWidth * 0.85 && a.left > a.pageWidth * 0.35;
        boolean sameIndent = Math.abs(a.left - b.left) <= 5;
        boolean bIsLessIndented = b.left < a.left - 5;
        boolean largeLeftShift = a.left - b.left > 100;

        double estimatedLineHeight = 12.0;
        double fontSize = 10.0;
        if (firstA != null && firstA.hasAttribute(FontSize.class)) {
            fontSize = firstA.getAttribute(FontSize.class).getValue().getMagnitude();
            estimatedLineHeight = fontSize * 1.4;
        }

        String textB = getBlockText(b).trim();
        int firstWordLength = 0;
        for (int i = 0; i < textB.length() && i < 20; i++) {
            if (Character.isWhitespace(textB.charAt(i)))
                break;
            firstWordLength++;
        }
        double estimatedFirstWordWidth = firstWordLength * fontSize * 0.6;
        double pageRightEdge = a.pageWidth - 54;
        double remainingSpace = pageRightEdge - a.lastLineRight;
        boolean hasSpaceButNotUsed = remainingSpace > estimatedFirstWordWidth + 10;

        boolean tightVerticalGap = vGap < estimatedLineHeight * 0.5;
        boolean veryTightGap = vGap < 5;
        boolean isParagraphSeparation = vGap > estimatedLineHeight * 0.8;
        boolean likelyNewParagraph = hasSpaceButNotUsed && sameIndent;

        if (likelyNewParagraph && vGap > 2) {
            return MergeDecision.PREVENT;
        }

        if (aLineIsFull && !aIsRightAligned && tightVerticalGap && !isParagraphSeparation && !likelyNewParagraph) {
            if (sameIndent || (bIsLessIndented && !largeLeftShift)) {
                return MergeDecision.MERGE;
            }
        }
        if (veryTightGap && bIsLessIndented && !largeLeftShift && !isParagraphSeparation) {
            return MergeDecision.MERGE;
        }

        return MergeDecision.CONTINUE;
    }

    private double calculateHorizontalGap(LayoutEntity a, LayoutEntity b) {
        if (a.right < b.left) {
            return b.left - a.right;
        } else if (b.right < a.left) {
            return a.left - b.right;
        }
        return 0;
    }

    private boolean areInDifferentHalves(LayoutEntity a, LayoutEntity b, double pageWidth) {
        double aCenterX = (a.left + a.right) / 2.0;
        double bCenterX = (b.left + b.right) / 2.0;
        return (aCenterX < pageWidth * 0.48 && bCenterX > pageWidth * 0.52)
                || (bCenterX < pageWidth * 0.48 && aCenterX > pageWidth * 0.52);
    }

    private boolean isNewSectionTitle(String text) {
        return text.matches("(?i)^SECTION\\s+\\d+.*")
                || text.matches("(?i)^GLOSSARY.*")
                || text.matches("(?i)^REFERENCES.*")
                || text.matches("(?i)^APPENDIX.*")
                || text.matches("(?i)^TABLE OF CONTENTS.*")
                || text.matches("(?i)^INDEX.*");
    }

    private boolean hasDifferentColors(LayoutEntity a, LayoutEntity b) {
        Element firstElemA = ((ElementGroup<Element>) a.group).getFirst();
        Element firstElemB = ((ElementGroup<Element>) b.group).getFirst();
        if (firstElemA != null && firstElemB != null) {
            if (firstElemA.hasAttribute(Color.class) && firstElemB.hasAttribute(Color.class)) {
                java.awt.Color colorA = firstElemA.getAttribute(Color.class).getValue();
                java.awt.Color colorB = firstElemB.getAttribute(Color.class).getValue();
                return colorA != null && colorB != null && !colorA.equals(colorB);
            }
        }
        return false;
    }

    private boolean shouldPreventSpecialContentMerge(String textA, String textB,
            LayoutEntity a, LayoutEntity b, double vGap) {
        boolean aHasLeaderDots = textA.contains("....") || textA.contains("…");
        boolean bHasLeaderDots = textB.contains("....") || textB.contains("…");
        if (aHasLeaderDots && bHasLeaderDots)
            return true;

        if (isGlossaryEntry(textA) && isGlossaryEntry(textB))
            return true;
        if (isDefinitionEntry(textA) && isDefinitionEntry(textB))
            return true;
        if (isReferenceEntry(textA) && isReferenceEntry(textB))
            return true;

        boolean isRightSideA = a.left > a.pageWidth * 0.5 && (a.right - a.left) < a.pageWidth * 0.4;
        boolean isRightSideB = b.left > b.pageWidth * 0.5 && (b.right - b.left) < b.pageWidth * 0.4;
        if (isRightSideA && isRightSideB && vGap > 2)
            return true;

        return false;
    }

    private boolean areBothCentered(LayoutEntity a, LayoutEntity b) {
        return isCentered(a) && isCentered(b);
    }

    private boolean isCentered(LayoutEntity e) {
        double leftMargin = e.left;
        double rightMargin = e.pageWidth - e.right;
        double width = e.right - e.left;
        boolean verySymmetric = Math.abs(leftMargin - rightMargin) < 5 && leftMargin > 60 && rightMargin > 60;
        return Math.abs(leftMargin - rightMargin) < 15
                && (leftMargin > 100 || width < e.pageWidth * 0.4 || verySymmetric);
    }

    private boolean hasDifferentStyles(LayoutEntity a, LayoutEntity b) {
        Element firstA = ((ElementGroup<Element>) a.group).getFirst();
        Element firstB = ((ElementGroup<Element>) b.group).getFirst();

        if (firstA != null && firstB != null) {
            if (firstA.hasAttribute(FontSize.class) && firstB.hasAttribute(FontSize.class)) {
                double sizeA = firstA.getAttribute(FontSize.class).getValue().getMagnitude();
                double sizeB = firstB.getAttribute(FontSize.class).getValue().getMagnitude();
                if (Math.abs(sizeA - sizeB) > 1.2)
                    return true;
            }
            if (isBold(firstA) != isBold(firstB))
                return true;
        }
        return false;
    }
}
