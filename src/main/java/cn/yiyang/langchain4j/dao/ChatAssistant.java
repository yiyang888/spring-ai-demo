package cn.yiyang.langchain4j.dao;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * LangChain4j 对话助手接口（声明式 AI Service）
 *
 * 对比 Spring AI：
 *  - Spring AI 用 ChatClient 的链式 DSL（.prompt().user().call()）
 *  - LangChain4j 只定义接口，用动态代理生成实现类，更接近 Spring Data JPA 的风格
 *
 * 这里一个接口同时承载三种能力：
 *  1. @MemoryId      —— 多轮会话记忆，用 memoryId 区分不同会话
 *  2. String 返回    —— 同步对话
 *  3. TokenStream    —— 流式对话（SSE）
 *     同一个接口方法返回 TokenStream，AiServices 会自动改用 StreamingChatModel
 */
public interface ChatAssistant {

    /**
     * 同步多轮对话：阻塞直到拿到完整回复
     * @MemoryId 标记会话标识，@UserMessage 标记用户输入
     */
    @SystemMessage("你是一名耐心的 Java 技术导师，用简洁专业的中文解答问题，必要时给出代码示例")
    String chat(@MemoryId String memoryId, @UserMessage String message);

    /**
     * 流式多轮对话：逐 token 返回，配合 SSE 推送到前端
     * 返回类型为 TokenStream 时，AiServices 自动选择 StreamingChatModel
     */
    @SystemMessage("你是一名耐心的 Java 技术导师，用简洁专业的中文解答问题，必要时给出代码示例")
    TokenStream streamChat(@MemoryId String memoryId, @UserMessage String message);
}
