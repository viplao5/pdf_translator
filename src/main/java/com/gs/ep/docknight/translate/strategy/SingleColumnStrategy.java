package com.gs.ep.docknight.translate.strategy;

import com.gs.ep.docknight.model.Element;
import com.gs.ep.docknight.model.ElementGroup;
import com.gs.ep.docknight.model.attribute.Color;
import com.gs.ep.docknight.model.attribute.FontSize;
import com.gs.ep.docknight.model.element.Page;
import com.gs.ep.docknight.translate.AbstractPageLayoutStrategy;
import com.gs.ep.docknight.translate.LayoutEntity;
import com.gs.ep.docknight.translate.PageType;

import java.util.List;

/**
 * 单栏布局策略
 * 
 * 适用于标准的单栏文档，内容从上到下、从左到右流动。
 * 主要特点：
 * - 所有内容在同一列中
 * - 阅读顺序简单，按从上到下排序
 * - 合并规则相对宽松
 */
public class SingleColumnStrategy extends AbstractPageLayoutStrategy {
    
    @Override
    public PageType getPageType() {
        return PageType.SINGLE_COLUMN;
    }
    
    @Override
    protected MergeContext createMergeContext(List<LayoutEntity> blocks) {
        double pageWidth = blocks.isEmpty() ? 0 : blocks.get(0).pageWidth;
        double pageHeight = blocks.isEmpty() ? 0 : blocks.get(0).pageHeight;
        return new MergeContext(pageWidth, pageHeight, false);
    }
    
    @Override
    public boolean shouldMerge(LayoutEntity a, LayoutEntity b, MergeContext context) {
        if (a.isTable || b.isTable) return false;
        
        double vGap = Math.max(0, Math.max(b.top - a.bottom, a.top - b.bottom));
        double hOverlap = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        double minWidth = Math.min(a.right - a.left, b.right - b.left);
        
        // === 1. 同一行：Bullet + Content ===
        if (vGap < 4 && Math.abs(a.top - b.top) < 5) {
            if (isStandaloneBullet(a) && isToRightOf(b, a) && !startsWithBullet(b)) return true;
            if (isStandaloneBullet(b) && isToRightOf(a, b) && !startsWithBullet(a)) return true;
        }
        
        // === 2. 阻止合并：新列表项/段落开始 ===
        if (startsWithBullet(b)) return false;
        
        // === 2.3 阻止合并：新章节标题 ===
        String textB = getBlockText(b).trim();
        if (isNewSectionTitle(textB)) return false;
        
        // === 2.4 阻止合并：颜色不同 ===
        if (hasDifferentColors(a, b)) return false;
        
        // === 2.45 阻止合并：新的参考文献条目 ===
        if (b.left < 80 && !(b.left > a.left + 10) && isReferenceEntry(textB)) return false;
        
        // === 2.5 自然段落换行检测 ===
        MergeDecision naturalMerge = checkNaturalLineWrap(a, b, vGap);
        if (naturalMerge == MergeDecision.MERGE) return true;
        if (naturalMerge == MergeDecision.PREVENT) return false;
        
        // === 3. 同一行：水平片段合并 ===
        if (vGap < 4 && hOverlap > -5 && Math.abs(a.top - b.top) < 3) return true;
        
        // === 4. 特殊内容检测 ===
        String textA = getBlockText(a).trim();
        if (shouldPreventSpecialContentMerge(textA, textB, a, b, vGap)) return false;
        
        // === 5. 居中对齐检测 ===
        if (areBothCentered(a, b) && vGap < 15) return true;
        
        // === 5.5 样式检测 ===
        if (hasDifferentStyles(a, b)) return false;
        
        // === 6. 保守的垂直合并 ===
        if (vGap > 6) return false;
        
        boolean sameIndent = Math.abs(a.left - b.left) <= 5;
        boolean veryTightGap = vGap < 5;
        boolean bIsMoreIndented = b.left > a.left + 5;
        
        // b 比 a 缩进更多的情况：
        // 1. 通常表示新段落/子项，不应合并
        // 2. 但如果 A 是列表项开始（如 (i)）且 B 不是新列表项（如 (PPBS)），
        //    则 B 是 A 的悬挂缩进续行，应该合并
        if (bIsMoreIndented) {
            boolean aIsListItem = startsWithBullet(a);
            boolean bIsNewListItem = startsWithBullet(b);
            
            // 列表项续行条件：A是列表项，B不是新列表项，且缩进差不超过50pt
            boolean isListItemContinuation = aIsListItem && !bIsNewListItem && (b.left - a.left) < 50;
            
            if (isListItemContinuation && vGap < 8) {
                // 列表项续行：直接合并（悬挂缩进格式）
                return true;
            }
            return false;
        }
        if (veryTightGap && sameIndent && hOverlap > minWidth * 0.5) return true;
        
        return false;
    }
    
