# Know Engine：Spring MVC + Reactor 显式 RAG 改造计划

制定日期：2026-11-10（Asia/Shanghai）

适用工程：D:/IdeaProject/know-engine

核对基线：48b778053d55a056a5443dc55a22e4108fb11754（新增超时时间）。制定计划前工作区干净。实施前必须重新核对 HEAD 和工作区，以实际代码为准；不要覆盖用户后续改动。

本文是交给实现者的工程约定，不是已完成改造的说明。用户已经选定 **保留 Spring MVC + Reactor、显式编排 RAG**。目标是合理性、鲁棒性、可扩展性和可理解性，不能以减少代码行数为目标删掉必要的执行保护。

## 1. 已确定的决策与范围

### 1.1 保留

- Spring Boot 3.5.6、Java 21、Spring MVC、Reactor；本次不升级技术栈。
- MyBatis/JDBC、现有 MySQL/Redis/Elasticsearch/S3 和文档版本体系。
- 显式的改写、并行双路检索、RRF、可选重排、父块扩展、模型生成。
- 当前轮只读取一次生效版本范围，两个检索通道共用它；范围读取失败不得扩大成全索引检索。
- RRF 作为基础排序与重排不可用时的降级结果。
- 当前有界执行器、依赖隔离、HTTP 连接取消、超时和容量保护的语义。
- 历史快照、意图识别临时记忆、离线评测的独立准入限制。
- LangChain4j 低层模型客户端可以继续使用，通过窄适配层隔离。
- 当前 AUTO 意图分流行为；新增明确的 RAG/COMMON 模式用于可控调用和评测，不悄悄改变默认产品行为。

### 1.2 必须改造

- 用业务数据类型代替在线主链中的多 Query 嵌套 Map 和框架 Content 判等技巧。
- 用结构化 ChatEvent 代替字符串前缀协议。
- 建立一轮请求的持久状态、幂等创建、显式取消、统一收尾。
- 将取消传播到活动的模型/检索请求，正确处理首字前取消和取消与完成竞争。
- 区分依赖阶段超时、准备超时、首字超时、文本空闲超时、总截止时间。
- 明确每类故障是否允许降级；用户取消和总截止时间不能触发继续生成。
- 保存成功、取消、失败和超时的消息状态及已生成内容；前端断开不能取消必要的收尾保存。
- 明确模型正常完成信号；不能把方法返回、HTTP EOF 或已输出部分文本等同于生成成功。
- 评测直接消费结构化业务结果，不再解析 SSE 前缀、创建临时聊天记录后回查引用。
- 配置 MVC 异步写出和有界缓冲；测试真实 Servlet/SSE 断开路径。

### 1.3 本轮不做

- 不改为 SseEmitter + 虚拟线程主方案，不迁移整个应用到 WebFlux/R2DBC。
- 不引入 LangGraph、AgentScope、通用 DAG/节点 DSL、消息队列或分布式工作流。
- 不重建用户认证系统。沿用 DefaultUser 的身份约定，增加归属一致性校验，但不宣称客户端可传 userId 等于安全认证。
- 不同时重写所有模型协议。先把当前客户端行为封装正确，原生响应式模型传输作为后续可替换实现。
- 不修改文档解析、上传发布、索引构建流程和云端拓扑。
- 不自动对现有云数据库执行 DDL，不停止共享服务。交付增量迁移 SQL、测试与操作说明；部署是独立步骤。
- 不承诺进程崩溃后恢复模型生成、不承诺断线续传或远端供应商立即停止计费。

## 2. 当前代码核验结果

### 2.1 阅读入口

以下路径均相对项目根；以方法名定位，行号会随实施变化。

| 文件/方法 | 当前行为 | 实施动作 |
| --- | --- | --- |
| src/main/java/know/engine/chat/service/ChatService.java：chat、rag、retrieve、rank、generate | 已显式编排；返回 Flux<String>；正常完成后保存 | 保留入口职责，抽出检索和模型适配，接入生命周期 |
| chat/service/OnlineChatWork.java | 每阶段有界池、熔断、超时、取消注册、计数 | 保留已验证保护，补类型化错误、动态预算和独立收尾容量 |
| ai/config/OnlineModelHttpClient.java | 阻塞读取 SSE；网络 I/O 前注册 request.cancel | 保留并验证；收紧模型正常完成契约 |
| rag/KnowEngineQueryTransformer.java | 普通 rewrite 方法，无历史时由应用跳过 | 保留纯改写职责 |
| rag/KnowEngineElasticsearchContentRetriever.java | ES async future 转阻塞等待；版本过滤；每路 size=10 | 改成返回明确的检索结果类型，保留传输取消与超时 |
| rag/KnowEngineReRankingContentAggregator.java | RRF 后全部候选重排；仍实现框架聚合接口 | 改为普通业务算法/服务，删除旧 Map 入参 |
| rag/reranker/KnowEngineReciprocalRankFuser.java | 基于内容对象判等累加排名，合并来源 | 改用显式片段标识，保存可解释的分数 |
| rag/reranker/KnowEngineDefaultContent.java | 以 EMBEDDING_ID 重写 equals/hashCode | 新数据类型替代后删除 |
| rag/reranker/DashScopeScoringModel.java | 批量打分、索引对齐、异常结果校验 | 保留校验，通过重排适配器调用 |
| chat/memory/DatabaseChatMemoryStore.java：loadHistory | 按当前消息 ID 边界读历史 | 增加完整轮次过滤，保留一次快照语义 |
| chat/service/impl/ChatMessageServiceImpl.java | 单项更新、无轮次终态；部分旧路径仍按“最后两条”排除 | 新终态事务与检查点；新主链不使用猜测最后两条的读取方式 |
| chat/entity/ChatMessage.java | 无 runId/运行状态；引用的子块与上下文文本未分离 | 增加 runId 关联；通过 DTO 展示运行状态和新引用结构 |
| src/main/resources/static/chat.html | fetch 读 SSE；无 AbortController/停止接口；末尾统一完成 UI | 切换新协议，增加停止和明确终态 |
| eval/service/EvalInferenceService.java | 收集字符串流，回查临时消息，删除临时会话 | 直接用结构化结果 |
| chat/controller/ChatConversationController.java：deleteConversation | 不检查活动生成，先删消息再删会话 | 活动轮次拒绝删除；删除持久记录使用事务 |
| src/main/resources/sql/schema.sql | 无聊天运行记录 | 增量迁移 + 同步新库结构 |

表中 chat/、rag/、ai/、eval/ 均位于 src/main/java/know/engine/。

### 2.2 不再沿用旧文档的事实

- 当前在线链不再通过 RagChatService + DefaultRetrievalAugmentor 驱动。
- 当前是 DashScope API 重排，不是本地 BGE/ONNX。
- 每路最多 10 条；现调用重排后最多 10 条，扩展父块后最多 5 条。
- 调用方没有配置 BGE 文档中的 minScore=0.6。
- rank 已在重排失败/关闭时使用 RRF 结果，因此保留 RRF 有明确理由。
- 问题改写已经不写数据库，意图识别已经使用临时记忆，不能把它们描述成尚待修复的问题。
- 当前模型传输依然是阻塞式；使用 Flux 并不意味着端到端非阻塞。

