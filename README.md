# CodeFlow

**Java 21 实现的本地终端 AI 编码 Agent**——一个仿 Claude Code 的 AI 编码助手。在终端里用自然语言驱动 LLM 读写文件、执行命令、搜索代码，完成从简单问答到多 Agent 协作的复杂编码任务。

## 功能特性

### 核心循环
- **流式 LLM 调用**：基于 Anthropic 官方 Java SDK + Reactor Core 实现 push-style 流式事件传递，边接收响应边执行工具，用户感知延迟大幅降低
- **多工具并行执行**：LLM 一次返回多个 tool_use 时并发执行，支持用户中断（Ctrl+C）
- **25 轮最大迭代（可配置）**：工具循环上限 + 分级上下文压缩，防止无限循环和 token 爆炸

### 工具集
| 工具 | 功能 |
|------|------|
| `Read` | 读取文件，支持行号标注和页码范围 |
| `Write` | 创建/覆写文件，自动创建父目录 |
| `Edit` | 精确字符串替换，支持单次/全局替换 |
| `Grep` | 按正则表达式搜索文件内容 |
| `Glob` | 按通配符模式查找文件 |
| `Bash` | 执行 Shell 命令，支持超时控制 |
| `MemoryRead` | 读取持久化记忆 |
| `Skill` | 加载技能定义并注入 LLM 上下文 |
| `Agent` | 启动子代理（Explorer / Planner / Coder / Verifier） |

### 上下文压缩（Context Compaction）
三层压缩机制，防止上下文窗口溢出：

| 层级 | 触发时机 | 策略 |
|------|----------|------|
| **Snip** | 工具结果超长时 | 截断过长的工具输出，插入摘要占位 |
| **Auto** | 上下文占用超过阈值 | 主动将历史消息压缩进 working memory |
| **Reactive** | API 返回上下文超限错误 | 应急压缩后自动重试 |

### 智能模型路由
- **多模型健康检查**：周期性评估各模型可用状态
- **熔断 + Fallback**：当前模型不可用时自动降级到备选模型
- **退避冷却**：被标记为不健康的模型进入冷却期后自动恢复
- **路由事件监听**：通过 `RoutingEventListener` 观察路由决策过程

### 权限管控

```
PermissionMode（ask / full_access）→ PreToolUse Hook 拦截 → 工具执行
```

- **权限模式**：`ask` 模式（默认）对高风险操作请求确认；`full_access` 模式允许全部操作自动通过。可通过 `/permissions` 切换
- **批量/子代理专用模式**：`batch_worker`、`subagent_read_only`、`subagent_coder`、`subagent_verifier` 等模式由系统内部使用，限制子代理权限
- **Hook 拦截**：settings.json 中的 PreToolUse 处理器可前置拦截任意工具调用，实现自定义权限策略

### Agent 系统（子代理）

主 Agent（即 CodeFlow 自身）可以通过 `Agent` 工具派生子代理来处理复杂任务。每个子代理是一个独立的 QueryEngine 实例，拥有受限的工具集和独立的执行上下文。

#### 四种子代理类型

| 类型 | 默认模式 | Worktree 隔离 | 可用工具 | 职责 |
|------|----------|:---:|------|------|
| **Explorer** | 前台 | ✗ | Read, Grep, Glob, Bash | 只读搜索与代码理解，不修改文件 |
| **Planner** | 前台 | ✗ | Read, Grep, Glob, Bash | 研究代码库并产出批量执行计划 |
| **Coder** | 后台 | ✓ | 全部工具 | 在隔离的 git worktree 中编写代码 |
| **Verifier** | 后台 | ✗ | Read, Grep, Glob, Bash | 验证 Coder 产出：运行测试、检查逻辑、审查变更 |

#### 执行模式

