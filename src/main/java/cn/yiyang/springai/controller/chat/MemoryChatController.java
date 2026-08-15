package cn.yiyang.springai.controller.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 多轮对话记忆演示 Controller
 *
 * 三种策略对比：
 * 1. 无记忆 — 每次请求独立，大模型不记得之前说了什么
 * 2. 手动会话 ID — 用 conversationId 区分不同会话，同一 ID 下的消息自动带入上下文
 * 3. 自动会话 ID — 服务端自动生成 conversationId，适合前端不方便管理 sessionId 的场景
 */
@RestController
public class MemoryChatController {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    public MemoryChatController(ChatClient.Builder builder, ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
        this.chatClient = builder
                .defaultSystem("你是一名 Java 技术面试官，正在和候选人对话")
                .build();
    }

    @GetMapping("/chat/no-memory")
    public String noMemory(@RequestParam String message) {
        return chatClient.prompt().user(message).call().content();
    }

    @GetMapping("/chat/with-memory")
    public String withMemory(@RequestParam String message,
                             @RequestParam(defaultValue = "default-session") String conversationId) {
        return chatClient
                .prompt().user(message)
                .advisors(MessageChatMemoryAdvisor
                        .builder(chatMemory)
                        .conversationId(conversationId)
                        .build())
                .call().content();
    }

    @GetMapping("/chat/auto-session")
    public String autoSession(@RequestParam String message,
                             @RequestParam(required = false) String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }

        String response = chatClient
                .prompt().user(message)
                .advisors(MessageChatMemoryAdvisor
                        .builder(chatMemory)
                        .conversationId(conversationId)
                        .build())
                .call().content();

        return "【会话ID：" + conversationId + "】\n" + response;
    }

    @GetMapping("/chat/clear")
    public String clear(@RequestParam(defaultValue = "default-session") String conversationId) {
        chatMemory.clear(conversationId);
        return "已清除会话 " + conversationId + " 的记忆";
    }
}
