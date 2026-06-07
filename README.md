# AskRed — 多轮决策 Agent 原型

> 🎯 **定位**: 面试导向的原型项目，验证 Agent 工程核心概念，非生产系统。

---

## 一、项目概述

### 1.1 一句话

一个能混合检索、多轮追问、记住偏好的旅行决策 Agent。

### 1.2 核心能力

| 能力 | 实现方式 | 定位 |
|------|---------|------|
| **意图识别** | cheapModel(DeepSeek-chat, temp=0) 三分类：chat / decision / refuse | 原型已实现 |
| **混合检索** | ES BM25(match) + kNN(dense_vector) + RRF 融合排序 | 原型已实现 |
| **多轮决策引导** | ROUTE→RETRIEVE→REASON→ACT 管线；CLARIFY 发出追问后，SessionStore 持久化状态，用户回答后恢复完整上下文继续推理 | 原型已实现 |
| **三层记忆** | L1(SessionStore, 30min TTL + 定时清理) / L2(ES user_memory) / L3(ES user_profile) | 原型已实现 |
| **LLM 分层调用** | CHEAP(路由/记忆提取) / NORMAL(推理/追问) / EXPENSIVE(最终推荐) | 原型已实现 |
| **离线评估** | 5 条 preset query + LLM-as-Judge 4 维评分 | 原型骨架 |
| **数据管道** | JSONL→Loader→Profiler→Cleaner→Enricher(LLM)→Indexer(ES) | 原型已实现 |

### 1.3 项目定位

这是一个 **原型（prototype）**，目标是在 2 天内跑通 Agent 全链路，验证：
- Agent 状态机 + 多轮推理的工程可行性
- 三层记忆架构在 ES 上的落地
- 混合检索（BM25 + 向量 + RRF）效果
- LLM 分层调用策略的成本-效果平衡
- LLM-as-Judge 评估体系的基础搭建

**每项能力均标注了与生产环境的差距（见下文）。**

---

## 二、核心链路

### 2.1 用户请求 → 回复链路

```
POST /api/chat { userId, message, sessionId }
  │
  ├─ ChatController.chat(request)
  │    └─ agentGraph.execute(userId, message, sessionId)
  │
  ├─ [SessionStore] 恢复或创建会话状态
  │    ├─ 新会话: 创建 AgentState + 加载 L3 画像
  │    ├─ 已有会话: 恢复完整对话历史 + 追加当前消息
  │    └─ 过期会话: 自动清理（30min TTL + 每5min定时扫除）
  │
  ├─ [IntentRouter] 意图分类 → cheapModel
  │    ├─ chat → 跳过检索推理，直接 ACT
  │    └─ decision/refine → 继续
  │
  ├─ [HybridRetriever] ES 混合检索
  │    ├─ 生成 query embedding（美团 miffy-002, 1024维）
  │    ├─ BM25 match on textForEmbedding
  │    ├─ kNN vector search on dense_vector field
  │    └─ RRF 融合排序（windowSize=50）→ top 10
  │
  ├─ [DecisionReasoner] 推理决策 → normalModel
  │    ├─ 缺关键信息 → CLARIFY, 记录 missingInfo
  │    └─ 信息充足 → RECOMMEND
  │
  ├─ [ResponseActor] 生成回复
  │    ├─ CLARIFY → normalModel("温和追问2-3个问题")
  │    └─ RECOMMEND → expensiveModel("基于笔记Top5生成推荐")
  │
  ├─ [SessionStore] 持久化会话状态（支撑多轮）
  │
  └─ [MemoryManager] 偏好记忆
       ├─ cheapModel 提取偏好 → L2 user_memory (append)
       └─ 聚合 L2 最近 50 条 → L3 user_profile (upsert)
```

#### 架构图

```
POST /api/chat
      │
      ▼
┌─────────────────────────────────────────┐
│ AgentGraph (状态机编排 + SessionStore)    │
│                                          │
│ ROUTE ──→ RETRIEVE ──→ REASON ──→ ACT  │
│   ↑        (BM25+向量+RRF)               │
│   └── CLARIFY 后用户回答，重新进入 ──────┘ │
└──────────┬──────────────────────────────┘
           │
  ┌────────┼────────┐
  ▼        ▼        ▼
ES 检索   记忆系统   LLM 推理
(混合检索) (L1+L2+L3) (DeepSeek 三层)
```