- **前台执行**（`run_in_background: false`）：主 Agent 阻塞等待子代理完成，结果直接返回给 LLM。适合 Explorer/Planner 等需要即时结果的场景
- **后台执行**（`run_in_background: true`）：子代理异步运行，立即返回 `agentId`。主 Agent 可通过 `/agent` 查看状态。适合 Coder/Verifier 等耗时较长的编码和验证任务

#### Coder + Verifier 对审流水线

这是 CodeFlow 批量代码生成的核心质量保障机制：

```
1. Planner/用户 拆解任务 → 生成 BatchPlan（多个 BatchWorkUnit）
2. 每个 WorkUnit 启动一个 Coder（后台，独立 worktree）
3. Coder 完成后自动触发 Verifier（后台，读取 Coder 的 worktree 只读验证）
4. Verifier 产出 verdict（通过/失败）和 testSummary
5. 后台子代理完成后结果记录在 `BackgroundTaskRegistry`，主 Agent 可在后续轮次中获取
```

关键设计：
- **Coder 必须运行在 worktree 隔离环境**：每个 Coder 获得独立的 git worktree，避免并行任务相互覆盖文件
- **Verifier 只读访问 Coder worktree**：Verifier 只能使用 Read/Grep/Glob/Bash，不能修改任何文件，确保验证的客观性
- **自动串联**：Coder 完成后自动启动 Verifier，无需主 Agent 介入。若未配置 Verifier，Coder 完成即结束
- **取消传播**：用户 Ctrl+C 打断主 Agent 时，后台子代理也会收到取消信号

#### Agent 工具调用参数

LLM 通过 `Agent` 工具发起子代理调用，参数如下：

| 参数 | 必需 | 说明 |
|------|:---:|------|
| `prompt` | 是 | 子代理的完整任务描述，应该是自包含的 |
| `subagent_type` | 否 | 类型：`Explorer` / `Planner` / `Coder` / `Verifier`，默认 `Coder` |
| `description` | 否 | 简短任务描述，作为子代理的显示名称 |
| `run_in_background` | 否 | 是否后台运行。Explorer/Planner 默认前台，Coder/Verifier 默认后台 |
| `isolation` | 否 | 仅 Coder 支持 `"worktree"`，其他类型传入会报错 |
| `target_agent_id` | 否 | 仅 Verifier 使用，指定要验证的 Coder 的 agentId |

#### 工具边界隔离

每个子代理类型的工具集是硬编码的，不受主 Agent 权限配置影响：

- **Explorer / Planner**：`Read`、`Grep`、`Glob`、`Bash`（只读四件套）
- **Verifier**：同上，确保只读验证
- **Coder**：继承主 Agent 的全部工具（因为需要在 worktree 中自由编写代码）

子代理内部也有独立的权限管控和迭代上限，但其 PreToolUse/Stop Hook 均被禁用（`none()`），避免子代理执行时弹出交互式确认。

### Skills 系统
- **斜杠命令**：终端 `/skill-name` 触发技能调用
- **模型自动调用**：LLM 识别到匹配场景时主动加载技能
- **SkillStore**：从 `~/.codeflow/skills/` 和项目目录下的 `.codeflow/skills/` 加载技能定义

### Hooks 系统
- **PreToolUse**：工具执行前拦截，可决定 allow/deny/ask
- **Stop**：每次 LLM 回答结束时触发，可根据输出内容执行自动反馈
- **CommandStopHookHandler**：通过 settings.json 配置命令式 Stop Hook

### Memory 系统
- **分层记忆**：L0（手动规则，`memory_rules.md`）→ L1（索引，`index.txt`）→ L2（长期事实）→ L3（反思洞察），存储在 `~/.codeflow/memory/` 下
- **MEMORY.md**：每个记忆文件使用 Markdown 格式，包含 frontmatter 元数据和正文
- **MemoryReflection**：自动对会话内容进行反思，提取值得长期保存的洞察
- **MemoryUpdateConfirmer**：记忆变更前向用户确认（即使 Full Access 模式下也需要确认）
- **TranscriptRecorder**：L5 完整回话记录器

