package com.codeflow.tools;

/**
 * 工具接口 - 所有工具必须实现此接口
 */
public interface Tool {

    /**
     * 工具名称（用于LLM识别）
     */
    String name();

    /**
     * 工具描述（告诉LLM这个工具是做什么的）
     */
    String description();

    /**
     * 工具的输入schema（JSON Schema格式）
     */
    String inputSchema();

    /**
     * 执行工具
     *
     * @param input 工具输入（JSON格式）
     * @return 工具执行结果
     */
    ToolExecutionResult execute(String input);

    /**
     * 校验工具输入参数是否合法。
     *
     * @param input 工具输入（JSON格式）
     * @return 校验结果，成功时才会执行工具
     */
    default ValidationResult validateInput(String input) {
        return ValidationResult.valid();
    }

    /**
     * 工具是否并发安全
     *
     * @return true 表示可以与其他并发安全工具并行执行，false 表示必须独占执行
     */
    default boolean isConcurrencySafe() {
        return false;  // 默认不安全，子类可以覆盖
    }

    /**
     * 工具执行结果
     */
    record ToolExecutionResult(String content, boolean isError) {
        public static ToolExecutionResult success(String content) {
            return new ToolExecutionResult(content, false);
        }

        public static ToolExecutionResult error(String message) {
            return new ToolExecutionResult(message, true);
        }
    }

    /**
     * 工具参数校验结果。
     */
    record ValidationResult(boolean allowed, String message) {
        public static ValidationResult valid() {
            return new ValidationResult(true, "");
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message);
        }
    }
}
