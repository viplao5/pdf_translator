package com.gs.ep.docknight.translate.strategy;

import com.gs.ep.docknight.model.Element;
import com.gs.ep.docknight.model.ElementGroup;
import com.gs.ep.docknight.model.TabularElementGroup;
import com.gs.ep.docknight.model.element.Page;
import com.gs.ep.docknight.translate.AbstractPageLayoutStrategy;
import com.gs.ep.docknight.translate.LayoutEntity;
import com.gs.ep.docknight.translate.PageType;

import java.util.List;

/**
 * 表格主导布局策略
 * 
 * 适用于以表格为主要内容的页面，如数据表、财务报表等。
 * 主要特点：
 * - 优先保持表格结构完整
 * - 表格外的文本按简单规则处理
 * - 阅读顺序：标题 -> 表格 -> 注释/脚注
 */
public class TableDominantStrategy extends AbstractPageLayoutStrategy {
    
    @Override
    public PageType getPageType() {
        return PageType.TABLE_DOMINANT;
    }
    
    @Override
    protected MergeContext createMergeContext(List<LayoutEntity> blocks) {
        double pageWidth = blocks.isEmpty() ? 0 : blocks.get(0).pageWidth;
        double pageHeight = blocks.isEmpty() ? 0 : blocks.get(0).pageHeight;
        return new MergeContext(pageWidth, pageHeight, false);
    }
    
    @Override
    protected void processTable(TabularElementGroup<Element> tabularGroup, 
                                List<LayoutEntity> entities, 
                                double pageWidth, double pageHeight) {
        // 表格主导策略：倾向于保持表格结构
        // 只有非常明显的布局表格才会被扁平化
        boolean isReal = isStrictRealTable(tabularGroup, pageHeight);
        if (isReal) {
            entities.add(new LayoutEntity(tabularGroup, pageWidth, pageHeight));
        } else {
            flattenLayoutTable(tabularGroup, entities, pageWidth, pageHeight);
        }
    }
    
    /**
     * 更严格的表格检测：倾向于保持表格结构
     */
    private boolean isStrictRealTable(TabularElementGroup<Element> table, double pageHeight) {
        int rows = table.numberOfRows();
        int cols = table.numberOfColumns();
        
        // 超过 3x3 的表格通常是数据表格
        if (rows >= 3 && cols >= 3) {
            return true;
        }
        
        // 有边框的表格
        if (hasVisibleBorders(table)) {
            return true;
        }
        
        // 1x1 或 2x1 的表格通常是布局容器
        if (rows <= 2 && cols <= 1) {
            return false;
        }
        
        // 默认：使用基类的检测逻辑
        return isRealTable(table, pageHeight);
    }
    
    private boolean hasVisibleBorders(TabularElementGroup<Element> table) {
        int cellsWithBorders = 0;
        int totalCells = 0;
        
        for (org.eclipse.collections.api.list.MutableList<com.gs.ep.docknight.model.TabularCellElementGroup<Element>> row : table.getCells()) {
            for (com.gs.ep.docknight.model.TabularCellElementGroup<Element> cell : row) {
                if (cell != null && !cell.getElements().isEmpty()) {
                    totalCells++;
                    com.gs.ep.docknight.model.RectangleProperties<Boolean> borders = cell.getBorderExistence();
                    if (borders.getTop() || borders.getBottom() || borders.getLeft() || borders.getRight()) {
                        cellsWithBorders++;
                    }
                }
            }
        }
        
        return totalCells > 0 && (double) cellsWithBorders / totalCells > 0.3;
    }
    
    @Override
    public boolean shouldMerge(LayoutEntity a, LayoutEntity b, MergeContext context) {
        // 表格不参与合并
        if (a.isTable || b.isTable) return false;
        
        double vGap = Math.max(0, Math.max(b.top - a.bottom, a.top - b.bottom));
        double hOverlap = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        double minWidth = Math.min(a.right - a.left, b.right - b.left);
        
        // === 1. 同一行合并 ===
        if (vGap < 4 && Math.abs(a.top - b.top) < 5) {
            if (isStandaloneBullet(a) && isToRightOf(b, a) && !startsWithBullet(b)) return true;
            if (isStandaloneBullet(b) && isToRightOf(a, b) && !startsWithBullet(a)) return true;
        }
        
        // === 2. 新列表项开始 ===
        if (startsWithBullet(b)) return false;
        
        // === 3. 标题/说明文字检测 ===
        String textA = getBlockText(a).trim();
        String textB = getBlockText(b).trim();
        
        // 表格标题通常较短，不应与其他内容合并
        if (isTableCaption(textA) || isTableCaption(textB)) {
            return false;
        }
        
        // === 4. 同一行水平合并 ===
        if (vGap < 4 && hOverlap > -5 && Math.abs(a.top - b.top) < 3) return true;
        
        // === 5. 居中内容合并 ===
        if (areBothCentered(a, b) && vGap < 15) return true;
        
        // === 6. 保守垂直合并 ===
        if (vGap > 8) return false;
        
        boolean sameIndent = Math.abs(a.left - b.left) <= 5;
        boolean veryTightGap = vGap < 5;
        
        if (veryTightGap && sameIndent && hOverlap > minWidth * 0.5) return true;
        
        return false;
    }
    
    @Override
    public int getReadingArea(LayoutEntity entity) {
        double width = entity.right - entity.left;
        double height = entity.bottom - entity.top;
        
        // 页眉检测
        if (entity.top < entity.pageHeight * 0.1) return -1;
        
        // 页脚检测
        if (entity.bottom > entity.pageHeight * 0.9 && height < 50) return 2;
        
        // 表格使用 Area 0
        if (entity.isTable) return 0;
        
        // 居中标题使用 Area 0
        if (isCentered(entity) && width < entity.pageWidth * 0.6) return 0;
        
        return 0;
    }
    
    private boolean isTableCaption(String text) {
        if (text == null || text.isEmpty()) return false;
        
        // 表格标题模式：Table 1, Figure 2, 表1, 图2 等
        return text.matches("(?i)^(Table|Figure|Chart|Graph|Exhibit)\\s+\\d+.*")
            || text.matches("^(表|图|圖)\\s*\\d+.*")
            || text.matches("(?i)^(Note|Notes|Source|Sources):.*");
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
}