### 2.3 已执行的基线验证

2026-11-10 使用项目内 JDK 21、离线 Maven 执行 ChatResilienceTest，5 项通过，0 失败、0 错误：

1. embedding 超时和 rerank 失败时仍使用关键词证据；
2. 两路检索都不可用时不调用生成模型；
3. 首字超时时在上游返回响应头之前关闭连接；
4. 取消订阅不会提前释放仍被不响应中断的调用占据的工作线程；
5. Druid 等待/连接/查询限制绑定正确。

这不是浏览器端停止、并发终态或真实模型质量的验收结果。实现者需要重新运行，并扩展第 12 节的测试。

## 3. 目标结构与 Reactor 使用约定

### 3.1 按职责组织，避免再造框架

可沿用 ChatService 作为应用编排入口，不强制为了命名新增 ChatApplicationService。建议的少数职责如下：

| 组件 | 责任 |
| --- | --- |
| ChatController | 创建轮次、打开流、取消、查询状态；映射 HTTP 与 SSE |
| ChatService | AUTO/RAG/COMMON 路由及显式阶段编排，返回业务事件 |
| RagRetrievalService | 改写、共享范围、双路检索、融合、重排、上下文组装 |
| RrfFuser/纯函数 | 对已经在内存中的候选计算 RRF、去重、稳定排序 |
| ChatModelGateway | 把模型调用适配为有明确结束契约的 Flux<ModelEvent> |
| ChatRunService/生命周期对象 | 幂等、状态竞争、取消句柄、最终保存、恢复查询 |
| OnlineChatWork | 阻塞工作隔离、阶段容量、传输取消、限时与计数 |
| 现有消息/会话 Service | 数据库读写和事务；不拼前端事件 |

这些是职责边界，不要求一行方法一个接口。只有外部依赖适配、业务结果和生命周期需要稳定边界。

主线示意：

~~~text
建立本轮与历史快照
  → 按模式识别是否检索
  → 按需改写
  → 读取一次生效版本范围
  → 关键词 / embedding+向量并行
  → RRF 融合
  → rerank（可降级）
  → 父块扩展、去重、上下文预算
  → 发资料事件
  → 模型文本流
  → 统一终态保存
  → 已保存的 end 事件
~~~

### 3.2 类型边界

- 外部一次性操作用 Mono<T>，持续事件用 Flux<T>。
- RRF、去重、排序、已有文本的整理用普通 Java 方法。
- 建议结果类型：RetrievalBranch、Candidate、PreparedContext、GenerationResult、ChatEvent、ChatRunOutcome。
- RetrievalBranch 显式区分 SUCCESS（可以为空）、DISABLED、UNAVAILABLE；不得用 Mono.empty 导致 zip 意外提前完成。
- Candidate 用 ChunkKey(docId, versionId, chunkId) 做身份；ES 文档 ID单独保留并验证映射。
- Candidate 保存 childText、parentChunkId、来源集合、各路排名/原分、rrfScore、可空 rerankScore。
- PreparedContext 保存实际发给模型的 ContextItem 和引用映射；不要复用 childText 字段承载父块文本。

### 3.3 Reactor 不变量

1. 工作在订阅时启动；使用 defer/fromCallable，避免组装 Publisher 时提前访问 DB/模型。
2. MVC 订阅返回的流。主编排中不嵌套随意 subscribe，不调用 block；唯一保留的后台订阅需有明确所有者、停止/错误处理（例如独立标题任务）。
3. 阻塞 DB/SDK 调用使用现有受控执行器；不得在 parallel 或非阻塞事件循环里做 JDBC/阻塞 HTTP。
4. 一个 run 只允许一次执行订阅。日志、状态查询、心跳、评测不能再订阅同一冷源而重新生成。
5. 并行只发生在明确独立的工作之间；资料选择、序号分配、终态保存有确定顺序。
6. async 事务边界必须包住同一数据库工作线程内的同步事务操作；不能认为包着返回 Flux 的 @Transactional 覆盖整个异步生命周期。
7. 取消/错误/完成清理优先用 usingWhen 或等价的可验证生命周期组合。doFinally 只做同步且幂等的轻量清理/指标，不能靠 doFinally 内临时 subscribe 实现关键异步落库。
8. 若因 MVC 断开收尾需要一个集中管理的异步 finalizer，它必须有有界容量、错误处理、停止清理和测试，不允许散落的 fire-and-forget。
9. 所有新 Reactor API 必须检查当前 Spring Boot 管理的实际版本；不照抄最新文档里本版本没有的 API。

## 4. RRF、重排与上下文的确定策略

### 4.1 改写与检索

- 保留无历史时直接用原问题；多轮改写补全指代、保留产品名/编号/状态，避免无条件抽象化丢细节。
- 改写失败或阶段超时时，在整轮预算仍有效且未取消的前提下，用最近一条完整历史用户问题 + 本次问题作为回退；无历史则原问题。记录 REWRITE_UNAVAILABLE。
- 使用同一份版本范围和查询文本执行双路；保留各自原始排名。
- 每路初始 topK=10；向量 ES 分数阈值先保留现值 0.5，明确它是检索层阈值，不能当成最终相关性置信度。
- 一路成功（即使为空），另一路失败：继续使用成功路，记录降级；两路都无法执行：FAILED/RETRIEVAL_UNAVAILABLE，不调用生成模型。
- 范围为空、两路正常但无命中，属于 NO_EVIDENCE，不是基础设施失败。以固定“当前资料不足”回答结束，不调用无资料的 RAG 生成模型；保留结构化原因供评测。

### 4.2 RRF

对每个片段按不同检索路累加 1 / (60 + rank)，rank 从 1 开始。

- 每一路内部同一片段只计一次，避免重复命中虚增贡献。
- 不直接相加向量分数和 BM25 分数；两者分开保存。
- 相同 ChunkKey 合并来源与排名，版本不同不得合并。
- RRF 分数降序；同分按明确 ChunkKey 字典序等稳定规则打破平局。
- 初始 rerankMaxCandidates=20。RRF 排序后最多取该数量进入重排；当前最多 20 条，因此不为了证明算法价值额外丢候选。
- RRF 正常路径提供基础名单，重排故障/关闭时提供可用排序。文档不得声称它必然影响重排成功后的最终名次。

### 4.3 重排

- 接受明确 query + List<Candidate>，删除 Map<Query, Collection<List<Content>>> 和 querySelector 的在线依赖。
- 保留 DashScope 的索引对齐、条数、重复索引、越界、有限数值校验。
- 正常排序：rerankScore 降序、rrfScore 降序、ChunkKey 稳定序。
- 不要通过 Map<TextSegment, Score> 再次以文本/框架 metadata 做身份合并。
- 默认不开分数阈值；如果配置阈值，必须与模型版本绑定并通过评测校准。
- 只有一个候选时，阈值关闭可跳过重排；阈值开启且需要据其判定相关性时仍须评分，不能给未评分候选凭空套阈值。
- 阈值过滤后为空：NO_EVIDENCE；删除“把低于阈值的前三条再塞回去”的逻辑。
- 超时、服务端 5xx/429、熔断开启：在剩余预算允许时回退 RRF，并记录 RERANK_UNAVAILABLE。
- 响应格式异常可用 RRF 继续，但必须记录独立的协议错误指标，不能悄悄掩盖。
- 不重试用户取消/总超时；不在整个聊天流外面统一 retry。
- 不把“经过 rerank”当作检索来源；来源仍是 VECTOR/KEYWORD/BOTH，排序方式单独记。