    @Override
    public int getReadingArea(LayoutEntity entity) {
        double width = entity.right - entity.left;
        double height = entity.bottom - entity.top;
        
        // 页眉检测
        if (width > entity.pageWidth * 0.4 && height < 100) {
            if (entity.top < entity.pageHeight * 0.12) return -1;
            if (entity.bottom > entity.pageHeight * 0.88) return 2;
        }
        
        // 窄页眉
        if (entity.top < entity.pageHeight * 0.08 && width < entity.pageWidth * 0.4) return -1;
        
        return 0;
    }
    
    // ==================== 私有辅助方法 ====================
    
    private enum MergeDecision { MERGE, PREVENT, CONTINUE }
    
    private MergeDecision checkNaturalLineWrap(LayoutEntity a, LayoutEntity b, double vGap) {
        Element firstA = ((ElementGroup<Element>) a.group).getFirst();
        Element firstB = ((ElementGroup<Element>) b.group).getFirst();
        
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
        
        // 检测"有空间但没放"的情况
        String textB = getBlockText(b).trim();
        int firstWordLength = 0;
        for (int i = 0; i < textB.length() && i < 20; i++) {
            if (Character.isWhitespace(textB.charAt(i))) break;
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
        
        // 新段落检测
        if (likelyNewParagraph && vGap > 2) {
            return MergeDecision.PREVENT;
        }
        
        // 自然续行
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
        // TOC 相关检测
        boolean isTocEntryStart = textA.matches("^\\d+\\.\\d*\\.?\\s+.*") 
            || textA.matches("^[A-Z]\\.\\d+\\.?\\s+.*")
            || textA.matches("^SECTION\\s+\\d+.*");
        boolean aEndsNearRightEdge = a.right > a.pageWidth * 0.85;
        boolean bHasLeaderDots = textB.contains("....") || textB.contains("…");
        boolean bStartsWithSectionNumber = textB.matches("^\\d+\\.\\d*\\.?\\s+.*") 
            || textB.matches("^[A-Z]\\.\\d+\\.?\\s+.*");
        
        if (isTocEntryStart && !aEndsNearRightEdge && bHasLeaderDots && !bStartsWithSectionNumber && vGap < 8) {
            return false; // 应该合并
        }
        
        boolean aHasLeaderDots = textA.contains("....") || textA.contains("…");
        if (aHasLeaderDots && bHasLeaderDots) return true;
        
        if (isGlossaryEntry(textA) && isGlossaryEntry(textB)) return true;
        if (isDefinitionEntry(textA) && isDefinitionEntry(textB)) return true;
        if (isReferenceEntry(textA) && isReferenceEntry(textB)) return true;
        
        // 右对齐窄块
        boolean isRightSideA = a.left > a.pageWidth * 0.5 && (a.right - a.left) < a.pageWidth * 0.4;
        boolean isRightSideB = b.left > b.pageWidth * 0.5 && (b.right - b.left) < b.pageWidth * 0.4;
        if (isRightSideA && isRightSideB && vGap > 2) return true;
        
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
                if (Math.abs(sizeA - sizeB) > 1.2) return true;
            }
            if (isBold(firstA) != isBold(firstB)) return true;
        }
        return false;
    }
}



