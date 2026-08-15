package cn.yiyang.langchain4j.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自定义内存版 ChatMemoryStore
 *
 * 对比 Spring AI：
 *  - Spring AI 用 ChatMemoryRepository（InMemoryChatMemoryRepository）+ MessageWindowChatMemory
 *  - LangChain4j 用 ChatMemoryStore + MessageWindowChatMemory，职责几乎一一对应
 *
 * 作用：按 memoryId（会话ID）持久化消息列表。
 * 这里用 ConcurrentHashMap 做进程内存储；生产环境可换成 Redis 实现，方法签名不变。
 *
 * 实现这三个方法即可被 MessageWindowChatMemory 使用：
 *  - getMessages：每次调用 LLM 前读取历史
 *  - updateMessages：每次加入新消息（用户消息 / AI 消息）后写入
 *  - deleteMessages：清除某个会话的记忆
 */
@Component
public class InMemoryChatMemoryStore implements ChatMemoryStore {

    private final Map<String, List<ChatMessage>> store = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return store.getOrDefault(memoryId.toString(), List.of());
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        store.put(memoryId.toString(), messages);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        store.remove(memoryId.toString());
    }
}