### 4.4 父块扩展、预算和引用

- 先对子块打分，再按顺序尝试扩展父块。
- 父块 key 包含 docId、versionId、parentChunkId；仅父块实际被选入上下文后才标为已使用。
- 父块读取失败或超出可用预算时回退命中子块；不能因为尝试过某个父块就丢弃同父块的其他有效子块。
- 只扩展一次的父块可关联多个命中 childKey；保留这些关联供引用与评测。
- 不在去重前硬截断到 5 或 10 条导致去重后缺项；从排序候选中继续扫描，直到达到最多 5 个上下文项、预算耗尽或候选用完。
- 增加可配置的 prompt 总预算：系统提示 + 完整历史 + 本次问题 + 资料 + 输出预留 + 安全余量。
- 有可靠的实际模型 token 估算器时使用它；否则采用明确命名的保守字符/字节预算，记录 approximate=true，不能称为精确 token 计数。
- 必须提供保守、有限的初始预算并解释来源；不得从名称猜测模型上下文上限。超大用户输入应在请求接收时限制，不静默截掉问题关键内容。
- 历史按完整轮次由旧到新裁剪；资料按相关性放入；保持分块边界，父块放不下时优先子块。
- ContextItem 保存实际注入的 text、contextId、父块信息、全部命中 childKey；引用分数属于子块，不能伪装成父块重排分。
- 前端文档卡片可以按 docId + versionId 去重；持久化/评测保留片段级和上下文级完整映射。
- 保留旧 RagReference 字段的读取兼容，新增字段解释新记录；不要破坏历史 JSON 和评测 ground truth。
- 显示的资料是“提供给模型的参考来源”，不宣称每条都被模型实际引用或逐句验证。

## 5. HTTP 与 SSE 协议

### 5.1 新接口

采用先创建、再打开流的方式，让耗时操作开始前客户端已经持有 runId，避免首字前无法定位取消对象。

| 接口 | 行为 |
| --- | --- |
| POST /chat/runs | JSON：clientRequestId、conversationId?、content、mode?、userId?；创建 READY 轮次并返回 runId、conversationId、userMessageId、assistantMessageId、status |
| GET /chat/runs/{runId}/events | 返回 text/event-stream；仅首次成功订阅可把 READY 转为 RUNNING 并启动业务 |
| POST /chat/runs/{runId}/cancel | 请求取消 READY/RUNNING；返回当前状态、是否受理、终态是否已保存 |
| GET /chat/runs/{runId} | 查询持久状态、消息标识、已保存部分答案/保存进度、结束原因 |

约定：

- mode 默认 AUTO，可选 RAG/COMMON；RAG 模式绕过意图模型，评测使用 RAG。
- 同一 userId + clientRequestId 重试返回同一个 run；请求内容/会话/模式不同则 409，不能启动第二次模型调用。
- 绑定 conversationId 后校验其存在、未删除且归属一致。所有 run 接口都做同样的归属校验；沿用现有默认用户语义，不扩展认证系统。
- 创建只做短事务准备，不调用 LLM/ES。新标题在实际开始执行后独立生成，失败不影响问答。
- 一会话只允许一个 READY/RUNNING/FINALIZING 轮次；并发创建时通过会话行锁/事务重新检查，而不是只有 JVM check-then-put。
- READY 初始有效期 60 秒；无 SSE 订阅则持久化 CANCELLED/NOT_STARTED。READY 不占模型工作槽，但应有全局有界待启动容量。
- 在线运行准入初始保留 8；首次打开流时没有容量则 429，run 仍 READY，可在有效期内重试。
- 第二个活动流订阅返回 409；已终止 run 的事件接口不重新生成，返回 409 并让客户端查询状态/历史。
- 本轮不支持 Last-Event-ID 重放和自动断线续传。前端使用 fetch 读取，避免 EventSource 自动重连再次触发执行。
- 开始前的输入/容量错误使用真实 HTTP 4xx/5xx；响应头已发送后通过业务事件报告，不能再假装改 HTTP 状态。

### 5.2 ChatEvent 与序列

Controller 返回 Flux<ServerSentEvent<ChatEvent>>；Spring MVC 可适配此返回值。领域层只产生 ChatEvent，不拼接 SSE 文本。

统一 envelope：

~~~json
{
  "protocolVersion": 1,
  "runId": "…",
  "sequence": 7,
  "type": "delta",
  "timestamp": "ISO-8601",
  "payload": { "text": "一段回答" }
}
~~~

SSE event 名等于 type；id 可用 runId:sequence，当前只用于诊断，不代表提供重放。

| type | payload | 规则 |
| --- | --- | --- |
| meta | 会话/消息 ID、模式 | 业务开始前首先发送 |
| progress | stage、message | 阶段状态，最多发必要变化 |
| warning | code、stage、message | 可恢复降级，一种原因避免重复刷屏 |
| references | 版本化资料卡片、上下文映射 | 上下文确定后、回答文本前发送；空列表也明确表达无证据 |
| delta | text | 回答文本增量，允许任意长度和换行，不承诺一事件一 token |
| end | status、reasonCode、persisted=true、degradedStages、finishReason? | 终态事务提交后最多一次 |
| error | code=PERSISTENCE_UNAVAILABLE 等、persisted=false | 无法完成终态保存的异常路径，关闭流；不伪造 end(COMPLETED) |

正常：meta → progress/warning → references → progress → delta* → end。

取消/失败/超时：可以无 delta；只要连接仍可写且终态已保存，发对应 end。客户端已断开则不要求投递 end，持久状态是查询依据。

心跳用 SSE comment，每 10 秒一次，不写入答案、不递增业务序号、不延长文本超时。不为心跳创建第二次 RAG 订阅；业务结束立即停止计时器。

### 5.3 背压与 MVC 写出

- 配置 MVC 的 AsyncTaskExecutor 和异步请求超时；与模型/DB 工作池分离，线程和队列有明确上限。
- 初始流缓冲以 256 个事件及总字节上限（例如 1 MiB，可配置）约束；单个超大 delta 必须按 Unicode 字符边界安全切分。
- 不丢弃回答 delta、references 或 end。允许合并小 delta、合并重复 progress；心跳不应在慢消费者时积压。
- 缓冲溢出：停止上游，选择 FAILED/SLOW_CONSUMER 并保存已获得文本；客户端收不到结束事件时靠查询状态。
- TCP/Servlet 写出也可能阻塞；测试慢读客户端，配置可用的容器/代理超时。不把“缓冲有界”宣称为“写操作一定立即可中断”。
- SSE 响应关闭缓存；存在代理时记录禁用响应缓冲和合理 idle timeout 的配置方式，不能为了本次改造擅自修改云代理。
- 所有事件出口序列化，sequence 在最终出口分配；同一来源的回调不得并发修改 StringBuilder。

