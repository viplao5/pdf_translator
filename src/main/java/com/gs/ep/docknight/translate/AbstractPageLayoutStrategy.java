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
 * 页面布局策略的抽象基类
 * 
 * 提供所有策略通用的方法实现，子类只需覆盖特定的行为。
 * 遵循模板方法模式，定义算法骨架，将特定步骤延迟到子类实现。
 */
public abstract class AbstractPageLayoutStrategy implements PageLayoutStrategy {
    
    // ==================== 模板方法 ====================
    
    @Override
    public List<LayoutEntity> analyzePage(Page page, double pageWidth, double pageHeight) {
        // 1. 收集所有元素
        List<Element> allElements = collectAllElements(page);
        
        // 2. 提取布局实体（子类可覆盖）
        List<LayoutEntity> entities = extractLayoutEntities(page, allElements, pageWidth, pageHeight);
        
        // 3. 初始排序
        entities.sort((a, b) -> Double.compare(a.top, b.top));
        
        // 4. 合并整理
        List<LayoutEntity> consolidated = consolidateBlocks(entities);
        
        // 5. 最终排序
        sortByReadingOrder(consolidated);
        
        return consolidated;
    }
    
    @Override
    public List<LayoutEntity> consolidateBlocks(List<LayoutEntity> blocks) {
        if (blocks.size() < 2) {
            return blocks;
        }
        
        MergeContext context = createMergeContext(blocks);
        List<LayoutEntity> current = new ArrayList<>(blocks);
        boolean merged;
        
        do {
            merged = false;
            for (int i = 0; i < current.size(); i++) {
                for (int j = i + 1; j < current.size(); j++) {
                    LayoutEntity a = current.get(i);
                    LayoutEntity b = current.get(j);
                    if (shouldMerge(a, b, context)) {
                        LayoutEntity mergedEntity = mergeEntities(a, b);
                        logMerge(a, b);
                        current.set(i, mergedEntity);
                        current.remove(j);
                        merged = true;
                        break;
                    }
                }
                if (merged) break;
            }
        } while (merged);
        
        return current;
    }
    
    @Override
    public void sortByReadingOrder(List<LayoutEntity> entities) {
        entities.sort((a, b) -> {
            int areaA = getReadingArea(a);
            int areaB = getReadingArea(b);
            if (areaA != areaB) {
                return Integer.compare(areaA, areaB);
            }
            if (Math.abs(a.top - b.top) < 3) {
                return Double.compare(a.left, b.left);
            }
            return Double.compare(a.top, b.top);
        });
    }
    
    // ==================== 公共工具方法 ====================
    
    /**
     * 收集页面中的所有元素
     */
    protected List<Element> collectAllElements(Page page) {
        List<Element> allRaw = Lists.mutable.ofAll(page.getContainingElements(e -> true));
        List<Element> allElements = new ArrayList<>();
        
        // Source of truth: PositionalContent
        if (page.hasAttribute(PositionalContent.class)) {
            allElements.addAll(page.getPositionalContent().getValue().getElements());
        }
        
        // Fallback: Content attribute
        if (page.hasAttribute(Content.class)) {
            ElementList contentList = page.getAttribute(Content.class).getValue();
            if (contentList != null) {
                for (Object o : contentList.getElements()) {
                    if (o instanceof Element && !allElements.contains(o)) {
                        allElements.add((Element) o);
                    }
                }
            }
        }
        
        // Add any other text elements found recursively
        Set<Element> currentSet = new HashSet<>(allElements);
        for (Element e : allRaw) {
            if (e instanceof TextElement || e.hasAttribute(Text.class)) {
                if (!currentSet.contains(e)) {
                    allElements.add(e);
                    currentSet.add(e);
                }
            }
        }
        
        return allElements;
    }
    
