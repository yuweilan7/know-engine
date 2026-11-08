package know.engine.chat.service;

import know.engine.ai.constant.PromptResources;
import know.engine.ai.service.CommonChatService;
import know.engine.ai.service.IntentRecognitionService;
import know.engine.ai.service.RagChatService;
import know.engine.ai.prompt.PromptService;
import know.engine.ai.service.TitleSummaryService;
import know.engine.ai.entity.IntentRecognitionResult;
import know.engine.chat.constant.ChatMessageType;
import know.engine.chat.constant.RetrievalSource;
import know.engine.chat.entity.ChatParam;
import know.engine.chat.memory.DatabaseChatMemoryStore;
import know.engine.document.service.SegmentService;
import know.engine.document.service.DocumentService;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import know.engine.rag.*;
import know.engine.rag.reranker.DashScopeScoringModel;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.injector.ContentInjector;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.query.router.DefaultQueryRouter;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.function.Consumer;

import static know.engine.rag.constant.MetadataKeyConstant.*;

@Service
@Slf4j
public class ChatService {

    @Autowired
    @Qualifier("openAiChatModel")
    private ChatModel chatModel;

    /**
     * RAG 对话生成专用 StreamingChatModel，见 {@link know.engine.ai.config.AiModelConfig#ragChatModel}
     */
    @Autowired
    @Qualifier("ragChatModel")
    private StreamingChatModel ragChatModel;

    @Autowired
    private CommonChatService commonChatService;

    @Autowired
    private IntentRecognitionService intentRecognitionService;

    @Autowired
    private TitleSummaryService titleSummaryService;

    @Autowired
    private ChatConversationService chatConversationService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private SegmentService segmentService;

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private PromptService promptService;

    @Autowired
    private OpenAiEmbeddingModel openAiEmbeddingModel;

    @Autowired
    private DashScopeScoringModel rerankModel;

    @Autowired
    private DatabaseChatMemoryStore databaseChatMemoryStore;

    @Autowired
    private ChatMemoryProvider conversationChatMemoryProvider;

    @Value("${langchain4j.open-ai.streaming-chat-model.model-name}")
    private String commonChatModelName;

    /**
     * 统一流式对话入口
     * 会话创建（可选）、异步标题生成、保存用户/助手消息、意图识别、不相关问题兜底通用对话、相关问题 RAG 对话
     *
     * @param userId         用户ID
     * @param content        用户问题
     * @param conversationId 会话ID（可选，为空则自动创建新会话）
     * @return 包含进度消息、[DONE] 事件及 LLM token 的 SSE 流
     */
    public Flux<String> chat(String userId, String content, String conversationId) {
        // 1. 处理会话：没有 conversationId 则创建新会话
        String finalConversationId = resolveConversationIdAndTitleSummaryAsync(userId, content, conversationId);

        // 2. 保存用户消息，并预插入空的助手消息，流式完成后再回写内容
        String messageId = chatMessageService.saveMessage(finalConversationId, ChatMessageType.USER, content);
        String assistantMessageId = chatMessageService.saveMessage(finalConversationId, ChatMessageType.ASSISTANT, null);

        // 3. 单独定义首条进度消息
        Flux<String> progressFlux = Flux.just("[PROGRESS]:正在识别您的意图...");

        // 4. 将阻塞式意图识别包装成 Mono，并放到 boundedElastic 线程池执行
        Mono<IntentRecognitionResult> intentMono = Mono.fromCallable(() -> intentRecognitionService.chat(finalConversationId, content));
        intentMono = intentMono.subscribeOn(Schedulers.boundedElastic());

        // 5. 在意图识别完成后，会回调此处，根据意图识别的结果 选择普通聊天或 RAG
        Flux<String> answerFlux = intentMono.flatMapMany(intentResult ->
                routeByIntent(userId, content, finalConversationId, messageId, assistantMessageId, intentResult));

        // 6. 严格按“进度 → 回答”的顺序拼接
        Flux<String> responseFlux = progressFlux.concatWith(answerFlux);

        // 7. 只记录异常，不吞掉或改写异常信号
        responseFlux = responseFlux.doOnError(error -> log.error("流式对话异常: conversationId={}", finalConversationId, error));

        // 8. 仅在前面的响应流正常完成后发送 DONE；发生异常时不会执行这里
        Mono<String> doneMono = Mono.just("[DONE]:" + finalConversationId);
        return responseFlux.concatWith(doneMono);
    }

