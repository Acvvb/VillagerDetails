package com.villagerdetails.client.setting.util;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;

/**
 * 26.3 动了 {@code InputConstants} 的两处 API，这里统一兜住，别处照常写业务代码。
 *
 * <ul>
 *     <li>26.3 把 {@code Type.KEYSYM} 改名成 {@code Type.KEYBOARD}，并删掉了 {@code Type.SCANCODE}</li>
 *     <li>26.3 去掉了 {@code isKeyDown} 的 {@code Window} 参数</li>
 * </ul>
 */
public final class InputCompat {

    private InputCompat() {
    }

    /**
     * 键盘按键的类型（26.2 叫 {@code KEYSYM}，26.3 叫 {@code KEYBOARD}）
     */
    public static InputConstants.Type keyboard() {



        return InputConstants.Type.KEYSYM;

    }

    /**
     * 这个键现在是否按下。{@code window} 参数在 26.3 已经用不上了，保留是为了调用处两边一致。
     */
    public static boolean isKeyDown(Window window, int key) {



        return InputConstants.isKeyDown(window, key);

    }
}
