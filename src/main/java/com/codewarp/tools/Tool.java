package com.codewarp.tools;

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
}
