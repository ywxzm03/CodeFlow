package com.codewarp.app;

import com.codewarp.config.ConfigManager;
import com.codewarp.config.Settings;
import com.codewarp.core.QueryEngine;
import com.codewarp.llm.AnthropicClient;
import com.codewarp.llm.LLMClient;
import com.codewarp.memory.MemoryContextProvider;
import com.codewarp.memory.MemoryReflection;
import com.codewarp.memory.MemoryStore;
import com.codewarp.permissions.ToolPermissionConfig;
import com.codewarp.permissions.ToolPermissionManager;
import com.codewarp.terminal.TerminalSession;
import com.codewarp.tools.BashTool;
import com.codewarp.tools.EditTool;
import com.codewarp.tools.GlobTool;
import com.codewarp.tools.GrepTool;
import com.codewarp.tools.MemoryReadTool;
import com.codewarp.tools.ReadTool;
import com.codewarp.tools.Tool;
import com.codewarp.tools.WriteTool;
import com.codewarp.util.Console;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * CodeWarp主应用
 */
public class CodeWarp {

    public static void main(String[] args) {
        silenceLibraryLogging();

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
                System.exit(1);
            }

            Console.info("配置已加载: " + configManager.getConfigFilePath());

        } catch (Exception e) {
            System.err.println("错误: 加载配置失败 - " + e.getMessage());
            System.exit(1);
            return;
        }

        // 初始化组件（使用 Anthropic Messages API）
        LLMClient llmClient = new AnthropicClient(
                settings.apiKey(),
                settings.baseUrl(),
                settings.resolvedModel(),
                settings.maxTokens()
        );

        List<Tool> tools = new ArrayList<>();
        tools.add(new ReadTool());
        tools.add(new WriteTool());
        tools.add(new EditTool());
        tools.add(new BashTool());
        tools.add(new GrepTool());
        tools.add(new GlobTool());

        MemoryContextProvider memoryContextProvider = null;
        MemoryReflection memoryReflection = null;
        MemoryStore memoryStore = new MemoryStore();
        try {
            memoryStore.initialize();
            memoryContextProvider = new MemoryContextProvider(memoryStore);
            memoryReflection = new MemoryReflection(llmClient, memoryStore);
            tools.add(new MemoryReadTool(memoryStore));
        } catch (IOException e) {
            Console.warn("[Memory] 初始化失败，已禁用记忆系统: " + e.getMessage());
        }

        ToolPermissionConfig toolPermissionConfig = new ToolPermissionConfig(settings.resolvedToolPermissions());
        ToolPermissionManager toolPermissionManager = new ToolPermissionManager(
                toolPermissionConfig,
                settings.resolvedPermissionMode()
        );
        QueryEngine queryEngine = new QueryEngine(
                llmClient,
                tools,
                settings.maxIterations(),
                toolPermissionManager,
                memoryContextProvider
        );

        // 启动终端交互
        new TerminalSession(queryEngine, llmClient, configManager, settings, toolPermissionManager, memoryReflection).run();
    }

    private static void silenceLibraryLogging() {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "off");
        Logger.getLogger("org.jline").setLevel(Level.OFF);
        Logger rootLogger = LogManager.getLogManager().getLogger("");
        if (rootLogger != null) {
            rootLogger.setLevel(Level.OFF);
            for (Handler handler : rootLogger.getHandlers()) {
                handler.setLevel(Level.OFF);
            }
        }
    }
}