#### 与生产环境的差距

| 维度 | 当前 | 生产 |
|------|------|------|
| 状态持久化 | ConcurrentHashMap + 定时清理 | Redis SETEX + TTL |
| 向量检索 | 实时 embedding（每次请求调 API） | 离线预计算 + 缓存 |
| 执行模式 | 同步阻塞 | 关键路径同步，记忆/评估异步 |
| 错误处理 | 异常冒泡 → HTTP 500 | 重试+降级+熔断 |
| 可观测性 | slf4j 控制台日志 | OpenTelemetry trace + span |
| 限流 | 无 | 单用户 QPS + LLM API 并发限制 |


### 2.2 记忆系统

```
三层: L1(会话) → L2(偏好事件) → L3(画像快照)

L1 — SessionStore (ConcurrentHashMap + ScheduledExecutorService)
  ├─ key: sessionId, value: SessionEntry{AgentState, timestamp}
  ├─ TTL: 30 分钟无操作自动过期
  ├─ 清理: 每 5 分钟定时扫除 + load() 时惰性检查
  ├─ 续期: 每次 load() 刷新时间戳（活跃 session 不过期）
  └─ 作用: 让 Agent 记住上下文（解决多轮对话状态丢失问题）

L2 — ES user_memory 索引
  ├─ 粒度: 逐条原子偏好 {userId, type, key, value, confidence}
  ├─ 写入: 每次对话 LLM 提取 → append（不覆盖）
  ├─ 作用: 事件溯源，保留偏好变化轨迹，支持偏好漂移检测
  └─ 聚合窗口: 最近 50 条参与 L3 重建

L3 — ES user_profile 索引
  ├─ 粒度: 一条聚合快照 per user（_id=userId）
  ├─ 写入: 每次对话后 rebuildProfile() 覆盖写
  ├─ 读取: 每次请求 loadProfile() O(1) 单文档
  └─ 字段: preferredDestinations[], budgetTier, travelStyle[], typicalDuration, companion
```

#### 与生产环境的差距

| 维度 | 当前 | 生产 |
|------|------|------|
| L1 持久化 | ConcurrentHashMap + daemon 线程清理 | Redis 集群 + 内置 TTL |
| L2 置信度 | 固定 1.0 | 按来源/频次加权 |
| L2 偏好衰减 | 等权聚合 | 时间衰减 + 频次加权 |
| L3 更新策略 | 每次全量重建 | 增量更新 + 定时批量 |
| 记忆惯性 | 无检测 | 偏好漂移检测 + 探索性推荐 |


### 2.3 评估体系

```
GET /api/eval
  → Evaluator.evaluate(EvalQuery.preset())
    → 5 条 query 逐条走 AgentGraph
      → cheapModel 4 维评分 → pass = 总分 ≥ 12/20
```

#### 5 条预设评估 query

| query | 期望覆盖 |
|-------|---------|
| 我想去巴厘岛玩5天，预算5000，喜欢拍照，一个人 | 具体地点、拍照、预算、单人 |
| 大理和丽江哪个更适合情侣？ | 对比、情侣元素、具体建议 |
| 推荐一个适合带父母去的地方，不用走太多路 | 适合老人、行程轻松、具体地点 |
| 京都有什么必去的寺庙？ | 寺庙推荐、具体信息、实用建议 |
| 曼谷哪里吃海鲜性价比高？ | 海鲜、价格、具体地点 |

#### 与生产环境的差距

| 维度 | 当前 | 生产 |
|------|------|------|
| 测试集 | 5 条 hardcoded | 30-50 Golden Set + 20 Regression Suite + 10 Scenario |
| 评分器 | cheapModel 单模型评 4 维 | 主评分器 + 校准器（不同模型族）+ 20% 人工抽检 |
| 确定性检查 | 无 | 格式检查 + 幻觉检测（笔记ID是否在检索结果中） |
| 在线评估 | 无 | 异步采样 10% 流量 + thumbs up/down |
| 结果持久化 | ❌ | 按 trace_id 存档，支持历史趋势对比 |
| CI 集成 | ❌ | PR 触发 eval，Golden < 85% / Regression 退化 → 阻断 |
| 反馈闭环 | 无 | 低分 trace 聚类 → issue → 修复 → 加入 Regression Suite |