    /**
     * 从元素列表中提取布局实体
     */
    protected List<LayoutEntity> extractLayoutEntities(Page page, List<Element> allElements, 
                                                        double pageWidth, double pageHeight) {
        List<LayoutEntity> entities = new ArrayList<>();
        MutableSet<ElementGroup<Element>> processedGroups = Sets.mutable.empty();
        MutableSet<TabularElementGroup<Element>> processedTables = Sets.mutable.empty();
        
        for (Element element : allElements) {
            PositionalContext<Element> context = element.getPositionalContext();
            if (context == null) {
                if (element instanceof TextElement) {
                    entities.add(new LayoutEntity(
                        new ElementGroup<>(Lists.mutable.of(element)), pageWidth, pageHeight));
                }
                continue;
            }
            
            // 处理表格
            TabularElementGroup<Element> tabularGroup = context.getTabularGroup();
            if (tabularGroup != null) {
                if (processedTables.add(tabularGroup)) {
                    processTable(tabularGroup, entities, pageWidth, pageHeight);
                }
                continue;
            }
            
            // 处理垂直组
            ElementGroup<Element> vGroup = context.getVerticalGroup();
            if (vGroup != null) {
                if (processedGroups.add(vGroup)) {
                    processVerticalGroup(vGroup, entities, pageWidth, pageHeight);
                }
            } else if (element instanceof TextElement || element.hasAttribute(Text.class)) {
                entities.add(new LayoutEntity(
                    new ElementGroup<>(Lists.mutable.of(element)), pageWidth, pageHeight));
            }
        }
        
        return entities;
    }
    
    /**
     * 处理表格元素（子类可覆盖）
     */
    protected void processTable(TabularElementGroup<Element> tabularGroup, 
                                List<LayoutEntity> entities, 
                                double pageWidth, double pageHeight) {
        boolean isReal = isRealTable(tabularGroup, pageHeight);
        if (isReal) {
            entities.add(new LayoutEntity(tabularGroup, pageWidth, pageHeight));
        } else {
            // Flatten "layout tables"
            flattenLayoutTable(tabularGroup, entities, pageWidth, pageHeight);
        }
    }
    
    /**
     * 处理垂直组（子类可覆盖）
     */
    protected void processVerticalGroup(ElementGroup<Element> vGroup, 
                                         List<LayoutEntity> entities, 
                                         double pageWidth, double pageHeight) {
        List<ElementGroup<Element>> splitGroups = splitGroupByListItems(vGroup);
        for (ElementGroup<Element> g : splitGroups) {
            entities.add(new LayoutEntity(g, pageWidth, pageHeight));
        }
    }
    
    /**
     * 将布局表格扁平化为行
     */
    protected void flattenLayoutTable(TabularElementGroup<Element> table, 
                                       List<LayoutEntity> entities, 
                                       double pageWidth, double pageHeight) {
        for (MutableList<TabularCellElementGroup<Element>> row : table.getCells()) {
            MutableList<Element> rowElements = Lists.mutable.empty();
            for (TabularCellElementGroup<Element> cell : row) {
                if (cell != null && !cell.getElements().isEmpty()) {
                    rowElements.addAllIterable(cell.getElements());
                }
            }
            if (rowElements.isEmpty()) continue;
            
            rowElements.sortThis((e1, e2) -> Double.compare(
                e1.getAttribute(Left.class).getMagnitude(),
                e2.getAttribute(Left.class).getMagnitude()));
            
            entities.add(new LayoutEntity(new ElementGroup<>(rowElements), pageWidth, pageHeight));
        }
    }
    
