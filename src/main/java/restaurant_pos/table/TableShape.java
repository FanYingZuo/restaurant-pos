package restaurant_pos.table;

/** 桌位形狀：方形、圓形（高度必須等於寬度）、長方形（寬高獨立）。 */
public enum TableShape {
    SQUARE,
    ROUND,
    RECTANGLE;

    /** 方形與圓形的高度等於寬度。 */
    public boolean requiresEqualSides() {
        return this != RECTANGLE;
    }
}
