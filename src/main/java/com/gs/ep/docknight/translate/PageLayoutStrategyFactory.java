package com.gs.ep.docknight.translate;

import com.gs.ep.docknight.model.Element;
import com.gs.ep.docknight.model.ElementList;
import com.gs.ep.docknight.model.PositionalContext;
import com.gs.ep.docknight.model.TabularElementGroup;
import com.gs.ep.docknight.model.attribute.Content;
import com.gs.ep.docknight.model.attribute.Height;
import com.gs.ep.docknight.model.attribute.Left;
import com.gs.ep.docknight.model.attribute.PositionalContent;
import com.gs.ep.docknight.model.attribute.Top;
import com.gs.ep.docknight.model.attribute.Width;
import com.gs.ep.docknight.model.element.Page;
import com.gs.ep.docknight.model.element.TextElement;
import com.gs.ep.docknight.translate.strategy.MultiColumnStrategy;
import com.gs.ep.docknight.translate.strategy.SingleColumnStrategy;
import com.gs.ep.docknight.translate.strategy.TableDominantStrategy;
import org.eclipse.collections.impl.factory.Lists;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 页面布局策略工厂
 * 
 * 负责：
 * 1. 检测页面的布局类型
 * 2. 根据类型创建合适的处理策略
 * 
 * 采用单例模式缓存策略实例，避免重复创建。
 */
public class PageLayoutStrategyFactory {
    
    // 策略实例缓存（策略类是无状态的，可以复用）
    private static final SingleColumnStrategy SINGLE_COLUMN_STRATEGY = new SingleColumnStrategy();
    private static final MultiColumnStrategy MULTI_COLUMN_STRATEGY = new MultiColumnStrategy();
    private static final TableDominantStrategy TABLE_DOMINANT_STRATEGY = new TableDominantStrategy();
    
    // 检测阈值配置
    private static final double MULTI_COLUMN_THRESHOLD = 0.3;  // 双栏检测阈值
    private static final double TABLE_DOMINANT_THRESHOLD = 0.4; // 表格主导阈值
    
    /**
     * 根据页面内容自动检测类型并创建策略
     * 
     * @param page 要分析的页面
     * @return 适合该页面的布局策略
     */
    public PageLayoutStrategy createStrategy(Page page) {
        PageType pageType = detectPageType(page);
        return getStrategy(pageType);
    }
    
    /**
     * 根据页面类型获取对应的策略实例
     * 
     * @param pageType 页面类型
     * @return 对应的策略实例
     */
    public PageLayoutStrategy getStrategy(PageType pageType) {
        switch (pageType) {
            case MULTI_COLUMN:
                return MULTI_COLUMN_STRATEGY;
            case TABLE_DOMINANT:
                return TABLE_DOMINANT_STRATEGY;
            case SINGLE_COLUMN:
            case MIXED:
            default:
                return SINGLE_COLUMN_STRATEGY;
        }
    }
    
    /**
     * 检测页面的布局类型
     * 
     * @param page 要检测的页面
     * @return 检测到的页面类型
     */
    public PageType detectPageType(Page page) {
        double pageWidth = page.getAttribute(Width.class).getValue().getMagnitude();
        double pageHeight = page.getAttribute(Height.class).getValue().getMagnitude();
        
        // 收集所有元素
        List<Element> allElements = collectAllElements(page);
        
        // 检测各种布局特征
        LayoutFeatures features = analyzeLayoutFeatures(allElements, pageWidth, pageHeight);
        
        // 根据特征判断类型
        return determinePageType(features);
    }
    
    /**
     * 布局特征数据类
     */
    private static class LayoutFeatures {
        int leftColumnElements = 0;
        int rightColumnElements = 0;
        int tableCount = 0;
        int totalElements = 0;
        double tableCoverageRatio = 0.0;
        int parallelBlockPairs = 0;
        
        boolean isLikelyMultiColumn() {
            return (leftColumnElements >= 3 && rightColumnElements >= 3)
                || parallelBlockPairs >= 1;
        }
        
        boolean isLikelyTableDominant() {
            return tableCount >= 1 && tableCoverageRatio > TABLE_DOMINANT_THRESHOLD;
        }
    }
    
    private List<Element> collectAllElements(Page page) {
        List<Element> allRaw = Lists.mutable.ofAll(page.getContainingElements(e -> true));
        List<Element> allElements = new ArrayList<>();
        
        if (page.hasAttribute(PositionalContent.class)) {
            allElements.addAll(page.getPositionalContent().getValue().getElements());
        }
        
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
        
        Set<Element> currentSet = new HashSet<>(allElements);
        for (Element e : allRaw) {
            if (e instanceof TextElement || e.hasAttribute(com.gs.ep.docknight.model.attribute.Text.class)) {
                if (!currentSet.contains(e)) {
                    allElements.add(e);
                    currentSet.add(e);
                }
            }
        }
        
        return allElements;
    }
    