## 三、技术栈

| 组件 | 选型 | 说明 |
|------|------|------|
| Java | 17+ | record 不可变数据模型 |
| Spring Boot | 3.4 | REST API + DI |
| LangChain4j | 1.13.0 | LLM 调用（OpenAI 兼容适配 DeepSeek） |
| Elasticsearch | 7.17 | 笔记混合检索（BM25+kNN+RRF）+ 用户记忆存储 |
| DeepSeek | chat / reasoner | 三层调用策略 |
| Embedding | 美团 aigc (miffy-002) | 1024 维，HTTP API |
| 构建 | Gradle 8.x | — |


## 四、项目结构

```
agent_demo/
├── build.gradle
├── data/
│   ├── raw/              # 原始笔记 JSONL
│   ├── cleaned/          # 清洗后数据
│   └── reports/          # 质量报告
└── src/main/java/askred/
    ├── AskRedApplication.java
    ├── pipeline/          # 数据管道（5 步：Load→Profile→Clean→Enrich→Index）
    │   ├── RawNote.java / CleanedNote.java
    │   ├── NoteLoader / QualityProfiler / NoteCleaner
    │   ├── NoteEnricher / EsIndexer
    │   └── PipelineRunner.java (main)
    ├── agent/             # Agent 核心
    │   ├── AgentGraph.java       # 编排器 + SessionStore (TTL+定时清理)
    │   ├── AgentState.java       # 状态对象 + UserProfile
    │   ├── IntentRouter.java     # 意图路由 (cheapModel)
    │   ├── HybridRetriever.java  # ES 混合检索 (BM25 + kNN + RRF)
    │   ├── DecisionReasoner.java # 推理决策 (normalModel)
    │   ├── ResponseActor.java    # 回复生成 (normal/expensive)
    │   └── MemoryManager.java    # 三层记忆管理
    ├── llm/
    │   └── LlmConfig.java        # LLM 配置 + MeituanEmbedder
    ├── controller/
    │   └── ChatController.java   # POST /api/chat, GET /api/eval
    ├── eval/
    │   ├── EvalQuery.java        # 5 条预设评估 query
    │   └── Evaluator.java        # LLM-as-Judge 4 维评分
    └── util/
        └── RateLimiter.java      # Embedding 限流器
```


## 五、叙事线

### 30 秒

> 我用 Java + ES + DeepSeek 做了一个多轮决策 Agent。核心是混合检索（BM25+向量+RRF） + 三层记忆（会话/偏好事件/画像快照）+ 状态机回环——CLARIFY 发出追问后，用户回答能带上下文重新推理。

### 1 分钟

> 技术栈选 Java 是因为我 5 年经验加速度，ES 直接复用搜索平台经验。检索做了 BM25+kNN+RRF 混合，不是简单的关键词匹配。LLM 三层分层调用——路由用便宜模型、推理用中等、生成推荐用贵模型。
>
> 状态机加了 SessionStore 做跨请求状态恢复（30min TTL + 定时清理僵尸 session），解决了多轮对话的"失忆"问题。记忆系统三层——会话级(in-memory)、偏好事件流(ES append)、画像快照(ES O(1)读)——本质是事件溯源 + 物化视图模式。
>
> 评估体系用 LLM-as-Judge 搭了骨架，能回答"改了 prompt 后效果变没变"。


## 六、快速启动

```bash
# 1. 启动 ES（确保 localhost:9200 可用）
curl http://localhost:9200

# 2. 配置 DeepSeek API key
#    编辑 src/main/resources/application.properties 中的 DEEPSEEK_API_KEY

# 3. 启动（首次自动导入笔记数据到 ES）
./gradlew bootRun

# 4. 验证对话
curl -X POST localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"userId":"test","message":"我想去巴厘岛玩5天，预算5000"}'

# 5. 运行评估
curl http://localhost:8080/api/eval
```


## 七、已知限制

1. **向量检索每次实时调 API**，生产应离线预计算 + 缓存
2. **单机内存 SessionStore**，不可扩展，生产需 Redis
3. **无在线评估**，无法感知生产用户真实满意度
4. **同步阻塞执行**，记忆保存阻塞用户回复
5. **无错误降级**，LLM/ES 异常直接 500
6. **ES 单机部署**，无高可用
