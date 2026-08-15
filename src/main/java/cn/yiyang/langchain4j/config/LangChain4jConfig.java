package cn.yiyang.langchain4j.config;

import cn.yiyang.langchain4j.dao.ChatAssistant;
import cn.yiyang.langchain4j.dao.CodeReviewAssistant;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class LangChain4jConfig {

    /**
     * 创建 CodeReviewAssistant 的代理对象
     * AiServices.builder 会用动态代理生成接口的实现类
     * 对比 Spring AI 的 ChatClient.Builder.build()，这里是 AiServices.builder().build()
     */
    @Bean
    public CodeReviewAssistant codeReviewAssistant(OpenAiChatModel openAiChatModel){
        return AiServices.builder(CodeReviewAssistant.class)
                .chatModel(openAiChatModel)
                .build();
    }

    @Bean
    public OpenAiChatModel langchain4jChatModel(
            @Value("${langchain4j.open-ai.chat-model.api-key}") String apiKey,
            @Value("${langchain4j.open-ai.chat-model.base-url}") String baseUrl,
            @Value("${langchain4j.open-ai.chat-model.model-name}") String modelName,
            @Value("${langchain4j.open-ai.chat-model.temperature:0.7}") double temperature) {

        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    /**
     * 流式聊天模型：SSE 逐 token 输出用
     * 对比 Spring AI：Spring AI 的同一个 OpenAiChatModel 既能 .call() 又能 .stream()，
     * 而 LangChain4j 把同步模型（ChatModel）和流式模型（StreamingChatModel）拆成两个独立模型
     */
    @Bean
    public OpenAiStreamingChatModel langchain4jStreamingChatModel(
            @Value("${langchain4j.open-ai.chat-model.api-key}") String apiKey,
            @Value("${langchain4j.open-ai.chat-model.base-url}") String baseUrl,
            @Value("${langchain4j.open-ai.chat-model.model-name}") String modelName,
            @Value("${langchain4j.open-ai.chat-model.temperature:0.7}") double temperature) {

        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    /**
     * 带多轮记忆的对话助手代理对象
     * 关键点：
     *  1. chatModel + streamingChatModel 同时注入，AiServices 按方法返回类型自动选用
     *     —— 返回 String 用 chatModel，返回 TokenStream 用 streamingChatModel
     *  2. chatMemoryProvider 按 memoryId 为每个会话创建独立的 MessageWindowChatMemory
     *     对比 Spring AI：Spring AI 用 MessageChatMemoryAdvisor + conversationId，这里是 chatMemoryProvider + @MemoryId
     *  3. maxMessages(20) 控制窗口大小，超出后自动淘汰最早的消息
     */
    @Bean
    public ChatAssistant chatAssistant(OpenAiChatModel langchain4jChatModel,
                                       OpenAiStreamingChatModel langchain4jStreamingChatModel,
                                       ChatMemoryStore chatMemoryStore) {
        return AiServices.builder(ChatAssistant.class)
                .chatModel(langchain4jChatModel)
                .streamingChatModel(langchain4jStreamingChatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(20)
                        .chatMemoryStore(chatMemoryStore)
                        .build())
                .build();
    }

    /**
     * LangChain4j 的 Embedding 模型（用百炼 text-embedding-v3）
     * 对比 Spring AI：Spring AI 自动配置 EmbeddingModel Bean，这里需要手动创建
     */
    @Bean
    public OpenAiEmbeddingModel langchain4jEmbeddingModel(
            @Value("${langchain4j.open-ai.embedding-model.api-key}") String apiKey,
            @Value("${langchain4j.open-ai.embedding-model.base-url}") String baseUrl,
            @Value("${langchain4j.open-ai.embedding-model.model-name}") String modelName) {
        return OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    /**
     * LangChain4j 的 PgVector EmbeddingStore
     * 用单独的表 langchain4j_store（避免和 Spring AI 的 vector_store 表冲突）
     * 对比 Spring AI：Spring AI 用 VectorStore 接口 + 自动配置，这里手动建 EmbeddingStore
     *
     * @Lazy 延迟初始化：PgVectorEmbeddingStore 构造时会连接 PostgreSQL 建表，
     * 如果数据库未启动会导致整个应用启动失败。加 @Lazy 后，只在实际调用时才连接。
     */
    @Lazy
    @Bean
    public EmbeddingStore<TextSegment> langchain4jEmbeddingStore() {
        return PgVectorEmbeddingStore.builder()
                .host("localhost")
                .port(5432)
                .database("vectordb")
                .user("postgres")
                .password("postgres")
                .table("langchain4j_store")   // 用单独的表，不跟 Spring AI 冲突
                .dimension(1024)               // 和 text-embedding-v3 维度一致
                .dropTableFirst(false)          // 不删表，保留数据
                .build();
    }

}
