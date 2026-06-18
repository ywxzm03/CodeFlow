package com.codewarp.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置模型
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Settings(
        @JsonProperty("api_key") String apiKey,
        @JsonProperty("base_url") String baseUrl,
        @JsonProperty("model") String model,
        @JsonProperty("models") Map<String, String> models,
        @JsonProperty("max_tokens") Integer maxTokens,
        @JsonProperty("max_iterations") Integer maxIterations
) {
    private static final String DEFAULT_MODEL_KEY = "A";

    /**
     * 默认配置
     */
    public static Settings defaults() {
        return new Settings(
                null,
                "https://api.anthropic.com/v1/messages",
                DEFAULT_MODEL_KEY,
                defaultModels(),
                8192,
                25
        );
    }

    /**
     * 合并配置（用加载的配置覆盖默认值）
     */
    public Settings merge(Settings other) {
        return new Settings(
                other.apiKey != null ? other.apiKey : this.apiKey,
                other.baseUrl != null ? other.baseUrl : this.baseUrl,
                other.model != null ? other.model : this.model,
                other.models != null ? other.models : this.models,
                other.maxTokens != null ? other.maxTokens : this.maxTokens,
                other.maxIterations != null ? other.maxIterations : this.maxIterations
        );
    }

    public Settings withModel(String model) {
        return new Settings(apiKey, baseUrl, model, models, maxTokens, maxIterations);
    }

    public String resolvedModel() {
        if (models != null && models.containsKey(model)) {
            return models.get(model);
        }
        return model;
    }

    public Map<String, String> resolvedModels() {
        return models == null || models.isEmpty() ? defaultModels() : models;
    }

    /**
     * 验证配置是否有效
     */
    public ValidationResult validate() {
        if (apiKey == null || apiKey.isEmpty()) {
            return ValidationResult.error("API Key 未设置");
        }
        if (baseUrl == null || baseUrl.isEmpty()) {
            return ValidationResult.error("Base URL 未设置");
        }
        if (model == null || model.isEmpty()) {
            return ValidationResult.error("Model 未设置");
        }
        if (models != null && models.isEmpty()) {
            return ValidationResult.error("Models 未设置");
        }
        if (maxTokens == null || maxTokens <= 0) {
            return ValidationResult.error("Max Tokens 必须大于0");
        }
        if (maxIterations == null || maxIterations <= 0) {
            return ValidationResult.error("Max Iterations 必须大于0");
        }
        return ValidationResult.ok();
    }

    public record ValidationResult(boolean valid, String error) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult error(String message) {
            return new ValidationResult(false, message);
        }
    }

    private static Map<String, String> defaultModels() {
        Map<String, String> models = new LinkedHashMap<>();
        models.put("A", "claude-opus-4-20250514");
        models.put("B", "claude-sonnet-4-20250514");
        models.put("C", "claude-haiku-4-20250514");
        return models;
    }
}