    private LayoutFeatures analyzeLayoutFeatures(List<Element> elements, double pageWidth, double pageHeight) {
        LayoutFeatures features = new LayoutFeatures();
        features.totalElements = elements.size();
        
        Set<TabularElementGroup<Element>> processedTables = new HashSet<>();
        double totalTableArea = 0.0;
        double pageArea = pageWidth * pageHeight;
        
        // 用于平行块检测的临时列表
        List<double[]> blockBounds = new ArrayList<>();
        
        for (Element elem : elements) {
            if (!elem.hasAttribute(Left.class) || !elem.hasAttribute(Top.class)) {
                continue;
            }
            
            double left = elem.getAttribute(Left.class).getMagnitude();
            double top = elem.getAttribute(Top.class).getMagnitude();
            
            // 忽略页眉页脚区域
            if (top < pageHeight * 0.08 || top > pageHeight * 0.92) {
                continue;
            }
            
            double width = elem.hasAttribute(Width.class) ? 
                elem.getAttribute(Width.class).getMagnitude() : 0;
            double height = elem.hasAttribute(Height.class) ? 
                elem.getAttribute(Height.class).getMagnitude() : 12;
            
            double centerX = left + width / 2.0;
            
            // 统计左右栏元素
            if (centerX < pageWidth * 0.45) {
                features.leftColumnElements++;
            } else if (centerX > pageWidth * 0.55) {
                features.rightColumnElements++;
            }
            
            // 记录块边界用于平行检测
            if (width > 0 && width < pageWidth * 0.55) {
                blockBounds.add(new double[]{left, top, left + width, top + height, centerX});
            }
            
            // 统计表格
            PositionalContext<Element> context = elem.getPositionalContext();
            if (context != null) {
                TabularElementGroup<Element> table = context.getTabularGroup();
                if (table != null && !processedTables.contains(table)) {
                    processedTables.add(table);
                    features.tableCount++;
                    
                    // 估算表格面积
                    double tableArea = estimateTableArea(table);
                    totalTableArea += tableArea;
                }
            }
        }
        
        // 计算表格覆盖率
        features.tableCoverageRatio = totalTableArea / pageArea;
        
        // 检测平行块对
        features.parallelBlockPairs = countParallelBlockPairs(blockBounds, pageWidth);
        
        return features;
    }
    
    private double estimateTableArea(TabularElementGroup<Element> table) {
        double minT = Double.MAX_VALUE, maxB = Double.MIN_VALUE;
        double minL = Double.MAX_VALUE, maxR = Double.MIN_VALUE;
        
        for (org.eclipse.collections.api.list.MutableList<com.gs.ep.docknight.model.TabularCellElementGroup<Element>> row : table.getCells()) {
            for (com.gs.ep.docknight.model.TabularCellElementGroup<Element> cell : row) {
                if (cell != null && !cell.getElements().isEmpty()) {
                    com.gs.ep.docknight.model.RectangleProperties<Double> bbox = cell.getTextBoundingBox();
                    minT = Math.min(minT, bbox.getTop());
                    maxB = Math.max(maxB, bbox.getBottom());
                    minL = Math.min(minL, bbox.getLeft());
                    maxR = Math.max(maxR, bbox.getRight());
                }
            }
        }
        
        if (minT == Double.MAX_VALUE) return 0;
        return (maxR - minL) * (maxB - minT);
    }
    
    private int countParallelBlockPairs(List<double[]> blocks, double pageWidth) {
        int pairs = 0;
        
        for (int i = 0; i < blocks.size(); i++) {
            for (int j = i + 1; j < blocks.size(); j++) {
                double[] a = blocks.get(i);
                double[] b = blocks.get(j);
                
                // a: [left, top, right, bottom, centerX]
                double aTop = a[1], aBottom = a[3], aCenterX = a[4];
                double bTop = b[1], bBottom = b[3], bCenterX = b[4];
                
                // 检查垂直重叠和水平分离
                double vOverlap = Math.min(aBottom, bBottom) - Math.max(aTop, bTop);
                double hGap = Math.abs(aCenterX - bCenterX);
                
                if (vOverlap > 5 && hGap > pageWidth * 0.30) {
                    pairs++;
                }
            }
        }
        
        return pairs;
    }
    
    private PageType determinePageType(LayoutFeatures features) {
        // 优先检测表格主导
        if (features.isLikelyTableDominant()) {
            return PageType.TABLE_DOMINANT;
        }
        
        // 然后检测多栏
        if (features.isLikelyMultiColumn()) {
            return PageType.MULTI_COLUMN;
        }
        
        // 默认单栏
        return PageType.SINGLE_COLUMN;
    }
    
    /**
     * 获取所有可用的策略类型
     */
    public PageType[] getAvailablePageTypes() {
        return PageType.values();
    }
    
    /**
     * 手动指定策略类型（用于测试或特殊需求）
     */
    public PageLayoutStrategy createStrategyForType(PageType type) {
        return getStrategy(type);
    }
}