    // 返回会话ID && 标题异步总结
    private String resolveConversationIdAndTitleSummaryAsync(String userId, String content, String conversationId) {
        // 前端入参的会话ID存在,无需额外处理,直接返回
        if (conversationId != null && !conversationId.isBlank()) {
            return conversationId;
        }

        // 会话ID不存在，说明是用户新开的新对话生成临时标题,内容的前二十字符
        String tempTitle = content.substring(0, Math.min(content.length(), 20));

        // 会话表 chat_conversation 数据创建
        String createdConversationId = chatConversationService.createConversation(userId, tempTitle);
        log.info("创建新会话: conversationId={}, tempTitle={}", createdConversationId, tempTitle);

        // 异步总结content内容生成新标题
        startTitleSummaryAsync(createdConversationId, content);
        return createdConversationId;
    }

    // 异步总结content内容生成新标题
    private void startTitleSummaryAsync(String conversationId, String content) {
        Thread.Builder.OfVirtual threadBuilder = Thread.ofVirtual();
        threadBuilder = threadBuilder.name("title-summary-" + conversationId);
        threadBuilder.start(() -> {
            try {
                // AI生成新标题
                String aiTitle = titleSummaryService.generateTitle(content);
                // 更新 会话表 chat_conversation 的标题字段
                chatConversationService.updateTitle(conversationId, aiTitle);
                log.info("异步标题更新完成: conversationId={}, title={}", conversationId, aiTitle);
            } catch (Exception error) {
                log.warn("异步标题生成失败, 保留临时标题: conversationId={}", conversationId, error);
            }
        });
    }

    /**
     * 根据意图识别的结果 路由到 普通聊天或 RAG （即判断是否需要RAG检索）
     */
    private Flux<String> routeByIntent(String userId, String content, String conversationId, String messageId, String assistantMessageId, IntentRecognitionResult intentResult) {
        // 意图识别和 RAG 共用会话记忆，但意图识别结果只用于决策是否需要RAG检索，意图识别的结果不需要被RAG真正检索时所见，因此清理 Redis中的会话记忆 是为了让 RAG检索重新从 DB 加载干净上下文
        databaseChatMemoryStore.evictCache(conversationId); // 清除指定会话的缓存，只是删除Redis的会话记忆，DB中的数据仍在

        // 意图识别结果related为 false-闲聊或不需要检索
        if (!intentResult.related()) {
            return createCommonChatFlux(conversationId, content, assistantMessageId);
        }

        // 意图识别结果related为 true-知识问答
        ChatParam chatParam = new ChatParam(userId, conversationId, messageId, content, assistantMessageId, intentResult);
        return ragChat(chatParam);
    }