### 5.4 旧接口与前端迁移

- 新 chat.html 只使用新协议，停止对裸文本和 [DONE]/[WARN] 等前缀的猜测。
- 为现有 POST /chat/send 保留一个薄兼容适配器：复用同一新引擎/状态机，映射旧 progress/reference/token/DONE；只对已保存 COMPLETED 发旧 DONE，其他终态映射 WARN。
- 兼容适配器不得保留另一套检索/降级/落库实现，也不得被评测继续依赖。README 标注弃用，删除留待独立版本。
- 新页面创建成功立即更新 conversationId，不再等 DONE 才知道会话存在。
- 开始前生成 clientRequestId；创建请求网络重试复用它。
- 停止按钮：独立请求 cancel 接口，取消请求不能共用待 abort 的信号；收到受理结果后 abort 流读取。
- 取消请求失败时仍可停止页面读取，但 UI 显示“已停止接收，后台状态待确认”，随后查询状态，不伪称服务端已停止。
- READY 创建请求尚未返回时点停止：记录本地停止意图，收到 runId 后立即 cancel，不启动 events；用户离开则由 READY 过期回收。
- 前端流解析支持 UTF-8 跨块、CRLF、多行 data、JSON 换行、注释心跳；正常 EOF 但没有 end/error 时，标为意外断流并查询。
- AbortError 不覆盖已有文本为“发送失败”；失败、取消、超时保留答案，并显示状态。
- 切换会话/刷新后的旧回调不能更新新会话 DOM；使用 runId 校验回调目标并清理 reader/定时器。
- 活动会话删除返回 409，提示先停止；终止后再事务删除消息/run/会话，不增加隐式的取消并删除竞态。

## 6. 取消、状态竞争与资源生命周期

### 6.1 持久状态与内存控制分开

持久状态：

~~~text
READY ──开始──→ RUNNING ──选择结束结果──→ FINALIZING ──事务提交──→ COMPLETED
  │                         │                                  CANCELLED
  └──取消/过期───────────────┘                                  TIMED_OUT
                                                             FAILED
~~~

- FINALIZING 是本轮已选择结束结果、正在保存的过渡状态，不能重新生成。
- 内存 handle 保存：停止信号、单调时钟截止时间、活动取消句柄集合、答案快照、引用、候选终态、阶段指标、收尾完成信号。
- 用一次性的 finish gate 原子决定赢家；然后执行带条件更新的终态事务。正常完成与取消谁先成功取得 gate，谁决定候选终态。
- 正常完成仅在收到供应商明确的成功完成信号后才能竞争 gate；收到几个 delta 不算成功。
- 本地 NO_EVIDENCE 等明确的非模型回答，用本地业务完成信号竞争 gate，并记录 generationSource=LOCAL；不能为此伪造供应商回调。
- 已进入 FINALIZING 的取消返回当前状态/tooLate，不覆盖已选择结果；已持久终态的重复取消是幂等查询。
- 标记停止必须先禁止新调用，再取消现有请求；后续迟到 delta 和完成回调都被忽略/记指标。
- 显式 cancel 与页面断开均能触发停止。页面断开采用 CANCELLED/CLIENT_DISCONNECTED；超时采用 TIMED_OUT 和具体阶段原因。

### 6.2 取消句柄契约

- 每项外部工作在开始网络 I/O 前注册句柄；首字前、响应头前也必须能取消。
- 支持多个并行句柄；不能用一个 run 全局 Runnable 被另一条检索路覆盖。
- 已停止的 run 再注册句柄，要立即终止该句柄；注册和取消竞争必须测试。
- 调用完成后注销句柄，避免后续误取消别的复用请求。
- 不在持有状态锁时执行可能阻塞的网络 abort；取快照后锁外调用，各句柄失败不能阻止其他句柄关闭。
- Future.cancel(true) 只是尝试中断；必须分别确认实际 HTTP 关闭、等待线程退出及依赖配额释放。
- 保留“已取消但仍运行”的指标和容量保护。实际工作槽只在底层工作退出时释放；不因下游取消就允许无限堆积不响应中断的请求。
- 当前 OnlineChatWork 的 ThreadLocal 注册可保留在同步适配边界内；若换异步客户端，必须显式传递调用控制，不假设 ThreadLocal 自动跨线程。
- ES future 的 cancel 不一定等同于网络 abort；验证当前客户端版本的传播能力，必要时接底层可取消句柄，并以真实连接测试和 socket/request 超时兜底。

### 6.3 Reactor 收尾约定

- 正常/错误/取消共用一个幂等 finalizer。不能有“成功写一套，停止按钮再随便 update 一套”。
- 使用 usingWhen 的异步清理或等价的受控组合，让下游取消后依然执行必要的保存；不要把保存放在已被取消的 tokens.concatWith(...) 后面。
- 显式停止信号若通过 takeUntilOther 实现，不得让它产生的完成被当成生成成功；最终结果以 finish gate 的原因判断。
- 收尾拥有独立预算和保留容量，不继承已经归零的生成 deadline，不被本轮模型熔断器拒绝。
- 输出 end 不参与是否保存成功的决策：先保存，再发送。发送失败不撤销已经提交的终态。
- 不要求“所有资源瞬间释放”，但必须有可验证上界/兜底超时；不得通过隐藏超时、吞异常来通过测试。
- 注册表有界，终止条目保留短 TTL 供竞态查询后清理；活动收尾不能提前丢失 handle。详细历史以 DB 为准。

## 7. 模型流完成契约

当前 generate 的 onCompleteResponse 为空，而 OnlineChatWork 在 action.run 返回后调用 sink.complete。这个组合依赖当前 HTTP 客户端同步读流，不能继续作为通用 StreamingChatModel 契约。

目标：

- ChatModelGateway 输出 Delta 与 ProviderCompleted（含完整响应、模型信息、finishReason/usage 可得时保留）。
- SDK 的正常完成回调/协议认可的终止信号才产生 ProviderCompleted；不是方法返回或裸 EOF。
- 上游正常终止标记缺失、协议错误、内容中途截断：FAILED/UPSTREAM_PROTOCOL 或 UPSTREAM_INTERRUPTED，保留部分文本。
- 模型返回后发生迟到回调、重复 completed/error：一次性终止门处理，不能重复落库。
- 回调入口的空串不能重置首字/空闲计时；内容过滤、空回答、输出长度耗尽等 finishReason 要有明确业务映射。
- 建议长度耗尽映射 FAILED/OUTPUT_LIMIT 并保留 partial=true，不能把截断答案标作完整历史；NO_EVIDENCE 的本地回答可明确标记 COMPLETED。
- 同步读流适配器仍在受控工作线程运行，并挂载连接取消；回调异步返回的实现必须单独正确处理完成与容量，不能因 starter 方法返回就关闭 Flux。
- 不对已输出 delta 的生成过程透明重试；当前模型 HTTP 自动重试为关闭，初始保持。
- 先保留当前 LangChain4j/Apache HTTP 实现完成契约改造。后续可新增 WebClient 适配器，但不是本次必做项，也不能以此为由删除已有取消测试。

## 8. 超时、降级、容量与错误分类