### 终端体验
- **JLine 3 行编辑器**：支持多行输入、语法高亮、历史搜索
- **斜杠命令补全**：Tab 键自动补全 slash commands
- **流式输出**：LLM 响应逐 token 打印，实时感知进度
- **后台任务管理**：`/agent` 查看运行中的子代理，`/batch` 管理批量任务

## 项目结构

```
src/main/java/com/codeflow/
├── app/                          # 应用入口层
│   └── CodeFlow.java             # DI 装配 + main()
├── terminal/                     # 终端交互层
│   ├── TerminalSession.java      # 主 REPL 会话
│   ├── SlashLineReader.java      # JLine 行读取（斜杠补全）
│   ├── SlashCommand.java         # 斜杠命令定义
│   └── SlashCommandRegistry.java # 斜杠命令注册表
├── core/                         # 核心运行时层
│   ├── QueryEngine.java          # "输入 → LLM → 工具" 主循环
│   ├── Message.java              # 消息模型（sealed interface）
│   ├── ConversationSession.java  # 会话上下文
│   ├── WorkingMemory.java        # 工作记忆
│   ├── StreamingToolExecutor.java# 流式工具执行器
│   └── CancellationToken.java    # 用户中断令牌
├── llm/                          # LLM 集成层
│   ├── LLMClient.java            # LLM 客户端接口
│   └── AnthropicClient.java      # Anthropic API（官方 SDK）实现
├── routing/                      # 智能路由层
│   ├── RoutingLLMClient.java     # 带路由的 LLM 客户端
│   ├── FallbackPolicy.java       # 熔断回退策略
│   ├── ModelHealthRegistry.java  # 模型健康注册表
│   └── RoutingEventListener.java # 路由事件监听
├── compact/                      # 上下文压缩层
│   ├── CompactionManager.java    # 压缩管理器（编排三层压缩）
│   ├── SnipCompactor.java        # Snip 压缩（截断工具结果）
│   ├── AutoCompactor.java        # Auto 压缩（主动摘要）
│   ├── ReactiveCompactor.java    # Reactive 压缩（应急重试）
│   └── TokenEstimator.java       # Token 估算器
├── tools/                        # 工具层
│   ├── Tool.java                 # 工具接口
│   ├── ReadTool.java / WriteTool.java / EditTool.java
│   ├── BashTool.java / GrepTool.java / GlobTool.java
│   ├── MemoryReadTool.java / SkillTool.java / AgentTool.java
│   ├── ToolExecutionContext.java # 工具执行上下文
│   └── ToolInputValidator.java   # 工具输入校验
├── agents/                       # 子代理系统
│   ├── AgentDefinition.java      # Agent 定义
│   ├── AgentInvocation.java      # Agent 调用
│   ├── AgentResult.java          # Agent 结果
│   ├── AgentResultParser.java    # Agent 输出解析
│   ├── SubagentRunner.java       # 子代理运行器
│   └── AgentTool.java            # Agent 工具（暴露给主 Agent）
├── batch/                        # 批量协调
│   ├── BatchCoordinator.java     # 批量子代理协调器
│   ├── BatchPlan.java            # 批量计划
│   └── BatchWorkUnit.java        # 工作单元
├── permissions/                  # 权限管理
│   ├── ToolPermissionManager.java
│   ├── ToolPermissionConfig.java
│   └── ToolPermissionConfirmer.java
├── hooks/                        # Hook 系统
│   ├── PreToolUseHandler.java    # 工具执行前 Hook
│   ├── StopHookHandler.java      # LLM 回答结束 Hook
│   └── CommandStopHookHandler.java
├── memory/                       # 记忆系统
│   ├── MemoryStore.java          # 记忆持久化
│   ├── MemoryContextProvider.java# 记忆上下文注入
│   ├── MemoryReflection.java     # 记忆反思
│   └── TranscriptRecorder.java   # 回话记录器（L5）
├── skills/                       # 技能系统
│   ├── SkillStore.java           # 技能加载
│   ├── SkillRenderer.java        # 技能渲染（注入 LLM 上下文）
│   └── SkillDefinition.java      # 技能定义模型
├── tasks/                        # 后台任务
│   └── BackgroundTaskRegistry.java
├── worktree/                     # Git Worktree 隔离
│   ├── WorktreeService.java
│   └── WorktreeSession.java
├── config/                       # 配置管理
│   ├── ConfigManager.java        # ~/.codeflow/settings.json 读写
│   └── Settings.java             # 强类型配置模型 + 校验
└── util/
    └── Console.java              # 终端格式化输出
```

