package com.villagerdetails.client.setting.type.server;

import com.villagerdetails.client.setting.type.style.StyleType;

public interface BindingServer {

    StyleType style();   // ← 关键：顶层 StyleType，不是 SecondCategoryType.StyleType

    default double minNumber() {
        return 0.0D;
    }

    default double maxNumber() {
        return 1.0D;
    }

    default double stepNumber() {
        return 1.0D;
    }

    static BindingServer toggle() {
        return () -> StyleType.TOGGLE;
    }

    static BindingServer color() {
        return () -> StyleType.COLOR;
    }

    static BindingServer list() {
        return () -> StyleType.LIST;
    }

    default Runnable action() {
        return () -> {
        };
    }

    static BindingServer slider(double min, double max, double step) {
        return new BindingServer() {
            @Override
            public StyleType style() {
                return StyleType.SLIDER;
            }

            @Override
            public double minNumber() {
                return min;
            }

            @Override
            public double maxNumber() {
                return max;
            }

            @Override
            public double stepNumber() {
                return step;
            }
        };
    }

    static BindingServer trigger(Runnable action) {
        return new BindingServer() {
            @Override
            public StyleType style() {
                return StyleType.TRIGGER;
            }

            @Override
            public Runnable action() {
                return action;
            }
        };
    }
}