### 8.1 时钟与配置

执行预算从 READY → RUNNING 成功时开始；READY 等待用单独 TTL。使用单调时钟计算剩余时间；持久时间使用实际时间戳。

| 预算 | 初始值/策略 | 语义 |
| --- | --- | --- |
| preparation-timeout | 保留 6s | 从开始执行到最终上下文准备完成 |
| first-answer-timeout | 保留 12s | 从开始执行到第一个非空回答 delta，不是第一条 SSE |
| stream-idle-timeout | 保留 10s | 生成中相邻非空 delta 的最大间隔 |
| total-timeout | 保留 45s | 整轮业务执行的绝对截止时间，不能被事件续期 |
| finalize-timeout | 初始 3s，可配置 | 停止上游后保存终态的独立上限 |
| ready-ttl | 初始 60s | 创建后没有启动时回收 |
| heartbeat-interval | 初始 10s | 探测连接与维持传输，不影响业务预算 |

每阶段实际期限 = min(阶段上限、当前准备/总预算剩余时间)，必须在实际订阅/执行时计算，排队耗时计入，不能在链组装时提前算一个过期值。

保留当前阶段限制作为初始基线：DB 1800ms、INTENT 1800ms、TITLE 2500ms、REWRITE 1400ms、EMBEDDING 1300ms、SEARCH 950ms、RERANK 1100ms、GENERATION 最多 45s。集中到配置/受校验的属性对象，避免多处 magic number。

这些值较紧，是既有运行策略，不是公认最佳参数。新增阶段耗时/降级率观测，后续按实际延迟分布调整；不能在测试失败时随意放大超时掩盖取消缺陷。

MVC async timeout 应覆盖业务总时长 + 收尾预算 + 小幅写出余量，不能比业务预算短，也不能无限等待。

### 8.2 故障分类和动作

| 场景 | 处理 |
| --- | --- |
| USER_CANCEL / CLIENT_DISCONNECTED | 立即停止，CANCELLED，禁止降级和重试 |
| 总期限/首字/空闲期限耗尽 | 停止上游，TIMED_OUT，保存部分文本 |
| 准备总期限耗尽 | TIMED_OUT；不得继续用新的预算开始生成 |
| 意图识别阶段失败 | 若整轮仍有效则按 RAG 处理，记录降级 |
| 改写阶段失败 | 整轮有效时使用明确回退查询 |
| embedding 失败或向量关闭 | 关键词路仍可执行；不能因 zip 空值丢弃它 |
| 一路 ES 查询失败 | 使用另一路成功结果，记录来源与降级 |
| 两路不可用 | FAILED/RETRIEVAL_UNAVAILABLE，不调用生成 |
| 查询正常但无资料/有效范围为空 | 本地 NO_EVIDENCE 回答，COMPLETED，明确原因 |
| rerank 阶段失败/关闭 | RRF 降级；取消或全局期限耗尽除外 |
| 已校准阈值判定全部不相关 | NO_EVIDENCE，不复活低分候选 |
| 父块读取失败 | 保留相关子块，记降级；不可吞取消 |
| 版本范围/历史/创建消息读取失败 | 失败关闭，不扩大范围或伪造空历史 |
| 模型错误/协议断流 | FAILED，保存 partial，不从头重播 |
| 检查点/最终保存失败 | 停止继续生成，独立收尾预算内有限重试；不能报告成功 |
| 标题失败 | 只影响标题，保留临时标题 |
| 用户输入非法/模式非法 | 模型调用前 HTTP 400 |
| 容量满/队列满 | HTTP 429（未建流）或明确失败事件，不无限排队 |

使用可区分的错误类型/原因码，禁止 catch(Throwable) 后统一回退；JVM 致命错误不属于业务降级。

熔断只统计相应依赖故障，用户取消、输入错误和本地准入拒绝不得算远端依赖失败。暂时保留已有轻量熔断器，补关闭/开启/半开测试，不在此次引入新韧性框架。

在线准入与离线评测准入继续独立。共享依赖池必须记录共享事实，评测在全局资源紧张时不挤占所有在线容量；本次不为此复制整套中间件。

## 9. 消息落库、迁移与故障恢复

### 9.1 最小持久模型

新增 chat_run 作为运行状态唯一事实来源，不在多个表各维护一套可独立修改的状态机。

建议字段：

- run_id：唯一轮次标识。
- user_id、conversation_id、client_request_id、request_fingerprint、mode。
- user_message_id、assistant_message_id：本轮成对消息。
- status、intended_status、finish_token、reason_code：状态、被选中的终态及幂等收尾标识。
- degraded_stages（JSON）、last_stage、model_name：必要的执行信息。
- context_snapshot（JSON）：实际注入的 ContextItem、版本/子块关联与预算信息；只保存最终选中的上下文，不保存全部召回文档或整个系统提示。
- checkpoint_sequence、partial：最后保存的内容版本、是否不完整。
- owner_instance_id、started_at、deadline_at、finished_at、created_at、updated_at、lock_version。

索引至少包括 run_id 唯一、(user_id, client_request_id) 唯一、(conversation_id, status)、(status, updated_at)。

chat_message 增加可空 run_id 和索引；新轮次两条消息都关联它。可用 (run_id, type) 唯一约束防止一个新轮次重复创建同角色消息，历史 NULL 记录不受影响。运行状态由查询 DTO 联结 chat_run 显示，不复制到 chat_message 再维护。

按实际 MySQL 方言和现有逻辑删除约定调整 SQL。字段长度、时区、JSON 空值与 MyBatis 映射必须一起验证；不要照搬示意而遗漏实体/索引。

### 9.2 事务边界

**创建事务：**

1. 校验/锁定已有会话，或创建新会话。
2. 检查 clientRequestId 幂等；同会话活动轮次冲突返回 409。
3. 插入 READY run、用户消息、助手占位消息及关联，全部成功才返回。
4. 模型和搜索不在事务内执行。

创建提交后、HTTP 响应丢失：同一个 clientRequestId 重试返回原记录。request_fingerprint 以首次提交的规范化命令计算（新会话的 conversationId 原始值为空），不能在生成会话 ID 后改变幂等输入。前端重试复用原命令。

删除过的会话/轮次不能因重复请求被复活。优先沿用逻辑删除并保留唯一键；若实际采用物理删除，必须保留有限且有明确保留期的幂等墓碑。对保留期内已删除的幂等键返回 410，不再插入新消息。

**上下文检查点：**

改写和上下文确定后保存 transformContent、选中的引用/实际上下文映射，以及降级信息。内容是本次生成依据的快照，后续切换文档版本不修改它。

**生成检查点：**

- 保存服务端已接收的文本前缀，初始每 1 秒或新增 4 KiB 触发，合并成最新快照；不逐 token 写 DB。
- 同一 run 同时最多一个检查点写入，后续快照合并，不无界排队。
- 检查点与 finalizer 通过 run 行锁/条件更新协调，只能写 RUNNING 且序号更大的快照。
- 迟到检查点不能覆盖最终答案或把状态改回 RUNNING。
- 检查点故障按持久化故障处理，停止继续消耗模型资源；不能无限只生成不保存。

**结束流程：**