    /**
     * 不需要RAG检索的普通问答
     * doOnNext 和 doOnComplete 都是 Reactor 的旁路钩子：不改变流里发出的数据，只在特定时机做额外事情
     * 时序大致是：
     * [ 1 ]先发 [PROGRESS]:正在为您生成回答...
     * [ 2 ]模型吐 "你" → doOnNext 追加，同时推给前端
     * [ 3 ]再吐 "好" → 再追加、再推送
     * [ 4 ]模型结束 → doOnComplete 把 "你好..." 整段落库
     */
    private Flux<String> createCommonChatFlux(String conversationId, String content, String assistantMessageId) {
        StringBuilder contentBuilder = new StringBuilder();
        Flux<String> progressFlux = Flux.just("[PROGRESS]:正在为您生成回答...");

        // commonChatService.streamChat 返回的是一个 Flux<String>：模型每生成一个 token，就往下流一个字符串，全部发完后发出 complete 信号
        Flux<String> modelFlux = commonChatService.streamChat(conversationId, content);

        // doOnNext：每来一个元素就执行一次这里 contentBuilder::append 等于每收到一个 token 就拼进 StringBuilder
        modelFlux = modelFlux.doOnNext(contentBuilder::append);

        // doOnComplete：流正常结束时执行一次此时所有 token 已经拼完，把完整文本写回预插入的助手消息
        modelFlux = modelFlux.doOnComplete(() -> chatMessageService.updateContent(assistantMessageId, contentBuilder.toString(), commonChatModelName));

        return Flux.concat(progressFlux, modelFlux);
    }

    /**
     * 执行一次完整的 RAG 流式问答
     * 业务数据流：用户问题 → 问题改写 → 向量/全文双路检索 → RRF 融合和 百炼重排 → 将候选片段注入提示词 → LLM 流式生成 → 保存完整回答
     * RAG 组件本身不是 Reactor 流（内部是同步回调），因此用 Flux.create 把两类消息桥接到同一个 SSE 流：
     * （1）RAG 各阶段通过 processCallback 发出的进度和引用消息
     * （2）大模型生成的回答 token
     * 时序大致是：
     * [ 1 ]外层订阅 ragFlux 后，Flux.create 把 sink 交给 executeRagPipeline
     * [ 2 ]管道执行期间，进度字符串经 sink.next 推给前端
     * [ 3 ]模型开始吐 token 后，token 同样经 sink.next 推给前端
     * [ 4 ]模型结束 → 回写完整回答 → sink.complete 结束外层 Flux
     *
     * @param chatParam 本轮问答上下文，包含会话 ID、用户消息 ID、助手消息 ID、原始问题、意图识别结果等
     * @return 按产生顺序输出进度、引用和回答 token 的 Flux
     */
    public Flux<String> ragChat(ChatParam chatParam) {
        // Flux.create 提供一个可主动推送数据的 sinkexecuteRagPipeline 里随时 sink.next(...)，这里就会变成 Flux 的一个元素
        Flux<String> ragFlux = Flux.create(sink -> executeRagPipeline(chatParam, sink));

        // subscribeOn：指定“谁来执行订阅动作”RAG 含模型、ES、数据库等阻塞调用，放到 boundedElastic，避免卡住 Reactor 事件循环
        ragFlux = ragFlux.subscribeOn(Schedulers.boundedElastic());

        // publishOn：指定“后续信号在哪消费”SSE 分发换到 parallel，避免 RAG 工作线程同时承担推送
        ragFlux = ragFlux.publishOn(Schedulers.parallel());
        return ragFlux;
    }

