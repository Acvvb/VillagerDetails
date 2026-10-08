package com.villagerdetails.client.setting.component;

import com.villagerdetails.client.setting.config.ComponentLayout;
import com.villagerdetails.client.setting.type.McIds;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;

import java.util.List;

import static com.villagerdetails.client.setting.config.UiLayout.WIDGET_GAP;
import static com.villagerdetails.client.setting.config.UiLayout.WIDGET_HEIGHT;

/**
 * 输入框下面的「id 补全弹窗」组件：候选列表、高亮、上下翻、补全进输入框、点击命中、以及画出来。
 *
 * <p>两个详情页共用：列表页补的是「全部注册表 id / 标签」，键值页补的是「这条规则该用的那个注册表」。
 *
 * <p>里面那点状态（候选、高亮、错误提示）由本组件持有，界面只管：
 * 输入变化时 {@link #update}、按「完成」前 {@link #reset}、画的时候 {@link #draw}。
 *
 * <p>位置的<b>上下翻转</b>也在这里：优先画在输入框下面（那边通常是空的），下面放不下就翻到输入框上面，
 * 两种情况都不超出画布。
 */
public final class SuggestionPopup {

    /**
     * 弹窗的位置信息 —— 由界面提供，因为它才知道输入框在哪、画布多大。
     *
     * @param left            弹窗左边界（一般就是内容区左边）
     * @param right           弹窗右边界（要滚动时是给滚动条让位后的内容区右边）
     * @param boxY            输入框那一行现在的 y（带滚动偏移，随动画变）
     * @param canvasTop       画布上边界（弹窗和行控件一起被它裁剪）
     * @param viewportBottom  整页的下边界（不是面板底边：行数少时弹窗就画在面板下面那片空白里）
     */
    public record Bounds(int left, int right, int boxY, int canvasTop, int viewportBottom) {
    }

    /** 补全候选（只在输入变化时算一次，遍历整张 id 表很贵） */
    private List<McIds.Entry> entries = List.of();
    /** 候选里高亮的那一条 */
    private int index;
    /** 非 null 时这一行显示校验失败提示，而不是候选 */
    private Component error;

    /** 清空候选和高亮，但留着错误提示 */
    public void clear() {
        this.entries = List.of();
        this.index = 0;
    }

    /** 清空候选、高亮和错误提示（离开这一行 / 收下一条之后调） */
    public void reset() {
        clear();
        this.error = null;
    }

    /** 把这一行换成红色的校验失败提示（输入框也得自己标红） */
    public void setError(Component message) {
        this.error = message;
        clear();
    }

    /** 又开始打字了：错误提示先撤掉 */
    public void clearError() {
        this.error = null;
    }

    public boolean hasError() {
        return error != null;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * 输入内容变了：重算候选。
     *
     * @param registry 补全范围；{@code null} = 全部注册表
     */
    public void update(String query, int limit, Registry<?> registry) {
        this.error = null;
        this.entries = registry == null
                ? McIds.search(query, limit)
                : McIds.searchIn(query, limit, registry);
        this.index = 0;
    }

    /** 上下键换一条候选（绕圈） */
    public void move(int delta) {
        if (entries.isEmpty()) {
            return;
        }
        this.index = Math.floorMod(index + delta, entries.size());
    }

    /** 把高亮的那条填进输入框（不直接保存，玩家再确认一次） */
    public void acceptInto(EditBox box) {
        if (box == null || entries.isEmpty()) {
            return;
        }
        box.setValue(entries.get(Math.floorMod(index, entries.size())).id());
        box.setCursorPosition(box.getValue().length());
    }

    /** 弹窗现在该显示几行：0 = 不显示 */
    public int lines() {
        return error != null ? 1 : entries.size();
    }

    /**
     * 弹窗上边界：优先画在输入框下面，下面放不下就翻到输入框上面。
     */
    public int top(Bounds bounds) {
        int lines = lines();
        if (lines == 0) {
            return 0;
        }
        int height = lines * ComponentLayout.POPUP_LINE_HEIGHT;
        int below = bounds.boxY() + WIDGET_HEIGHT + WIDGET_GAP;
        if (below + height <= bounds.viewportBottom()) {
            return below;
        }
        return Math.max(bounds.canvasTop(), bounds.boxY() - WIDGET_GAP - height);
    }

    /** 点到第几条候选（-1 = 没点到 / 现在显示的是校验提示） */
    public int hitTest(Bounds bounds, double mouseX, double mouseY) {
        int lines = lines();
        if (error != null || lines == 0) {
            return -1;
        }
        if (mouseX < bounds.left() || mouseX > bounds.right()) {
            return -1;
        }
        int top = top(bounds);
        for (int i = 0; i < lines; i++) {
            int lineY = top + i * ComponentLayout.POPUP_LINE_HEIGHT;
            if (mouseY >= lineY && mouseY < lineY + ComponentLayout.POPUP_LINE_HEIGHT) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 画弹窗：一行一个候选，左边是完整 id、右边灰字标注它来自哪个注册表（例如 {@code block / item}）；
     * 高亮那行有底色。校验失败时那一行换成红色的提示文字。
     */
    public void draw(GuiGraphicsExtractor graphics, Font font, Bounds bounds) {
        int lines = lines();
        if (lines == 0) {
            return;
        }
        int x = bounds.left();
        int width = bounds.right() - x;
        int top = top(bounds);
        int height = lines * ComponentLayout.POPUP_LINE_HEIGHT;

        graphics.fill(x, top, x + width, top + height, ComponentLayout.POPUP_BACKGROUND);
        for (int i = 0; i < lines; i++) {
            int lineY = top + i * ComponentLayout.POPUP_LINE_HEIGHT;
            if (error != null) {
                graphics.text(font, error, x + 2, lineY + 2, ComponentLayout.POPUP_ERROR);
                continue;
            }
            if (i == index) {
                graphics.fill(x + 1, lineY, x + width - 1, lineY + ComponentLayout.POPUP_LINE_HEIGHT,
                        ComponentLayout.POPUP_SELECTED);
            }
            McIds.Entry option = entries.get(i);
            // 标注来源；「一整个注册表」这种分类 id 额外标一下，免得和同名的条目混淆
            String source = option.category()
                    ? Component.translatable("villagerdetails.ui.list.category").getString() + " · " + option.source()
                    : option.source();
            int sourceWidth = font.width(source);
            String shown = font.plainSubstrByWidth(option.id(), Math.max(0, width - sourceWidth - 8));
            graphics.text(font, shown, x + 2, lineY + 2, ComponentLayout.POPUP_TEXT);
            graphics.text(font, source, x + width - sourceWidth - 2, lineY + 2,
                    ComponentLayout.POPUP_SOURCE);
        }
        graphics.outline(x, top, width, height, ComponentLayout.POPUP_BORDER);
    }
}