1. 原子选择候选终态，冻结答案/引用快照，停止后续副作用。
2. 以 finish_token 幂等取得 DB 收尾权，将 READY/RUNNING 条件更新为 FINALIZING 并记录 intended_status。
3. 最终事务写入答案、模型信息、引用、改写、最终原因及 run 终态，更新会话时间。
4. 确认提交后才发 end(persisted=true)。重复收尾读回同一结果，不再次写入。

finish_token 及条件更新用于防止取消、模型完成、定时器、网络断开四方重复收尾；不依赖单纯的“先读状态再无条件 update”。

### 9.3 保存失败与不确定提交

- 最终保存使用独立收尾执行通道，避免所有生成/DB常规槽占满时没有容量结束请求。
- finalize-timeout 内仅对同一份幂等快照做有限重试（初始最多一次）；不重新调用模型。
- JDBC 超时/中断不一定证明事务没有提交。查询 runId + finishToken 确认结果，再决定重试；不能把已经提交的 COMPLETED 改成 FAILED。
- 若 DB 持续不可用，不发 persisted=true，不声称保证落库；连接可写则发 error(PERSISTENCE_UNAVAILABLE, persisted=false)。
- 保留有界待收敛记录与告警/日志；恢复任务只处理明确的过期 FINALIZING/残留运行状态。待收敛容量满时拒绝新请求，不把未保存答案放进无界内存队列。
- 恢复时先确认是否已有终态；无法恢复完整答案的记录标记 FAILED/RECOVERY_INCOMPLETE，保留最后检查点，禁止伪造成功。
- 这套设计不承诺 DB 全故障叠加进程崩溃时保留最后一段未持久化文本。保证的是：不假报成功，保留已有检查点，状态可收敛。

### 9.4 历史与清理

- 新轮次记忆只纳入 COMPLETED 的完整 user/assistant 配对；取消、失败、超时的部分内容默认不进入后续模型历史。
- 固定无证据回答属于已完成轮次，可以进入历史，保留 NO_EVIDENCE 原因。
- 旧 run_id=NULL 的非空历史继续可读；不把旧空助手消息自动认定为成功。迁移策略在 README 中说明。
- 按 id/轮次边界和完整配对截取历史；不再通过“跳过最后两条”猜测当前轮。
- Redis 若保留为历史缓存，写入完整终态后失效/更新；缓存不可用可从 DB 读取，不要让意图分类结果写回正式聊天记忆。
- 终态消息查询返回 runId、status、partial、reasonCode 等，前端刷新后能显示“已停止/超时/失败”及已保存文本。
- 终态会话删除时事务逻辑删除 run 和消息（或按上文保留幂等墓碑），不留可访问的孤儿记录；活动会话返回 409。
- 本轮不增加自动清空历史或无限增长的事件日志；只保存必要快照和诊断信息。

### 9.5 停机与重启

- 现有部署按单个活动 Java 实例设计。运行中的任务句柄只在本机，跨实例接管不在本轮范围。
- 关闭应用时停止接收新 run，取消活动上游，在有界宽限期内完成收尾，然后关闭执行器/HTTP/DB；测试清理顺序。
- 确认单实例前提下，启动时将上个进程残留 RUNNING/FINALIZING 收敛为失败/中断，保留检查点；过期 READY 取消。
- 不自动重发模型请求；不要扫描并误结束另一个仍在线实例的 run。
- owner_instance_id 为诊断/未来扩展保留，不意味着已支持多实例容错。

### 9.6 数据迁移交付

- 新增 src/main/resources/sql/upgrade-chat-runs.sql，另同步 schema.sql 的新库建表。
- 迁移应保留已有消息、引用、评测记录和文档数据；不重建整库，不运行旧的全库初始化文件覆盖数据。
- 提供迁移前检查、重复运行处理方式、失败后恢复说明。MySQL DDL 的隐式提交需要明确，不能承诺整个迁移事务原子回滚。
- 对 NULL run_id 的历史兼容，不为旧数据凭空制造 clientRequestId 和成功状态。
- 代码回滚应能忽略新增列/表；不要把“删新增表”当作默认回滚，避免丢掉新产生的消息状态。
- 生产/现有云库执行迁移不是实现阶段默认操作；使用专用测试库验收实际 MySQL 行为。

## 10. 评测、日志与可观测性

### 10.1 评测复用业务结果

- 将可复用执行请求定义为包含 question、history、mode 和检索配置的普通数据。
- 在线传输/会话持久化是外围适配；核心检索和生成可以被离线调用。
- EvalInferenceService 直接取得 Mono<GenerationResult> 或收集类型化模型事件，得到 answer、rewrittenQuery、候选/上下文引用、降级标志和阶段耗时。
- 评测不访问 /events，不解析字符串前缀，不为拿引用必须创建/删除临时聊天记录。
- 保留评测独立准入（当前 2），并明确共享依赖执行器的容量；不得假称完全物理隔离。
- 现有评测 API、报表页面与历史 JSON 保持可读；新增字段向后兼容。
- 同一配置下线上与离线共用同一排序、范围过滤、父块和上下文预算代码，不复制逻辑。
- 保留现有 ground-truth 的 childChunkId/parentChunkId 匹配语义；新增实际 ContextItem 指标可单独报告。
- 后续用同一数据集比较：双路+RRF、再加重排、再加改写/父块。保存配置与模型标识，不能只比较两个不同配置的单次主观回答。

### 10.2 观测

至少记录：

- runId、conversationId、stage、status、reasonCode、使用的模型标识。
- 准备/首字/文本空闲/总耗时、各依赖耗时、排队耗时。
- 两路返回数、融合数、重排数、实际上下文项/预算占用、父块回退数。
- 降级原因、依赖超时、熔断开启、准入拒绝、缓冲溢出。
- 逻辑活动轮次、实际仍运行的取消任务、活动 HTTP 连接/可用配额。
- 持久化重试/未确认提交/残留 FINALIZING 数量。

请求标识通过明确上下文或 Reactor Context 传递；MDC 跨线程不自动成立。日志不包含密钥、完整请求头、完整历史或整篇资料，默认不打印每个 token。

优先沿用现有日志/计数，不为了这次改造部署新的监控集群。

## 11. 实施顺序、改动文件和阶段验收

必须按依赖顺序小步交付，每阶段完成对应测试；允许调整类名和局部实现，但不得改变本文已确定的协议和不变量。基础阶段尚未接到前端时，不得宣称整个改造已完成。

### P0：核对基线并建立回归入口

- 阅读本文、当前 AGENTS.md（如新增）、git diff、pom.xml 和关键类。
- 重新运行既有 5 项 ChatResilienceTest；记录当前版本、命令和结果。
- 确认所有现有调用方：旧 /chat/send、chat.html、评测、记忆和删除接口。
- 增加 reactor-test（与 Boot 管理版本一致），建立独立的本地假上游与测试数据工厂。
- 不运行真实模型/云写入来替代自动化测试。

验收：既有行为可复现；测试不会依赖真实 API key 或现有云库。

### P1：收敛检索与重排

主要文件：rag/ 下检索、聚合、RRF、引用工具；ChatService 的 rewrite/retrieve/rank/expandParents；新增少量候选/上下文 DTO。

