package cn.yiyang.springai.controller.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ChatController {

    private final ChatClient chatClient;

    // 构造注入，Spring AI 自动配置好 ChatClient.Builder
    public ChatController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    // 同步调用：GET /chat?question=你好
    @GetMapping("/chat")
    public String chat(@RequestParam String question) {
        return chatClient.prompt()
                .system("你是一名 Java 技术专家，回答简洁专业")
                .user(question)
                .call()        // 同步调用
                .content();     // 取回复文本
    }

    // 流式调用：GET /chat/stream?question=你好
    @GetMapping(value = "/chat/stream", produces = "text/event-stream; charset=UTF-8")
    public Flux<String> stream(@RequestParam String question) {
        return chatClient.prompt()
                .system("你是一名 Java 技术专家，回答简洁专业")
                .user(question)
                .stream()       // 流式调用
                .content();      // 直接返回 Flux<String>
    }
}
