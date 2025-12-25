package com.gs.ep.docknight.translate;

import com.gs.ep.docknight.model.Element;
import com.gs.ep.docknight.model.element.Page;

import java.util.List;

/**
 * 页面布局处理策略接口
 * 
 * 不同的页面类型（单栏、多栏、表格等）可以有不同的处理策略实现。
 * 策略模式允许在运行时根据页面特征选择合适的处理方式。
 */
public interface PageLayoutStrategy {
    
    /**
     * 获取该策略适用的页面类型
     */
    PageType getPageType();
    
    /**
     * 分析页面并提取布局实体
     * 
     * @param page 要分析的页面
     * @param pageWidth 页面宽度
     * @param pageHeight 页面高度
     * @return 提取的布局实体列表
     */
    List<LayoutEntity> analyzePage(Page page, double pageWidth, double pageHeight);
    
    /**
     * 判断两个布局实体是否应该合并
     * 
     * @param a 第一个实体
     * @param b 第二个实体
     * @param context 合并上下文信息
     * @return 如果应该合并返回 true
     */
    boolean shouldMerge(LayoutEntity a, LayoutEntity b, MergeContext context);
    
    /**
     * 获取实体的阅读区域编号（用于排序）
     * 
     * @param entity 布局实体
     * @return 区域编号，数字越小越先阅读
     */
    int getReadingArea(LayoutEntity entity);
    
    /**
     * 对布局实体进行合并整理
     * 
     * @param entities 原始布局实体列表
     * @return 合并后的布局实体列表
     */
    List<LayoutEntity> consolidateBlocks(List<LayoutEntity> entities);
    
    /**
     * 对布局实体进行最终排序
     * 
     * @param entities 布局实体列表
     */
    void sortByReadingOrder(List<LayoutEntity> entities);
    
    /**
     * 合并上下文信息，提供合并决策所需的页面级信息
     */
    class MergeContext {
        public final double pageWidth;
        public final double pageHeight;
        public final boolean isMultiColumn;
        
        public MergeContext(double pageWidth, double pageHeight, boolean isMultiColumn) {
            this.pageWidth = pageWidth;
            this.pageHeight = pageHeight;
            this.isMultiColumn = isMultiColumn;
        }
    }
}



