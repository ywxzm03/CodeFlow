package com.codeflow.config;

import com.codeflow.permissions.PermissionMode;
import com.codeflow.permissions.ToolPermission;
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
        @JsonProperty("max_iterations") Integer maxIterations,
        @JsonProperty("permission_mode") PermissionMode permissionMode,
        @JsonProperty("tool_permissions") Map<String, ToolPermission> toolPermissions,
        @JsonProperty("compaction") Compaction compaction
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
                25,
                PermissionMode.ASK,
                defaultToolPermissions(),
                Compaction.defaults()
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
                other.maxIterations != null ? other.maxIterations : this.maxIterations,
                other.permissionMode != null ? other.permissionMode : this.permissionMode,
                other.toolPermissions != null ? other.toolPermissions : this.toolPermissions,
                other.compaction != null ? other.compaction : this.compaction
        );
    }

    public Settings withModel(String model) {
        return new Settings(apiKey, baseUrl, model, models, maxTokens, maxIterations, permissionMode, toolPermissions, compaction);
    }

    public Settings withPermissionMode(PermissionMode permissionMode) {
        return new Settings(apiKey, baseUrl, model, models, maxTokens, maxIterations, permissionMode, toolPermissions, compaction);
    }

    public String resolvedModel() {
        return resolvedModels().get(model);
    }

    public Map<String, String> resolvedModels() {
        return models == null || models.isEmpty() ? defaultModels() : models;
    }

    public Map<String, ToolPermission> resolvedToolPermissions() {
        return toolPermissions == null ? defaultToolPermissions() : toolPermissions;
    }

    public PermissionMode resolvedPermissionMode() {
        return permissionMode == null ? PermissionMode.ASK : permissionMode;
    }

    public Compaction resolvedCompaction() {
        return compaction == null ? Compaction.defaults() : compaction.mergeDefaults();
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
        if (!resolvedModels().containsKey(model)) {
            return ValidationResult.error("Model 必须是 models 中配置的 key");
        }
        if (maxTokens == null || maxTokens <= 0) {
            return ValidationResult.error("Max Tokens 必须大于0");
        }
        if (maxIterations == null || maxIterations <= 0) {
            return ValidationResult.error("Max Iterations 必须大于0");
        }
        Compaction resolvedCompaction = resolvedCompaction();
        if (resolvedCompaction.contextWindowTokens() <= 0) {
            return ValidationResult.error("Compaction context_window_tokens 必须大于0");
        }
        if (resolvedCompaction.snipToolResultThresholdChars() <= 0) {
            return ValidationResult.error("Compaction snip_tool_result_threshold_chars 必须大于0");
        }
        if (resolvedCompaction.autoCompactThresholdRatio() <= 0 || resolvedCompaction.autoCompactThresholdRatio() >= 1) {
            return ValidationResult.error("Compaction auto_compact_threshold_ratio 必须在0到1之间");
        }
        if (resolvedCompaction.autoCompactHotMessages() < 0) {
            return ValidationResult.error("Compaction auto_compact_hot_messages 不能小于0");
        }
        if (resolvedCompaction.reactiveCompactHotMessages() <= 0) {
            return ValidationResult.error("Compaction reactive_compact_hot_messages 必须大于0");
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

    private static Map<String, ToolPermission> defaultToolPermissions() {
        Map<String, ToolPermission> permissions = new LinkedHashMap<>();
        permissions.put("Read", ToolPermission.ASK);
        permissions.put("Write", ToolPermission.ASK);
        permissions.put("Edit", ToolPermission.ASK);
        permissions.put("Bash", ToolPermission.ASK);
        permissions.put("Grep", ToolPermission.ASK);
        permissions.put("Glob", ToolPermission.ASK);
        permissions.put("MemoryRead", ToolPermission.ASK);
        permissions.put("Skill", ToolPermission.ASK);
        return permissions;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Compaction(
            @JsonProperty("enabled") Boolean enabled,
            @JsonProperty("context_window_tokens") Long contextWindowTokens,
            @JsonProperty("snip_tool_result_threshold_chars") Integer snipToolResultThresholdChars,
            @JsonProperty("auto_compact_threshold_ratio") Double autoCompactThresholdRatio,
            @JsonProperty("auto_compact_hot_messages") Integer autoCompactHotMessages,
            @JsonProperty("reactive_compact_hot_messages") Integer reactiveCompactHotMessages
    ) {
        public static Compaction defaults() {
            return new Compaction(true, 200_000L, 8_000, 0.8, 5, 2);
        }

        public Compaction mergeDefaults() {
            Compaction defaults = defaults();
            return new Compaction(
                    enabled != null ? enabled : defaults.enabled,
                    contextWindowTokens != null ? contextWindowTokens : defaults.contextWindowTokens,
                    snipToolResultThresholdChars != null ? snipToolResultThresholdChars : defaults.snipToolResultThresholdChars,
                    autoCompactThresholdRatio != null ? autoCompactThresholdRatio : defaults.autoCompactThresholdRatio,
                    autoCompactHotMessages != null ? autoCompactHotMessages : defaults.autoCompactHotMessages,
                    reactiveCompactHotMessages != null ? reactiveCompactHotMessages : defaults.reactiveCompactHotMessages
            );
        }
    }
}
