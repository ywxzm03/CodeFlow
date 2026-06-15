package com.codewarp.app;

import com.codewarp.config.ConfigManager;
import com.codewarp.config.Settings;
import com.codewarp.core.QueryEngine;
import com.codewarp.llm.AnthropicClient;
import com.codewarp.llm.LLMClient;
import com.codewarp.tools.BashTool;
import com.codewarp.tools.EditTool;
import com.codewarp.tools.GlobTool;
import com.codewarp.tools.GrepTool;
import com.codewarp.tools.ReadTool;
import com.codewarp.tools.Tool;
import com.codewarp.tools.WriteTool;

import java.util.List;
import java.util.Scanner;

/**
 * CodeWarp主应用
 */
public class CodeWarp {

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("CodeWarp - AI Coding Assistant");
        System.out.println("=".repeat(60));

        // 加载配置
        ConfigManager configManager = new ConfigManager();
        Settings settings;

        try {
            settings = configManager.load();

            // 验证配置
            Settings.ValidationResult validation = settings.validate();
            if (!validation.valid()) {
                System.err.println("\n错误: " + validation.error());
                System.err.println("请编辑配置文件: " + configManager.getConfigFilePath());
                System.err.println("\n示例配置:");
                System.err.println("""
                        {
                          "api_key": "your-api-key-here",
                          "base_url": "https://api.anthropic.com/v1/messages",
                          "model": "claude-opus-4-20250514",
                          "max_tokens": 8192,
                          "max_iterations": 25
                        }
                        """);
                System.exit(1);
            }

            System.out.println("✓ 配置已加载: " + configManager.getConfigFilePath());
            System.out.println("✓ 使用模型: " + settings.model());

        } catch (Exception e) {
            System.err.println("错误: 加载配置失败 - " + e.getMessage());
            System.exit(1);
            return;
        }

        // 初始化组件
        LLMClient llmClient = new AnthropicClient(
                settings.apiKey(),
                settings.baseUrl(),
                settings.model(),
                settings.maxTokens()
        );

        List<Tool> tools = List.of(
                new ReadTool(),
                new WriteTool(),
                new EditTool(),
                new BashTool(),
                new GrepTool(),
                new GlobTool()
        );

        QueryEngine queryEngine = new QueryEngine(llmClient, tools, settings.maxIterations());

        // 启动CLI
        startCLI(queryEngine);
    }

    private static void startCLI(QueryEngine queryEngine) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("\n可用工具: Read, Write, Edit, Bash, Grep, Glob");
        System.out.println("输入 'exit' 退出程序\n");

        while (true) {
            System.out.print("\n> ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) {
                continue;
            }

            if ("exit".equalsIgnoreCase(input)) {
                System.out.println("再见!");
                break;
            }

            try {
                // 执行查询
                QueryEngine.QueryResult result = queryEngine.query(input);

                // 输出最终结果
                System.out.println("\n" + "=".repeat(60));
                System.out.println("最终响应:");
                System.out.println("-".repeat(60));
                System.out.println(result.finalResponse());
                System.out.println("=".repeat(60));
                System.out.println("统计: " + result.iterations() + " 次迭代, " +
                                 result.stopReason());

            } catch (Exception e) {
                System.err.println("\n错误: " + e.getMessage());
                e.printStackTrace();
            }
        }

        scanner.close();
    }
}
