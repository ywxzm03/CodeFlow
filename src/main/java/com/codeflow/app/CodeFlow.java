package com.codeflow.app;

import com.codeflow.config.ConfigManager;
import com.codeflow.config.Settings;
import com.codeflow.compact.AutoCompactor;
import com.codeflow.compact.CompactionManager;
import com.codeflow.compact.CompactionPolicy;
import com.codeflow.compact.ReactiveCompactor;
import com.codeflow.compact.SnipCompactor;
import com.codeflow.compact.TokenEstimator;
import com.codeflow.core.QueryEngine;
import com.codeflow.hooks.InternalSettingsPermissionPreToolUseHandler;
import com.codeflow.hooks.PreToolUseHandler;
import com.codeflow.llm.AnthropicClient;
import com.codeflow.llm.LLMClient;
import com.codeflow.memory.MemoryContextProvider;
import com.codeflow.memory.MemoryReflection;
import com.codeflow.memory.MemoryStore;
import com.codeflow.memory.TranscriptRecorder;
import com.codeflow.memory.TranscriptStore;
import com.codeflow.permissions.ToolPermissionManager;
import com.codeflow.skills.SkillRenderer;
import com.codeflow.skills.SkillStore;
import com.codeflow.terminal.TerminalSession;
import com.codeflow.tools.BashTool;
import com.codeflow.tools.EditTool;
import com.codeflow.tools.GlobTool;
import com.codeflow.tools.GrepTool;
import com.codeflow.tools.MemoryReadTool;
import com.codeflow.tools.ReadTool;
import com.codeflow.tools.SkillTool;
import com.codeflow.tools.Tool;
import com.codeflow.tools.WriteTool;
import com.codeflow.util.Console;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * CodeFlow主应用
 */
public class CodeFlow {

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

        // 初始化模型客户端。
        LLMClient llmClient = new AnthropicClient(
                settings.apiKey(),
                settings.baseUrl(),
                settings.resolvedModel(),
                settings.maxTokens()
        );

        // 注册基础工具。
        List<Tool> tools = new ArrayList<>();
        SkillStore skillStore = new SkillStore();
        SkillRenderer skillRenderer = new SkillRenderer();
        tools.add(new ReadTool());
        tools.add(new WriteTool());
        tools.add(new EditTool());
        tools.add(new BashTool());
        tools.add(new GrepTool());
        tools.add(new GlobTool());
        tools.add(new SkillTool(skillStore, skillRenderer));

        // 初始化 L5 完整会话记录。
        TranscriptStore transcriptStore = null;
        TranscriptRecorder transcriptRecorder = TranscriptRecorder.disabled();
        TranscriptStore initializedTranscriptStore = new TranscriptStore();
        try {
            initializedTranscriptStore.initialize();
            transcriptStore = initializedTranscriptStore;
            transcriptRecorder = new TranscriptRecorder(transcriptStore);
        } catch (IOException e) {
            Console.warn("[Memory] L5 transcript 初始化失败，已禁用 transcript: " + e.getMessage());
        }

        // 初始化 L0-L3 记忆，并把当前 transcript 路径注入上下文。
        MemoryContextProvider memoryContextProvider = null;
        MemoryReflection memoryReflection = null;
        MemoryStore memoryStore = new MemoryStore();
        TranscriptRecorder activeTranscriptRecorder = transcriptRecorder;
        try {
            memoryStore.initialize();
            memoryContextProvider = new MemoryContextProvider(memoryStore, activeTranscriptRecorder::transcriptPath);
            memoryReflection = new MemoryReflection(llmClient, memoryStore);
            tools.add(new MemoryReadTool(memoryStore));
        } catch (IOException e) {
            Console.warn("[Memory] 初始化失败，已禁用记忆系统: " + e.getMessage());
        }

        // 初始化工具权限管理。settings.json 中的 tool_permissions 由内置 PreToolUse 处理器读取。
        ToolPermissionManager toolPermissionManager = new ToolPermissionManager(settings.resolvedPermissionMode());
        PreToolUseHandler preToolUseHandler = new InternalSettingsPermissionPreToolUseHandler(configManager);

        // 组装三层压缩器。
        CompactionManager compactionManager = null;
        if (transcriptStore != null) {
            Settings.Compaction compaction = settings.resolvedCompaction();
            CompactionPolicy policy = new CompactionPolicy(
                    compaction.enabled(),
                    compaction.contextWindowTokens(),
                    compaction.snipToolResultThresholdChars(),
                    compaction.autoCompactThresholdRatio(),
                    compaction.autoCompactHotMessages(),
                    compaction.reactiveCompactHotMessages()
            );
            TokenEstimator tokenEstimator = new TokenEstimator();
            SnipCompactor snipCompactor = new SnipCompactor(policy.snipToolResultThresholdChars(), tokenEstimator, transcriptRecorder, transcriptStore);
            AutoCompactor autoCompactor = new AutoCompactor(policy, tokenEstimator, llmClient, transcriptRecorder, transcriptStore);
            ReactiveCompactor reactiveCompactor = new ReactiveCompactor(policy, tokenEstimator, llmClient, transcriptRecorder, transcriptStore);
            compactionManager = new CompactionManager(snipCompactor, autoCompactor, reactiveCompactor);
        }

        // 组装查询引擎。
        QueryEngine queryEngine = new QueryEngine(
                llmClient,
                tools,
                settings.maxIterations(),
                toolPermissionManager,
                preToolUseHandler,
                memoryContextProvider,
                compactionManager,
                skillStore
        );

        // 启动终端交互
        new TerminalSession(
                queryEngine,
                llmClient,
                configManager,
                settings,
                toolPermissionManager,
                memoryReflection,
                transcriptRecorder,
                transcriptStore,
                skillStore,
                skillRenderer
        ).run();
    }

    /**
     * 关闭三方库日志，避免干扰终端交互。
     */
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