- 引入显式 Candidate/Branch/PreparedContext。
- 移除主链的框架聚合接口和特殊 equals/hashCode；保留纯 RRF 与重排 adapter。
- 实现稳定排序、阈值无证据、父块关联和总上下文预算。
- 保留原路由与返回行为的临时适配，避免新协议未完成时破坏页面。

验收：第 12 节 A 组中纯检索、排序、预算用例先通过；涉及新的取消错误分类的联动用例在 P2 完成后补齐。双路失败不生成，RRF 降级和来源追踪可解释。

### P2：整理执行控制与模型流契约

主要文件：OnlineChatWork、OnlineModelHttpClient、AiModelConfig、ChatService.generate；新增 ChatModelGateway/模型事件。

- 阶段参数集中配置；引入类型化截止/取消/依赖错误。
- 补充传输注册竞争、多句柄、正确释放工作槽和熔断分类。
- 生成成功由 ProviderCompleted 决定；处理异步 starter、异常 EOF、重复回调。
- 保留当前已验证的响应头前连接取消能力。

验收：第 12 节 B 组通过，原有 5 项回归仍通过；实际连接测试证明中断能力。

### P3：运行记录、收尾和历史

主要文件：SQL、chat/entity/mapper/service、DatabaseChatMemoryStore；新增 ChatRunService 与实体/状态。

- 编写增量迁移与新库 schema。
- 实现幂等创建、活动会话互斥、READY 过期和 finish gate。
- 实现检查点、统一终态事务、异步取消收尾、失败收敛和重启处理。
- 新历史过滤完整轮次；旧历史兼容。
- 有界 finalizer 容量独立于被取消的生成订阅。

验收：第 12 节 C 组通过；数据库事务和唯一约束使用真实 MySQL 测试，不能只用 Mockito 证明。

### P4：结构化事件与 MVC 接口

主要文件：ChatController、ChatService、MVC 异步配置；新增 ChatEvent DTO 和 HTTP 请求/响应 DTO。

- 上线新的 create/events/cancel/status 路由。
- 正确处理首次订阅、重复请求、订阅竞争、断开、心跳、慢消费者。
- 事件最终序列化、JSON 转义与终态顺序。
- /chat/send 改成薄兼容层，删除旧独立执行路径。

验收：第 12 节 D 组通过；真实嵌入式 Servlet 容器验证响应、断开和异步错误。MockMvc 只补接口逻辑，不能替代网络测试。

### P5：前端停止与状态展示

主要文件：static/chat.html，可提取 chat-stream.js；少量 chat.css。

- 改用新 create + events API，加入停止按钮和独立 AbortController。
- 增加结构化 SSE parser、无终态断流处理、状态查询和 READY 提前停止。
- 保留部分文本、引用和明确状态；杜绝旧请求更新新会话。
- 活动会话禁止删除，完成后列表和时间更新正确。

验收：第 12 节 E 组通过；至少实际浏览器验证开始、停止、刷新、错误、切换会话。

### P6：评测与遗留清理

主要文件：EvalInferenceService、评测结果映射、旧 RagChatService/CommonChatService/ProgressAware 类、相关配置与 pom.xml。

- 评测直接复用结构化业务结果和独立准入。
- 全仓搜索后才删除无调用的旧类；保留仍用于意图/标题等的 AiServices 能力。
- 只有确认没有其他接口需要 Flux 适配后才移除 langchain4j-reactor；Reactor 本身必须保留并使用明确依赖。
- 修正文档中的 BGE、旧参数、旧代理调用链和“取消必定停止计费”等错误说法。

验收：评测 API/导出和历史结果兼容；新旧 HTTP 适配共用引擎；全量自动化回归通过。

### P7：综合验收与交付

- 同时验证新库初始化和从旧结构增量迁移。
- 使用假模型/ES 做并发、超时、慢消费者和取消压测，核对线程、队列、连接与内存不持续增长。
- 完成下面的验收清单；明确尚未完成的真实环境检查，不用“代码完成”替代验收。
- 更新 README 的 API、运行语义、超时/并发配置、数据库迁移和回滚说明。
- 在本文末尾或单独 IMPLEMENTATION_REPORT 中记录阶段完成、测试命令/结果、偏离计划的理由、未验证项。不要改掉原计划而掩盖差异。

## 12. 必测场景与验收方式

### A. 纯检索逻辑与可解释性

1. A 在向量第 1、关键词第 2，RRF 分为 1/61+1/62；来源合并。
2. 同一路重复片段不重复计分；不同版本同名/同文片段不合并。
3. 无重排时稳定 RRF 顺序；重排正常时按新分数排序；同分结果稳定。
4. 记录两路原分，不把 BM25 当相似度与向量分相加。
5. 重排超时、429、缺失/重复/越界索引、NaN 响应按分类处理。
6. 配置阈值后全低分得到 NO_EVIDENCE，不回填低分前三条。
7. 向量失败但关键词成功（含成功为空），以及两路都失败，结果分别正确。
8. 父块去重后能从剩余候选补足；父块缺失/超预算时不会误丢所有兄弟子块。
9. 实际上下文与持久引用映射一致，预算包含系统提示和历史。
10. 多轮改写保留编号/状态；取消/全局过期不走改写回退或新检索。

### B. Reactor、时间和真实上游连接

1. StepVerifier 验证延迟订阅、数据顺序、错误/取消传播；依赖假时钟的预算与 Scheduler 一起可注入。
2. 虚拟时间验证准备、首字、空闲、总截止时间；心跳/进度不能延长首字或文本空闲期限。
3. 阶段排队时间计入预算；剩余预算归零不启动 HTTP。
4. 在连接前、等待响应头、首字前、输出中分别取消；本地假服务器观察 EOF/reset/停止读取。
5. 实际不响应中断的任务继续占据受限工作槽，不能被取消风暴突破容量。
6. 异步 starter 返回不提前完成流；同步 adapter 正常完成不遗漏最后片段。
7. 上游发出部分文本后无完成信号断开，结果 FAILED 且保留 partial。
8. 完成/错误回调重复、取消与句柄注册竞争、双路同时取消，无资源泄漏。
9. 熔断开启/半开及恢复；取消不计依赖失败；本地拒绝不触发远端熔断。
10. 不透明重试已输出答案；计数证明一次 run 只调用一次生成。

### C. 数据库与状态竞争

必须在专用 MySQL 8.x 测试环境验证事务、行锁、唯一索引和 JSON；可用 Testcontainers（本机具备 Docker 时）或明确配置的隔离测试库。H2/Mock 不作为 MySQL 并发正确性的最终证据。

1. 新库初始化、旧库增量迁移和历史 NULL run_id 读取。
2. 相同 clientRequestId 并发创建只产生一对消息、一个 run；不同 payload 返回 409。
3. 同会话并发创建只有一个活动轮次；不同会话不被全局互斥串行化。
4. READY 取消/过期不调用模型；未开始的流重试不重复写消息。
5. 完成与取消、超时与完成竞争：终态只能有一个，保存只应用一次。
6. 收尾 commit 之前不发送成功 end；事务失败不产生成功记录。
7. 检查点与 finalizer 竞争、迟到旧检查点，不覆盖最终文本。
8. 中途取消保存部分文本；刷新后状态/引用仍可查，后续历史不含未完成轮次。
9. JDBC 返回超时但数据库实际已提交，读回幂等确认，不重复生成或反转终态。
10. 生成取消后保存仍执行；保存预算独立；持久化持续故障明确报未保存并可收敛。
11. 活动会话删除拒绝；终态删除不留 run/消息孤儿。
12. 重启残留 run 收敛，保留检查点，不自动重新调用模型。

