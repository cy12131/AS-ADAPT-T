package com.example.adaptt.control;

public enum AdaptAction {
    NONE,
    INCREASE,
    DECREASE;

    public AdaptAction opposite() {
        return switch (this) {
            case INCREASE -> DECREASE;
            case DECREASE -> INCREASE;
            case NONE -> NONE;
        };
    }
}