**统计**：92 个 Java 源文件，约 11,500 行代码。

## 技术栈

| 组件 | 选型 | 说明 |
|------|------|------|
| 语言 | Java 21 | Records、Sealed Interfaces、Pattern Matching、Virtual Threads（Preview） |
| 构建 | Gradle 8.x (Kotlin DSL) | Application Plugin 提供 `./gradlew run` |
| LLM SDK | Anthropic Java SDK 2.40.1 | 官方 SDK，支持 raw stream |
| 流式处理 | Reactor Core 3.8.6 | push-style 事件传递，背压支持 |
| 终端 | JLine 3.28.0 | 行编辑、语法高亮、补全 |
| JSON | Jackson 2.17.0 + JSR310 | 配置序列化、API 请求/响应 |
| 日志 | SLF4J Simple 2.0.13 | 运行时日志（默认关闭终端输出） |
| 测试 | JUnit 5.10 + Jupiter | 单元测试 |

## 快速开始

### 环境要求
- **JDK 21+**
- **Anthropic API Key**

### 1. 配置 API Key

首次运行时，CodeFlow 会自动创建 `~/.codeflow/settings.json`，编辑该文件填入你的 API Key：

```json
{
  "api_key": "sk-ant-api03-your-key-here",
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
  "compaction": {
    "enabled": true,
    "context_window_tokens": 200000,
    "snip_tool_result_threshold_chars": 8000,
    "auto_compact_threshold_ratio": 0.8,
    "auto_compact_hot_messages": 5,
    "reactive_compact_hot_messages": 2
  },
  "routing": {
    "enabled": true,
    "retry_current_model_once": true,
    "unhealthy_cooldown_seconds": 300
  },
  "hooks": {
    "Stop": {
      "command": "",
      "timeout_seconds": 30
    }
  }
}
```

也可手动创建：

```bash
mkdir -p ~/.codeflow
cat > ~/.codeflow/settings.json << 'EOF'
{
  "api_key": "sk-ant-...",
  "base_url": "https://api.anthropic.com/v1/messages",
  "model": "A",
  "models": {
    "A": "claude-opus-4-20250514",
    "B": "claude-sonnet-4-20250514",
    "C": "claude-haiku-4-20250514"
  },
  "max_tokens": 8192,
  "max_iterations": 25
}
EOF
```

### 2. 启动

```bash
# 方式一：Gradle 直接运行
./gradlew run

# 方式二：先安装再执行（推荐，避免 Gradle 输出干扰）
./gradlew --quiet installDist
./build/install/CodeFlow/bin/CodeFlow

# 方式三：使用启动脚本（自动查找 JDK 21 + 静默安装）
./run.sh
```

### 3. 使用示例

```
CodeFlow> 读取 build.gradle.kts 的内容，告诉我有哪些依赖

CodeFlow> 在 src/main/java/com/codeflow/util/ 下创建一个 TimeUtils.java，提供友好的时间格式化方法

CodeFlow> 搜索项目中所有用到 "AnthropicClient" 的地方

CodeFlow> /permissions           # 查看/切换当前权限模式
CodeFlow> /agent                 # 查看运行中的子代理
CodeFlow> /compact               # 手动触发上下文压缩
CodeFlow> /hook                  # 查看 Hook 配置
```

## 配置参考

配置文件路径：`~/.codeflow/settings.json`

### 基础配置