### D. MVC/SSE 与兼容

1. 使用嵌入式 Tomcat 的随机端口 + 真实 HTTP 客户端验证 text/event-stream、逐步到达、命名事件。
2. 未订阅不执行；重复 GET 活动流/终态流不再次调用生成。
3. HTTP 头前 400/409/429 与头后 end/error 行为正确。
4. 无订阅 READY 过期不泄漏条目；流断开触发停止并最终落库。
5. 取消 HTTP 接口在模型工作池满时仍能被受理。
6. 慢读/不读客户端触发有界策略；心跳停止，无无界缓冲或遗留计时器。
7. 旧 /chat/send 映射正确，只复用同一引擎，不重复订阅。
8. MVC async timeout 不提前截断正常业务总预算；终态提交后写出失败不反转数据库结果。

### E. 前端

1. 一个 UTF-8 汉字拆成多次网络读取仍正确；CRLF、多行 data、JSON 内换行不破坏 Markdown。
2. 首字前停止、回答中停止、创建返回前停止、重复点停止。
3. cancel 请求成功/失败、AbortError、无 end 的 EOF 都不错误显示完成。
4. 新会话 ID 立即可用；取消后刷新看到对应记录。
5. 切换会话后的迟到片段不写错 DOM；资源和事件监听器清理。
6. 引用保持顺序与版本，partial 和原因展示正确；普通模型文本以旧特殊前缀开头也不会被误识别为控制事件。

### F. 集成与容量

- 保留全部现有聊天与文档测试，验证文档相关功能无回归。
- 评测可在无浏览器/无临时消息表写入的情况下得到结构化结果。
- 使用本地假上游进行 8 个活动轮次 + 超额请求、重复取消、单依赖挂死、慢消费者测试。
- 记录峰值工作线程/队列/活动连接、结束后残留任务、首字与总耗时；只测“返回了错误”不足以证明资源已释放。
- 真实模型小样本/真实云库迁移仅在适合且获授权的验收环境执行，明确调用成本；不得伪造没有执行的质量结果。

## 13. 验收清单（实现后逐项填写）

- [ ] Spring MVC + Reactor 保留，未引入通用 Agent/DAG 框架。
- [ ] 当前主干能力与已有 5 项韧性测试保留。
- [ ] RRF/重排/父块/无证据策略有明确规则和测试。
- [ ] 核心算法不依赖框架 Content 判等或无必要的多 Query Map。
- [ ] 新接口及类型化 SSE 已接到真实前端，旧接口是薄适配。
- [ ] 首字前/生成中停止可终止上游，不能仅停止前端显示。
- [ ] 超时和用户取消不会被降级逻辑吞掉。
- [ ] 模型流正常完成与裸 EOF/方法返回被正确区分。
- [ ] 所有终态、部分文本和引用有一致的持久化策略。
- [ ] 先保存再成功通知；检查点不会覆盖终态。
- [ ] DB 不可用/提交未知时不假报成功，可查询/收敛。
- [ ] 完整历史过滤、旧数据兼容、活动会话互斥/删除行为通过。
- [ ] MVC 写出、缓冲、心跳、执行器、实际取消任务均受限且可观测。
- [ ] 评测复用结构化逻辑，不解析 SSE/回查临时消息。
- [ ] 迁移 SQL、新库 schema、真实 MySQL 测试和回滚说明完成。
- [ ] 本地网络/Servlet/前端测试已执行，真实环境未验证项明确列出。
- [ ] 文档与实现一致；最终报告列出文件、命令、结果、限制和计划偏差。

## 14. 本地执行与交付提示

### 14.1 基线命令（PowerShell）

系统默认 java 当前可能是 JDK 17；本项目要求 JDK 21。仅在本次子进程环境中设置，不改用户全局 PATH/JAVA_HOME。

~~~powershell
Set-Location D:/IdeaProject/know-engine
$env:JAVA_HOME = (Resolve-Path '.local/tools/jdk-21/jdk-21.0.13+9').Path
& 'D:/apache-maven-3.8.8/bin/mvn.cmd' -B -o '-Dmaven.repo.local=D:/maven-repository' '-Dtest=ChatResilienceTest' test
~~~

新增依赖后离线构建若缺包，可以正常联网解析依赖，并记录原因；不能把缺包当作业务测试失败，也不能绕过测试。完成后运行与改动相关的全部测试。若 IDE 生成了异常陈旧 class，使用标准 Maven 重新编译，记录原因；不要无理由删除源码或运行数据。

### 14.2 实现过程约束

- 实施前读取实际仓库约束和用户新指令；本计划不会覆盖后续明确指令。
- 允许自行决定常规类名、包组织和局部实现，并在报告解释与计划的实质差异。
- 不要求逐阶段向用户重复请求确认。先完成本地代码、迁移文件、假上游测试和可审阅结果。
- 不修改 .local 私有配置，不输出凭证，不把云端线上服务当可销毁测试依赖。
- 不替用户提交/推送、部署或执行现有云库迁移；源码和本地可验证测试是本次实现交付范围。
- 仓库已有未提交变更时保留它们，仅修改本任务相关内容。
- 测试或环境受阻必须说明实际阻塞和未完成项，不能把“已写测试”报告成“已通过测试”。

### 14.3 完成报告

至少交付：

1. 哪些功能保留、哪些替换、核心调用链。
2. 新 API/SSE 示例、取消与保存语义。
3. SQL 迁移步骤、历史兼容和回滚限制。
4. 实际运行的测试/命令和结果；未运行项及原因。
5. 配置初始值及有待评测的参数。
6. 相对本计划的偏差及证据。

## 15. 参考依据

以下是机制参考，不是要求升级到网页显示的最新版本。实现必须以 pom.xml 实际解析版本和本地依赖源码为准。

- [Spring MVC 异步、响应式返回值、SSE、断开和写出配置](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-async.html)：MVC 可返回响应式流；流式写出仍需配置执行器；断开探测需要后续写出/心跳。
- [Reactor 阻塞调用适配](https://projectreactor.io/docs/core/release/reference/faq.html)：将阻塞工作包装并调度到适当的有界执行环境；返回 Mono 不会自动使 I/O 非阻塞。
- [Reactor 测试](https://projectreactor.io/docs/core/release/reference/testing.html)：StepVerifier、虚拟时间和路径验证；实际网络取消仍需集成测试。
- [RRF 公式和排序含义](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/reciprocal-rank-fusion)：按排名融合不同检索信号，默认平滑常数 60。本项目实现自己的轻量融合函数，不要求购买/启用 ES 特定检索功能。

本次计划编写只增加本文，不实施业务改造；基线测试结果见第 2.3 节。
