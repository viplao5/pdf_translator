package com.gs.ep.docknight.translate;

/**
 * 页面布局类型枚举
 * 用于标识PDF页面的主要布局特征，以便选择合适的处理策略
 */
public enum PageType {
    
    /**
     * 单栏布局：正文从左到右流动，没有明显的列分隔
     */
    SINGLE_COLUMN("单栏布局"),
    
    /**
     * 多栏布局：页面分为两栏或多栏，内容按列从上到下流动
     */
    MULTI_COLUMN("多栏布局"),
    
    /**
     * 表格主导：页面主要内容为表格，需要保持表格结构
     */
    TABLE_DOMINANT("表格主导"),
    
    /**
     * 混合布局：页面包含多种布局元素（标题、正文、表格、列表等）
     */
    MIXED("混合布局");
    
    private final String displayName;
    
    PageType(String displayName) {
        this.displayName = displayName;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    @Override
    public String toString() {
        return displayName;
    }
}



