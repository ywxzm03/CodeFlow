package com.codeflow.routing;

public record ModelRoute(String key, String model) {
    public ModelRoute {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Model route key must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Model route model must not be blank");
        }
    }
}