    /**
     * 组装并启动一轮 RAG 管道，结果通过 sink 推到 ragChat 创建的 Flux
     * 组装顺序对应 LangChain4j RetrievalAugmentor 真正执行时的顺序：
     * [ 1 ]QueryTransformer：结合历史改写用户问题，并通过 processCallback 发进度
     * [ 2 ]双路 ContentRetriever：向量 KNN + 全文 match；ProgressAware 包装在首次 retrieve 前发“正在检索”进度
     * [ 3 ]ContentAggregator：RRF 融合 + 百炼重排；ProgressAware 包装发“排序筛选/生成回答”进度，并落库引用
     * [ 4 ]ContentInjector：把最终片段注入发给大模型的用户消息
     * [ 5 ]AiServices 挂上 streaming 模型、会话记忆、系统提示词和上面的 RetrievalAugmentor
     * [ 6 ]subscribeToModel 订阅模型 token，转发到 sink；客户端取消 SSE 时 dispose 订阅
     * processCallback = sink::next，所以管道内部任何进度字符串都会进入 SSE
     */
    private void executeRagPipeline(ChatParam chatParam, FluxSink<String> sink) {
        // 方法引用：progressCallback.accept(msg) 等价于 sink.next(msg)
        Consumer<String> processCallback = sink::next;

        // 检索前改写用户问题；chatParam.messageId() 用于把改写结果异步回写到用户消息
        KnowEngineQueryTransformer queryTransformer = new KnowEngineQueryTransformer(
                chatModel,
                promptService.getPromptTemplate(PromptResources.QUERY_REWRITE),
                chatParam.messageId(),
                processCallback
        );

        // 一轮问答读一次生效版本。全文和向量召回共享这一份范围，数据库失败时直接报错。
        List<Long> scope = List.copyOf(documentService.activeVersionIds());
        var embeddingRetriever = new ProgressAwareContentRetriever(
                new KnowEngineElasticsearchContentRetriever(elasticsearchClient, openAiEmbeddingModel, scope, true),
                processCallback, RetrievalSource.VECTOR);
        var fullTextRetriever = new ProgressAwareContentRetriever(
                new KnowEngineElasticsearchContentRetriever(elasticsearchClient, openAiEmbeddingModel, scope, false),
                processCallback, RetrievalSource.KEYWORD);

        // assistantMessageId 用于把最终引用片段写回助手消息
        ContentAggregator contentAggregator = createContentAggregator(processCallback, chatParam.assistantMessageId());

        // 使用通用问答系统提示词；注入器负责把检索片段拼进即将发给模型的用户消息
        String prompt = promptService.getPrompt(PromptResources.GENERIC_QA);
        ContentInjector contentInjector = new DefaultContentInjector(promptService.getPromptTemplate(PromptResources.RAG_CONTENT_INJECTOR));

        // DefaultQueryRouter 会把同一个 Query 同时交给两个检索器，得到两路候选后再交给 aggregator
        DefaultQueryRouter queryRouter = new DefaultQueryRouter(embeddingRetriever, fullTextRetriever);

        // 把改写、双路检索、聚合、注入串成 LangChain4j 的 RetrievalAugmentor
        // 调用 aiService.streamChat 时，框架按 queryTransformer → queryRouter → contentAggregator → contentInjector 顺序执行
        RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                // 将改写后的同一个 Query 路由到向量和全文两个检索器
                .queryRouter(queryRouter)
                // 在检索前先结合聊天历史改写用户问题
                .queryTransformer(queryTransformer)
                // 对双路召回结果执行融合、去重和重排
                .contentAggregator(contentAggregator)
                // 将最终片段注入发送给大模型的用户消息
                .contentInjector(contentInjector)
                // 创建完整的 RAG 增强管道
                .build();

        // AiServices 会生成动态代理：调用 streamChat 时先跑 retrievalAugmentor，再把增强后的消息交给 ragChatModel
        RagChatService aiService = AiServices.builder(RagChatService.class)
                // 使用支持逐 token 输出的 RAG 专用模型
                .streamingChatModel(ragChatModel)
                .chatMemoryProvider(conversationChatMemoryProvider)
                // 设置通用问答系统提示词
                .systemMessage(prompt)
                // 挂载问题改写、检索、聚合和内容注入管道
                .retrievalAugmentor(retrievalAugmentor)
                // 创建 KnowEngineChatAiService 动态代理
                .build();

        // 这里才真正订阅模型流：token / 错误 / 完成都会写进 sink
        Disposable disposable = subscribeToModel(aiService, chatParam, sink);

        // 客户端断开 SSE 时取消模型订阅，避免继续生成和占用资源
        sink.onCancel(disposable::dispose);
    }

