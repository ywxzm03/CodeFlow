package com.codewarp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 配置管理器 - 负责加载和保存 ~/.codewrap/settings.json
 */
public class ConfigManager {

    private static final String CONFIG_DIR_NAME = ".codewrap";
    private static final String CONFIG_FILE_NAME = "settings.json";

    private final Path configDir;
    private final Path configFile;
    private final ObjectMapper objectMapper;

    public ConfigManager() {
        this.configDir = getConfigDir();
        this.configFile = configDir.resolve(CONFIG_FILE_NAME);
        this.objectMapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 获取配置目录路径 (~/.codewrap)
     */
    private static Path getConfigDir() {
        String home = System.getProperty("user.home");
        return Paths.get(home, CONFIG_DIR_NAME);
    }

    /**
     * 加载配置
     * 如果配置文件不存在，返回默认配置并创建示例文件
     */
    public Settings load() throws IOException {
        // 如果配置文件不存在，创建默认配置
        if (!Files.exists(configFile)) {
            System.out.println("配置文件不存在，创建示例配置: " + configFile);
            Settings defaults = Settings.defaults();
            createExampleConfig();
            return defaults;
        }

        // 读取配置文件
        try {
            Settings loaded = objectMapper.readValue(configFile.toFile(), Settings.class);
            // 合并默认配置（填充缺失的字段）
            return Settings.defaults().merge(loaded);
        } catch (Exception e) {
            throw new IOException("读取配置文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 保存配置
     */
    public void save(Settings settings) throws IOException {
        // 确保配置目录存在
        if (!Files.exists(configDir)) {
            Files.createDirectories(configDir);
        }

        // 写入配置文件
        objectMapper.writeValue(configFile.toFile(), settings);
    }

    /**
     * 创建示例配置文件
     */
    private void createExampleConfig() throws IOException {
        // 确保配置目录存在
        if (!Files.exists(configDir)) {
            Files.createDirectories(configDir);
        }

        // 创建示例配置
        String exampleJson = """
                {
                  "api_key": "YOUR_API_KEY_HERE",
                  "base_url": "https://api.anthropic.com/v1/messages",
                  "model": "A",
                  "models": {
                    "A": "claude-opus-4-20250514",
                    "B": "claude-sonnet-4-20250514",
                    "C": "claude-haiku-4-20250514"
                  },
                  "max_tokens": 8192,
                  "max_iterations": 25,
                  "permission_mode": "ask",
                  "tool_permissions": {
                    "Read": "ask",
                    "Write": "ask",
                    "Edit": "ask",
                    "Bash": "ask",
                    "Grep": "ask",
                    "Glob": "ask",
                    "MemoryRead": "ask"
                  }
                }
                """;

        Files.writeString(configFile, exampleJson);
        System.out.println("已创建示例配置文件，请编辑并填入你的 API Key");
    }

    /**
     * 获取配置文件路径
     */
    public Path getConfigFilePath() {
        return configFile;
    }

    /**
     * 检查配置文件是否存在
     */
    public boolean configFileExists() {
        return Files.exists(configFile);
    }
}
