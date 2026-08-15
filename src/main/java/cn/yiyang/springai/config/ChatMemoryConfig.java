package cn.yiyang.springai.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository){
        return MessageWindowChatMemory.builder().chatMemoryRepository(repository)
                .maxMessages(20).build();
    }

    /**
     * 全局 RestClient.Builder 超时配置
     * Spring AI 的 OpenAI 客户端会使用此 Builder，从而获得超时控制
     * - 连接超时 10s
     * - 读取超时 60s（LLM 生成可能较慢）
     */
    @Bean
    public RestClient.Builder restClientBuilder() {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(10))
                .withReadTimeout(Duration.ofSeconds(60));
        ClientHttpRequestFactory factory = ClientHttpRequestFactories.get(settings);
        return RestClient.builder().requestFactory(factory);
    }
}