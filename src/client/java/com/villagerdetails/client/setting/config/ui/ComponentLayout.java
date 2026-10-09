package com.villagerdetails.client.setting.config.ui;

/**
 * 组件的<b>内部布局配置</b>：设置界面里每个组件自己「文字往里缩多少、几块之间隔多少」的全局变量都集中在这里。
 *
 * <p>和 {@link UiLayout} 的分工：{@code UiLayout} 决定「组件摆在页面的哪里、组件多大」，
 * 这里决定「组件<b>内部</b>怎么摆」。每个变量都标了它控制的是哪个组件、哪一部分。
 * 颜色属于样式不属于布局，仍留在各自的组件里。
 */
public final class ComponentLayout {

    private ComponentLayout() {
    }

    // ==============================================================
    // 通用
    // ==============================================================

    /**
     * 界面里半透明面板的底色（主界面左右两栏、列表详情页的列表区共用）
     */
    public static final int PANEL_COLOR = 0x55000000;

    // ==============================================================
    // 列表项输入框的补全弹窗 / 校验提示
    // ==============================================================

    /**
     * 补全弹窗每行的高度
     */
    public static final int POPUP_LINE_HEIGHT = 12;
    /**
     * 补全弹窗的背板底色
     */
    public static final int POPUP_BACKGROUND = 0xF0101010;
    /**
     * 补全弹窗里当前高亮那一行的底色
     */
    public static final int POPUP_SELECTED = 0xFF2E5C8A;
    /**
     * 补全弹窗的边框颜色
     */
    public static final int POPUP_BORDER = 0xFF808080;
    /**
     * 补全候选中 id 本身的文字颜色
     */
    public static final int POPUP_TEXT = 0xFFFFFFFF;
    /**
     * 补全候选中「来自哪个注册表」这行标注的颜色
     */
    public static final int POPUP_SOURCE = 0xFFA0A0A0;
    /**
     * 校验失败提示的颜色（和「关=红」同一个红）
     */
    public static final int POPUP_ERROR = 0xFFE74C3C;

    // ==============================================================
    // ScreenDecorations —— 页面装饰（标题 / 上下分隔条）
    // ==============================================================

    /**
     * 标题文字相对屏幕顶部的 y 偏移（居中画在顶栏里）
     */
    public static final int TITLE_Y = 12;
    /**
     * 原版分隔条精灵图单格的宽度（blit 时告诉渲染器的纹理宽度）
     */
    public static final int SEPARATOR_SPRITE_WIDTH = 32;
    /**
     * 原版分隔条精灵图单格的高度（blit 时告诉渲染器的纹理高度）
     */
    public static final int SEPARATOR_SPRITE_HEIGHT = 2;
    /**
     * 分隔条实际画出来的高度（顶栏底边、底栏顶边、二级分类下面那条线都是它）
     */
    public static final int SEPARATOR_HEIGHT = 2;

    // ==============================================================
    // OptionLabel —— 每一行最左边那枚「名称标签」
    // ==============================================================

    /**
     * 标签内文字距离组件左边界的留白（页面里标签框的左右内边距也按它算）
     */
    public static final int LABEL_PADDING = 4;
    /**
     * 标签内文字相对组件顶部的偏移：把文字在 WIDGET_HEIGHT 高的行里摆到中间
     */
    public static final int LABEL_TEXT_Y_OFFSET = 6;

    // ==============================================================
    // SwitchButton / KeyBindButton —— 带自定义字色的按钮
    // ==============================================================

    /**
     * 文字行高：按钮里把文字垂直居中时，(按钮高 - 它) / 2 就是文字 y 偏移
     */
    public static final int BUTTON_TEXT_HEIGHT = 8;

    // ==============================================================
    // ScrollBar —— 内容区右侧的滚动条
    // ==============================================================

    /**
     * 滚动条滑块的最小高度：内容再多，滑块也不会细到抓不住
     */
    public static final int SCROLLBAR_MIN_THUMB = 32;

    // ==============================================================
    // RuleSwitchGroup —— 服务器规则的那排状态按钮（是 / 否 / 管理员 …）
    // ==============================================================

    /**
     * 按钮组里相邻两个状态按钮之间的间距
     */
    public static final int SWITCH_GROUP_GAP = 3;
    /**
     * 状态按钮文字左右各留的内边距（按钮宽度 = 文字宽 + 它 × 2）
     */
    public static final int SWITCH_GROUP_PADDING = 5;
    /**
     * 单个状态按钮的最小宽度（文字只有一两个字时也不会太窄）
     */
    public static final int SWITCH_GROUP_MIN_WIDTH = 28;
}
