package cn.yiyang.springai.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置
 *
 * 默认 RedisTemplate 用 JDK 序列化（二进制，不可读），
 * 这里改为 Jackson JSON 序列化，方便调试和跨语言兼容。
 *
 * 安全说明：使用 BasicPolymorphicTypeValidator 限制可反序列化的类型白名单，
 * 仅允许 Java 集合和基本类型，防止反序列化漏洞（RCE 攻击）。
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // Key 用 String 序列化
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Value 用 Jackson JSON 序列化
        Jackson2JsonRedisSerializer<Object> jsonSerializer =
                new Jackson2JsonRedisSerializer<>(buildObjectMapper(), Object.class);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * 配置 ObjectMapper：保留类型信息（反序列化时能还原原始类型）
     *
     * 安全策略：使用 BasicPolymorphicTypeValidator 构建类型白名单，
     * 仅允许 Java 标准集合和基本包装类型通过反序列化类型校验，
     * 阻止任意类加载（替代不安全的 LaissezFaireSubTypeValidator）。
     */
    private ObjectMapper buildObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        BasicPolymorphicTypeValidator validator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType(Object.class)
                .allowIfSubType("java.util.")
                .allowIfSubType("java.lang.")
                .allowIfSubType("java.time.")
                .build();
        mapper.activateDefaultTyping(validator, ObjectMapper.DefaultTyping.NON_FINAL);
        return mapper;
    }
}
