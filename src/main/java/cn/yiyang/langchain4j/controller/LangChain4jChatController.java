package cn.yiyang.langchain4j.controller;

import cn.yiyang.langchain4j.dao.ChatAssistant;
import cn.yiyang.langchain4j.service.InMemoryChatMemoryStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * LangChain4j 完整对话服务：多轮记忆 + SSE 流式输出
 *
 * 对比 Spring AI 的 ChatController / MemoryChatController：
 *  - Spring AI：ChatClient.prompt().user(x).advisors(memoryAdvisor).call()  —— 链式 DSL
 *  - LangChain4j：chatAssistant.chat(memoryId, x)                           —— 声明式接口调用
 *
 * 测试：
 *  1. 同步多轮：GET /lc4j/chat/ask?message=我是张三&memoryId=u1
 *     再问：GET /lc4j/chat/ask?message=我叫什么？&memoryId=u1   —— 能记住“张三”
 *  2. 流式多轮：浏览器直接访问 /lc4j/chat/sse?message=用三句话介绍Spring&memoryId=u1
 *  3. 清除记忆：GET /lc4j/chat/clear?memoryId=u1
 */
@RestController
public class LangChain4jChatController {

    private final ChatAssistant chatAssistant;
    private final InMemoryChatMemoryStore chatMemoryStore;

    public LangChain4jChatController(ChatAssistant chatAssistant,
                                     InMemoryChatMemoryStore chatMemoryStore) {
        this.chatAssistant = chatAssistant;
        this.chatMemoryStore = chatMemoryStore;
    }

    // ================================================================
    // 同步多轮对话：阻塞返回完整回复
    // 同一个 memoryId 下的历史消息会被自动带入上下文
    // ================================================================
    @GetMapping("/lc4j/chat/ask")
    public String ask(@RequestParam String message,
                      @RequestParam(defaultValue = "default") String memoryId) {
        return chatAssistant.chat(memoryId, message);
    }

    // ================================================================
    // 自动生成 memoryId 的同步对话：第一次不传，服务端生成并返回
    // 对应 Spring AI 的 /chat/auto-session
    // ================================================================
    @GetMapping("/lc4j/chat/auto")
    public String auto(@RequestParam String message,
                       @RequestParam(required = false) String memoryId) {
        if (memoryId == null || memoryId.isBlank()) {
            memoryId = UUID.randomUUID().toString();
        }
        String reply = chatAssistant.chat(memoryId, message);
        return "【会话ID：" + memoryId + "】\n" + reply;
    }

    // ================================================================
    // SSE 流式多轮对话：逐 token 推送
    // produces 必须带 charset=UTF-8，否则中文会乱码（与 Spring AI 一致）
    //
    // 实现思路：用 Flux.create 把 TokenStream 的回调桥接成响应式流
    //   - onPartialResponse：每收到一个 token 就 sink.next() 推给前端
    //   - onCompleteResponse：流结束，sink.complete()
    //   - onError：异常，sink.error()
    // 对比 Spring AI：Spring AI 直接返回 chatClient.stream().content() 就是 Flux<String>，
    //                 LangChain4j 需要手动把 TokenStream 桥接成 Flux
    // ================================================================
    @GetMapping(value = "/lc4j/chat/sse", produces = "text/event-stream; charset=UTF-8")
    public Flux<String> sse(@RequestParam String message,
                            @RequestParam(defaultValue = "default") String memoryId) {
        return Flux.create(sink -> chatAssistant.streamChat(memoryId, message)
                .onPartialResponse(token -> sink.next(token))
                .onCompleteResponse(response -> sink.complete())
                .onError(sink::error)
                .start());
    }

    // ================================================================
    // 清除某个会话的记忆
    // 对比 Spring AI：Spring AI 通过 chatMemory.clear(conversationId) 清除
    // ================================================================
    @GetMapping("/lc4j/chat/clear")
    public String clear(@RequestParam String memoryId) {
        chatMemoryStore.deleteMessages(memoryId);
        return "已清除会话 " + memoryId + " 的记忆";
    }
}
