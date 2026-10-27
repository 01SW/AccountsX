package top.syshub.accountsx.common.ui;

/**
 * 选中态样式常量。
 *
 * <p>左侧筛选栏和登录界面的模式切换都表达「这几个里选了哪一个」，所以共用同一套底色与外扩量，
 * 避免两处各画一套高亮、改一处忘另一处。这里只放颜色和尺寸，绘制仍由各适配器完成——图形 API
 * 逐版本不同，common 不能依赖。</p>
 */
public final class SelectionStyle {
    /** 选中项外圈色：垫在控件下面，露出一圈强调边框。 */
    public static final int FRAME = 0xFF8FA2FF;

    /** 外圈相对选项矩形左右外扩的量。 */
    public static final int INSET_X = 3;

    /** 外圈相对选项矩形上下外扩的量。 */
    public static final int INSET_Y = 2;

    private SelectionStyle() {
    }
}
