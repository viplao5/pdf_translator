package com.gs.ep.docknight.translate;

import com.gs.ep.docknight.model.ElementGroup;
import com.gs.ep.docknight.model.RectangleProperties;
import com.gs.ep.docknight.model.attribute.FirstLineIndent;
import com.gs.ep.docknight.model.attribute.Height;
import com.gs.ep.docknight.model.attribute.Left;
import com.gs.ep.docknight.model.attribute.Top;
import com.gs.ep.docknight.model.attribute.Width;
import com.gs.ep.docknight.model.Element;
import com.gs.ep.docknight.model.element.TextElement;

/**
 * Represents a logical block of content (paragraph, table, or group of
 * elements)
 * with its geometric properties and layout context.
 */
public class LayoutEntity {
    public final Object group; // ElementGroup<Element> or TabularElementGroup
    public final double top;
    public final double bottom;
    public final double left;
    public final double right;
    public final double pageWidth;
    public final double pageHeight;
    public final boolean isTable;
    public final double firstLineLeft;
    public final double lastLineRight;  // 最后一行的右边界，用于判断"有空间但没用"

    // Cache for expensive text extraction
    private String cachedText;

    public LayoutEntity(Object group, double pageWidth, double pageHeight) {
        this.group = group;
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
        this.isTable = group instanceof com.gs.ep.docknight.model.TabularElementGroup;

        RectangleProperties<Double> bbox;
        if (isTable) {
            com.gs.ep.docknight.model.TabularElementGroup<Element> table = (com.gs.ep.docknight.model.TabularElementGroup<Element>) group;
            double minT = Double.MAX_VALUE, maxB = Double.MIN_VALUE;
            double minL = Double.MAX_VALUE, maxR = Double.MIN_VALUE;
            boolean hasContent = false;

            for (org.eclipse.collections.api.list.MutableList<com.gs.ep.docknight.model.TabularCellElementGroup<Element>> row : table
                    .getCells()) {
                for (com.gs.ep.docknight.model.TabularCellElementGroup<Element> cell : row) {
                    if (cell != null && !cell.getElements().isEmpty()) {
                        RectangleProperties<Double> cBox = cell.getTextBoundingBox();
                        minT = Math.min(minT, cBox.getTop());
                        maxB = Math.max(maxB, cBox.getBottom());
                        minL = Math.min(minL, cBox.getLeft());
                        maxR = Math.max(maxR, cBox.getRight());
                        hasContent = true;
                    }
                }
            }
            if (!hasContent) {
                bbox = new RectangleProperties<>(0.0, 0.0, 0.0, 0.0);
            } else {
                bbox = new RectangleProperties<>(minT, maxR, maxB, minL);
            }
        } else {
            bbox = ((ElementGroup<Element>) group).getTextBoundingBox();
        }
        this.top = bbox.getTop();
        this.bottom = bbox.getBottom();
        this.left = bbox.getLeft();
        this.right = bbox.getRight();

        // Calculate first line indent position and last line right boundary
        if (!isTable) {
            ElementGroup<Element> textGroup = (ElementGroup<Element>) group;
            if (!textGroup.getElements().isEmpty()) {
                Element first = textGroup.getElements().get(0);
                if (first.hasAttribute(FirstLineIndent.class)) {
                    this.firstLineLeft = first.getAttribute(Left.class).getMagnitude()
                            + first.getAttribute(FirstLineIndent.class).getMagnitude();
                } else if (first.hasAttribute(Left.class)) {
                    this.firstLineLeft = first.getAttribute(Left.class).getMagnitude();
                } else {
                    this.firstLineLeft = this.left;
                }
                
                // 计算最后一行的右边界
                // 找到 top 值最大的元素（即最后一行的元素）
                double maxTop = Double.MIN_VALUE;
                double lastLineRightTemp = this.right;
                for (Element elem : textGroup.getElements()) {
                    if (elem.hasAttribute(Top.class)) {
                        double elemTop = elem.getAttribute(Top.class).getMagnitude();
                        if (elemTop > maxTop) {
                            maxTop = elemTop;
                            // 更新最后一行的右边界
                            if (elem.hasAttribute(Left.class) && elem.hasAttribute(Width.class)) {
                                lastLineRightTemp = elem.getAttribute(Left.class).getMagnitude() 
                                    + elem.getAttribute(Width.class).getMagnitude();
                            }
                        } else if (Math.abs(elemTop - maxTop) < 3) {
                            // 同一行的其他元素，取最大的右边界
                            if (elem.hasAttribute(Left.class) && elem.hasAttribute(Width.class)) {
                                double elemRight = elem.getAttribute(Left.class).getMagnitude() 
                                    + elem.getAttribute(Width.class).getMagnitude();
                                if (elemRight > lastLineRightTemp) {
                                    lastLineRightTemp = elemRight;
                                }
                            }
                        }
                    }
                }
                this.lastLineRight = lastLineRightTemp;
            } else {
                this.firstLineLeft = this.left;
                this.lastLineRight = this.right;
            }
        } else {
            this.firstLineLeft = this.left;
            this.lastLineRight = this.right;
        }
    }

    public String getCachedText() {
        return cachedText;
    }

    public void setCachedText(String text) {
        this.cachedText = text;
    }
}