    /**
     * 创建召回结果聚合器
     * 内层 KnowEngineReRankingContentAggregator：RRF 融合双路结果，再用 百炼重排、截断
     * 外层 ProgressAwareContentAggregator：聚合前后发进度，并把最终引用写回助手消息
     */
    private ContentAggregator createContentAggregator(Consumer<String> processCallback, String assistantMessageId) {
        ContentAggregator reRankingAggregator = KnowEngineReRankingContentAggregator.builder()
                // 百炼分数与本地 BGE 的分值范围不同，先按相对顺序选 Top 5。
                .scoringModel(rerankModel)
                // 多保留几个候选，扩展父块时去重后仍可补足 5 条。
                .maxResults(10)
                // 当前查询改写器只生成一个 Query，因此选择 Map 中的第一个 Query
                .querySelector(queryToContents -> queryToContents.keySet().iterator().next())
                // 创建执行 RRF 融合和 百炼重排的聚合器
                .build();
        // 装饰器包在重排器外面：先发“正在排序筛选”，聚合后再发“正在生成回答”，并持久化引用
        return new ProgressAwareContentAggregator(
                candidates -> expandParents(reRankingAggregator.aggregate(candidates)),
                processCallback,
                assistantMessageId,
                chatMessageService
        );
    }

    /** 先对子块融合、重排，之后再扩展上下文。引用保留命中的子块 ID。 */
    private List<Content> expandParents(List<Content> ranked) {
        List<Content> result = new ArrayList<>();
        var seenParents = new HashSet<String>();
        for (Content hit : ranked) {
            var metadata = hit.textSegment().metadata();
            String parentId = metadata.getString(PARENT_CHUNK_ID);
            if (parentId != null) {
                if (!seenParents.add(parentId)) continue;
                String parent = segmentService.getParentText(metadata.getLong(DOC_ID), metadata.getLong(VERSION_ID), parentId);
                if (parent != null && parent.length() <= 12000) {
                    hit = Content.from(TextSegment.from(parent, metadata), hit.metadata());
                }
            }
            result.add(hit);
            if (result.size() == 5) break;
        }
        return result;
    }

    /**
     * 订阅 RAG 专用模型的流式输出，把 token 接到 ragChat 的 sink 上
     * 与 {@link #createCommonChatFlux} 一样：doOnNext 攒全文，doOnComplete 回写助手消息
     * 差别是这里不能直接 return Flux，必须 subscribe 到 sink，因为外层已经是 Flux.create
     * 时序大致是：
     * [ 1 ]aiService.streamChat 触发 RetrievalAugmentor（改写/检索/重排/注入），期间进度已通过 processCallback 推到 sink
     * [ 2 ]模型吐 token → doOnNext 追加；subscribe 的 onNext 把 token 推给 sink
     * [ 3 ]模型结束 → doOnComplete 落库完整回答 → subscribe 的 onComplete 调用 sink.complete()
     * [ 4 ]中途出错 → subscribe 的 onError 调用 sink.error()
     */
    private Disposable subscribeToModel(RagChatService aiService, ChatParam chatParam, FluxSink<String> sink) {
        StringBuilder contentBuilder = new StringBuilder();

        // 这里才会真正启动 RAG 管道 + 模型生成streamChat 返回 Flux<String>：每个 token 一个元素，发完后 complete
        Flux<String> modelFlux = aiService.streamChat(chatParam.conversationId(), chatParam.content());

        // doOnNext：每个 token 拼进 StringBuilder，供结束后落库token 本身仍会继续传给后面的 subscribe
        modelFlux = modelFlux.doOnNext(contentBuilder::append);

        // doOnComplete：流正常结束时，把拼好的完整回答写回预插入的助手消息出错或取消不会走到这里
        modelFlux = modelFlux.doOnComplete(
                () -> chatMessageService.updateContent(
                        chatParam.assistantMessageId(),
                        contentBuilder.toString(),
                        ragChatModel.defaultRequestParameters().modelName()
                ));

        // 三参数 subscribe：onNext / onError / onComplete 分别对应 sink 的 next、error、complete
        // 返回的 Disposable 交给 executeRagPipeline，客户端取消 SSE 时用来中断模型订阅
        return modelFlux.subscribe(sink::next, sink::error, sink::complete);
    }

}