    /**
     * 判断是否是真正的数据表格
     */
    protected boolean isRealTable(TabularElementGroup<Element> table, double pageHeight) {
        int rows = table.numberOfRows();
        int cols = table.numberOfColumns();
        
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
                    if (borders.getTop() || borders.getBottom() || borders.getLeft() || borders.getRight()) {
                        cellsWithBorders++;
                    }
                }
            }
        }
        double tableHeight = (maxB == Double.MIN_VALUE) ? 0 : (maxB - minT);
        
        // 大型容器且边框密度低 -> 扁平化
        if (tableHeight > pageHeight * 0.4) {
            if (cols <= 2) return false;
            double borderRatio = (nonEmptyCells == 0) ? 0 : (double) cellsWithBorders / nonEmptyCells;
            if (borderRatio < 0.25) return false;
        }
        
        // 高度结构化的表格
        if (rows > 8 || cols > 4) {
            if (cols == 2 && isListStyleTable(table, rows)) {
                return false;
            }
            return true;
        }
        
        if (nonEmptyCells > 0 && (double) cellsWithBorders / nonEmptyCells > 0.5) {
            return true;
        }
        
        // 小型无边框布局盒子 -> 扁平化
        if (cellsWithBorders == 0 && (rows <= 2 && cols <= 2)) {
            return false;
        }
        
        // 检测列表型表格
        if (cols == 2 && isListMarkerTable(table, rows)) {
            return false;
        }
        
        return rows >= 2 && cols >= 2;
    }
    
    private boolean isListStyleTable(TabularElementGroup<Element> table, int rows) {
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
        return validRows > rows * 0.5 && avgCol1Width < 60.0;
    }
    
    private boolean isListMarkerTable(TabularElementGroup<Element> table, int rows) {
        int listMarkerCount = 0;
        for (MutableList<TabularCellElementGroup<Element>> row : table.getCells()) {
            if (row.size() >= 1 && !row.get(0).getElements().isEmpty()) {
                StringBuilder cellText = new StringBuilder();
                for (Element elem : row.get(0).getElements()) {
                    if (elem.hasAttribute(Text.class)) {
                        cellText.append(elem.getAttribute(Text.class).getValue());
                    }
                }
                String text = cellText.toString().trim();
                if (text.matches("^\\(\\d+\\)$") || text.matches("^\\([a-zA-Z]\\)$") ||
                    text.matches("^[a-zA-Z]\\.$") || text.matches("^\\d+\\.$") ||
                    text.equals("•") || text.equals("-") || text.equals("*")) {
                    listMarkerCount++;
                }
            }
        }
        return listMarkerCount > 0 && listMarkerCount >= rows * 0.5;
    }
    
    /**
     * 按列表项拆分组
     */
    protected List<ElementGroup<Element>> splitGroupByListItems(ElementGroup<Element> group) {
        List<ElementGroup<Element>> result = new ArrayList<>();
        MutableList<Element> elements = group.getElements();
        
        if (elements.size() <= 1) {
            result.add(group);
            return result;
        }
        
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
                if (isListItemStart(text) || isGlossaryEntryStart(text) || isDefinitionEntry(text)) {
                    shouldSplit = true;
                }
                
                // 字体大小变化
                double lastFontSize = getElementFontSize(lastElem);
                double currFontSize = getElementFontSize(elem);
                if (lastFontSize > 0 && currFontSize > 0 && Math.abs(lastFontSize - currFontSize) > 1.5) {
                    shouldSplit = true;
                }
                
                // 粗体/斜体变化
                if (isBold(lastElem) != isBold(elem) || isItalic(lastElem) != isItalic(elem)) {
                    shouldSplit = true;
                }
                
                // 水平位置大幅变化
                double lastLeft = lastElem.hasAttribute(Left.class) ? 
                    lastElem.getAttribute(Left.class).getMagnitude() : 0;
                if (Math.abs(lastLeft - elemLeft) > 100) {
                    shouldSplit = true;
                }
            }
            
            if (shouldSplit && !currentGroup.isEmpty()) {
                result.add(new ElementGroup<>(currentGroup));
                currentGroup = Lists.mutable.empty();
            }
            
            currentGroup.add(elem);
            lastElem = elem;
            
            if (elem.hasAttribute(Top.class) && elem.hasAttribute(Height.class)) {
                double bottom = elemTop + elem.getAttribute(Height.class).getMagnitude();
                if (bottom > lastBottom) lastBottom = bottom;
            } else if (elem.hasAttribute(Top.class)) {
                if (elemTop > lastBottom) lastBottom = elemTop + 10;
            }
        }
        
        if (!currentGroup.isEmpty()) {
            result.add(new ElementGroup<>(currentGroup));
        }
        
        return result;
    }
    
    /**
     * 合并两个实体
     */
    protected LayoutEntity mergeEntities(LayoutEntity a, LayoutEntity b) {
        MutableList<Element> allElems = Lists.mutable.ofAll(
            ((ElementGroup<Element>) a.group).getElements());
        allElems.addAllIterable(((ElementGroup<Element>) b.group).getElements());
        
        allElems.sortThis((e1, e2) -> {
            double t1 = e1.getAttribute(Top.class).getMagnitude();
            double t2 = e2.getAttribute(Top.class).getMagnitude();
            if (Math.abs(t1 - t2) < 5) {
                return Double.compare(
                    e1.getAttribute(Left.class).getMagnitude(),
                    e2.getAttribute(Left.class).getMagnitude());
            }
            return Double.compare(t1, t2);
        });
        
        return new LayoutEntity(new ElementGroup<>(allElems), a.pageWidth, a.pageHeight);
    }
    
    /**
     * 创建合并上下文
     */
    protected abstract MergeContext createMergeContext(List<LayoutEntity> blocks);
    
    // ==================== 文本检测工具方法 ====================
    
    public String getBlockText(LayoutEntity e) {
        if (e.getCachedText() != null) {
            return e.getCachedText();
        }
        
        if (e.isTable) return "[TABLE]";
        
        double blockWidth = e.right - e.left;
        double leftMargin = e.left;
        double rightMargin = e.pageWidth - e.right;
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
            if (text == null) text = "";
            
            if (!text.isEmpty()) {
                if (prev != null) {
                    double vGap = el.getAttribute(Top.class).getMagnitude()
                        - (prev.getAttribute(Top.class).getMagnitude()
                            + prev.getAttribute(Height.class).getMagnitude());
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
    
    protected boolean isListItemStart(String text) {
        if (text == null || text.isEmpty()) return false;
        
        if ((text.startsWith("(") || text.startsWith("（")) && text.length() >= 3) {
            int closeIdx = text.indexOf(')');
            if (closeIdx == -1) closeIdx = text.indexOf('）');
            if (closeIdx > 1 && closeIdx < 10) {
                String inside = text.substring(1, closeIdx);
                if (inside.matches("\\d{1,3}")) return true;
                if (inside.matches("[a-zA-Z]")) return true;
                if (inside.matches("(?i)^(i{1,3}|iv|v|vi{0,3}|ix|x|xi{0,2})$")) return true;
                if (inside.matches("\\d+[a-zA-Z]")) return true;
            }
        }
        
        if (text.matches("^[a-zA-Z0-9][\\.\\.、].*")) return true;
        if (text.startsWith("•") || text.startsWith("-") || text.startsWith("*")) return true;
        return false;
    }
    
    protected boolean isGlossaryEntry(String text) {
        if (text == null || text.length() < 3) return false;
        if (text.matches("^[A-Z][A-Z0-9\\.\\(\\)&/]{0,14}\\s+[A-Za-z].*")) return true;
        if (text.matches("^[a-zA-Z]{2,10}\\s+[A-Z][a-z].*")) return true;
        return false;
    }
    
    protected boolean isGlossaryEntryStart(String text) {
        if (text == null || text.length() < 3) return false;
        
        String[] parts = text.split("\\s+", 2);
        if (parts.length < 2) return false;
        
        String abbr = parts[0];
        String definition = parts[1];
        
        if (!definition.matches("^[A-Za-z].*")) return false;
        
        if (abbr.matches("^[A-Z][A-Z0-9\\.\\(\\)&/]{0,14}$")) return true;
        if (abbr.matches("^[A-Za-z][A-Za-z0-9\\-]{1,11}$") && abbr.matches(".*[A-Z].*")) return true;
        if (abbr.matches("^[A-Z]{2,6}\\([A-Za-z&/]+\\)(/[A-Z]+)?$")) return true;
        
        return false;
    }
    
    protected boolean isReferenceEntry(String text) {
        if (text == null || text.length() < 10) return false;
        
        String[] patterns = {
            "^ASTM\\s+", "^DoD\\s+Directive", "^DoD\\s+Instruction", "^DoD\\s+Manual",
            "^DoD\\s+\\d", "^Defense\\s+Federal", "^Deputy\\s+Secretary", "^Section\\s+\\d",
            "^Title\\s+\\d", "^Public\\s+Law", "^Executive\\s+Order", "^OMB\\s+", "^\\d+\\s+U\\.?S\\.?C"
        };
        
        for (String pattern : patterns) {
            if (text.matches("(?i)" + pattern + ".*")) return true;
        }
        return false;
    }
    
    protected boolean isDefinitionEntry(String text) {
        if (text == null || text.length() < 5) return false;
        if (text.matches("^[a-z][a-z\\s\\-]+\\.\\s{1,3}[A-Z].*")) return true;
        if (text.matches("^[a-z][a-z\\s\\-]*[A-Z]+[a-zA-Z]*\\.\\s{1,3}[A-Z].*")) return true;
        if (text.matches("^[A-Z]{2,6}\\.\\s{1,3}(Defined|See|As defined).*")) return true;
        if (text.matches("^[A-Z][a-zA-Z\\s]+\\.\\s{1,3}(The|A|An|See|As|Services).*")) return true;
        return false;
    }
    
    protected boolean startsWithBullet(LayoutEntity e) {
        String text = getBlockText(e).trim();
        if (text.isEmpty()) return false;
        char first = text.charAt(0);
        
        if (first == '•' || first == '-' || first == '*') return true;
        
        // Pattern: (x) or (xx) - 括号包围的短标记（1-2个字符，如 (a), (1), (10)）
        // 不匹配缩写词如 (PPBS), (GIG), (USD(I)) 等
        if ((first == '(' || first == '（') && text.length() >= 3) {
            int closeIdx = text.indexOf(')');
            if (closeIdx == -1) closeIdx = text.indexOf('）');
            if (closeIdx > 1 && closeIdx <= 4) { // 最多3个字符在括号内，如 (10), (aa)
                String inside = text.substring(1, closeIdx);
                // 只匹配：单个字母、单个数字、或最多2位数字
                if (inside.matches("[a-zA-Z]|\\d{1,2}")) return true;
            }
        }
        
        if (text.length() >= 2 && (Character.isLetterOrDigit(first) || isChinese(first))) {
            char second = text.charAt(1);
            if (second == '.' || second == ')' || second == '。' || second == '、' || second == '．') {
                return true;
            }
            if (Character.isDigit(first) && text.length() >= 3) {
                int dotIdx = text.indexOf('.');
                if (dotIdx > 0 && dotIdx < 4 && text.substring(0, dotIdx).matches("\\d+")) {
                    return true;
                }
            }
        }
        return false;
    }
    
    protected boolean isStandaloneBullet(LayoutEntity e) {
        return (e.right - e.left) < 30 && startsWithBullet(e);
    }
    
    protected boolean isToRightOf(LayoutEntity target, LayoutEntity bullet) {
        return target.left >= bullet.right - 15 && target.left < bullet.right + 50;
    }
    
    protected boolean isChinese(char c) {
        return c >= '\u4e00' && c <= '\u9fff';
    }
    
    protected double getElementFontSize(Element elem) {
        if (elem.hasAttribute(FontSize.class)) {
            return elem.getAttribute(FontSize.class).getMagnitude();
        }
        return 0;
    }
    
    protected boolean isBold(Element e) {
        if (e == null || !e.hasAttribute(TextStyles.class)) return false;
        List<String> styles = e.getAttribute(TextStyles.class).getValue();
        return styles != null && styles.contains(TextStyles.BOLD);
    }
    
    protected boolean isItalic(Element elem) {
        if (elem.hasAttribute(TextStyles.class)) {
            List<String> styles = elem.getAttribute(TextStyles.class).getValue();
            return styles != null && styles.contains(TextStyles.ITALIC);
        }
        return false;
    }
    
    protected void logMerge(LayoutEntity a, LayoutEntity b) {
        // 调试日志已禁用
    }
}