| 配置项 | 类型 | 必需 | 默认值 | 说明 |
|--------|------|------|--------|------|
| `api_key` | string | 是 | - | Anthropic API 密钥 |
| `base_url` | string | 否 | `https://api.anthropic.com/v1/messages` | API 地址，可改为代理 |
| `model` | string | 否 | `"A"` | 当前使用的模型 key，必须是 `models` 中的某个 key |
| `models` | object | 否 | `{"A": "claude-opus-4-...", "B": "...", "C": "..."}` | 模型池，key→模型 ID 映射 |
| `max_tokens` | int | 否 | 8192 | 单次 API 调用的最大 token 数 |
| `max_iterations` | int | 否 | 25 | 单次查询最大工具调用轮数 |

### 智能路由（可选）

```json
{
  "routing": {
    "enabled": true,
    "retry_current_model_once": true,
    "unhealthy_cooldown_seconds": 300,
    "models": {
      "A": "claude-opus-4-20250514",
      "B": "claude-sonnet-4-20250514",
      "C": "claude-haiku-4-20250514"
    }
  }
}
```

开启后，CodeFlow 会在当前模型不可用时自动回退到 `models` 中的下一个可用模型。`model` 配置项的值对应 `models` 中的 key（如 `"A"`）。

### 权限模式（可选）

```json
{
  "permission_mode": "ask"
}
```

| 值 | 说明 |
|----|------|
| `ask` | 默认模式，对高风险操作（写文件、执行命令）弹出确认提示 |
| `full_access` | 完全访问模式，所有操作自动通过 |

### 上下文压缩（可选）

```json
{
  "compaction": {
    "enabled": true,
    "context_window_tokens": 200000,
    "snip_tool_result_threshold_chars": 8000,
    "auto_compact_threshold_ratio": 0.8,
    "auto_compact_hot_messages": 5,
    "reactive_compact_hot_messages": 2
  }
}
```

### 代理设置

如需通过代理访问 API，修改 `base_url` 指向你的代理地址即可。CodeFlow 使用 JDK 原生 `HttpClient`，支持 `http_proxy`/`https_proxy` 环境变量。

## 架构设计

### 主循环

```
用户输入 → TerminalSession
              │
              ▼
         QueryEngine
              │
     ┌────◄──┴──►────┐
     │                │
     ▼                ▼
  LLMClient     Tool.execute()
  (Anthropic)   (Read/Write/Edit/...)
     │                │
     └────►────┬──◄───┘
               ▼
        工具结果 → 下一轮 LLM 调用（直到没有工具调用或达到上限）
```

### 上下文压缩流程

```
Snip (工具结果层)        Auto (主动层)          Reactive (应急层)
───────────────        ─────────────          ─────────────────
工具输出 > 阈值          上下文占用 > 80%         API 返回超限错误
    │                       │                       │
    ▼                       ▼                       ▼
截断 + 占位摘要          压缩历史 → WM          更激进压缩 + 重试
```

### 子代理流水线

```
                        ┌── Coder-1 (worktree) ──→ Verifier-1
                        │
Planner ──→ BatchPlan ──┼── Coder-2 (worktree) ──→ Verifier-2
                        │
                        └── Coder-3 (worktree) ──→ Verifier-3
                                                    │
                                          verdict: pass / fail
```

**流程说明：**
1. **Planner**（可选）：LLM 用 Planner 子代理分析任务并产出 `BatchPlan`（拆解为多个 `BatchWorkUnit`）
2. **并行 Coder**：每个 WorkUnit 启动一个 Coder 在独立 git worktree 中编码，多 Coder 可并发执行
3. **自动 Verifier**：Coder 成功后立即启动 Verifier，只读检查 worktree 中的变更，输出 verdict
4. **结果汇总**：用户通过 `/agent` 查看各 Agent 状态、commit SHA、测试摘要和 verdict

Planner 也可以被跳过 —— 用户直接用 `/batch` 命令指定一组并行任务即可。