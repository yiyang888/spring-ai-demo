package cn.yiyang.springai.controller.chat;

import cn.yiyang.springai.model.CodeReviewResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 结构化输出演示：
 * 让大模型返回 JSON，自动映射到 Java Bean
 *
 * 三种用法：
 * 1. entity(Class)           - 最简单，返回单个对象
 * 2. entity(TypeReference)   - 返回泛型集合（如 List<Bean>）
 * 3. BeanOutputConverter     - 手动控制，拿到 format 指令自定义 prompt
 */
@RestController
public class StructuredController {

    private static final Logger log = LoggerFactory.getLogger(StructuredController.class);

    private final ChatClient chatClient;

    public StructuredController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    // ================================================================
    // 用法一：entity(Class) —— 最简单，一行搞定
    // Spring AI 自动用 BeanOutputConverter 把 JSON 转成 Java 对象
    // ================================================================
    @GetMapping("/review/simple")
    public CodeReviewResult reviewSimple(@RequestParam(defaultValue = "审查下面这段代码的安全性和规范性") String question) {

        String code = """
                public class UserController {
                    public String login(String username, String password) {
                        String sql = "SELECT * FROM users WHERE username='" + username
                                    + "' AND password='" + password + "'";
                        User user = jdbcTemplate.queryForObject(sql, User.class);
                        if (user != null) {
                            return "token_" + username + "_" + System.currentTimeMillis();
                        }
                        return "登录失败";
                    }
                }
                """;

        // entity() 会让 Spring AI 自动：
        // 1. 从 CodeReviewResult 类生成 JSON Schema
        // 2. 把 schema 格式指令追加到 prompt 末尾
        // 3. LLM 返回 JSON 后，用 ObjectMapper 反序列化成 CodeReviewResult 对象
        return chatClient.prompt()
                .system("你是一名高级开发工程师，负责审查代码并帮助开发者发现潜在问题。请从安全漏洞、代码规范、性能问题、设计缺陷四个维度审查。")
                .user(question + "\n\n待审查代码：\n" + code)
                .call()
                .entity(CodeReviewResult.class);  // ← 关键：直接返回 Java 对象
    }

    // ================================================================
    // 用法二：entity(TypeReference) —— 返回泛型集合
    // 比如让大模型审查多段代码，每段返回一个结果，组成 List
    // ================================================================
    @GetMapping("/review/list")
    public List<CodeReviewResult> reviewList() {

        return chatClient.prompt()
                .system("你是一名代码审查专家。请分别审查以下两段代码，每段代码返回一个审查结果。")
                .user("""
                        请审查以下两段代码：

                        代码1：
                        public String login(String username, String password) {
                            String sql = "SELECT * FROM users WHERE username='" + username + "'";
                            return sql;
                        }

                        代码2：
                        public List<User> getAllUsers() {
                            return userRepository.findAll();
                        }
                        """)
                .call()
                .entity(new ParameterizedTypeReference<List<CodeReviewResult>>() {});
    }

    // ================================================================
    // 用法三：BeanOutputConverter 手动控制 —— 更灵活
    // 可以拿到 format 指令，自己拼接到 prompt 任意位置
    // 适合需要精细控制 prompt 结构的场景
    // ================================================================
    @GetMapping("/review/manual")
    public CodeReviewResult reviewManual(@RequestParam(defaultValue = "审查这段代码") String question) {

        // 1. 手动创建 BeanOutputConverter
        BeanOutputConverter<CodeReviewResult> converter = new BeanOutputConverter<>(CodeReviewResult.class);

        // 2. 拿到格式指令（这是 Spring AI 根据 CodeReviewResult 类自动生成的 JSON Schema）
        String format = converter.getFormat();
        log.debug("[StructuredController] 自动生成的格式指令:\n{}", format);

        String code = """
                public class UserController {
                    public String login(String username, String password) {
                        String sql = "SELECT * FROM users WHERE username='" + username
                                    + "' AND password='" + password + "'";
                        User user = jdbcTemplate.queryForObject(sql, User.class);
                        if (user != null) {
                            return "token_" + username + "_" + System.currentTimeMillis();
                        }
                        return "登录失败";
                    }
                }
                """;

        // 3. 手动把 format 指令拼到 prompt 里（可以控制位置）
        String prompt = question + """

                待审查代码：
                """ + code + """

                请严格按照以下格式返回结果：
                """ + format;

        // 4. 调用模型，拿到字符串结果
        String content = chatClient.prompt()
                .system("你是一名高级开发工程师，负责审查代码。")
                .user(prompt)
                .call()
                .content();

        // 5. 手动用 converter 把 JSON 字符串转成 Java 对象
        return converter.convert(content);
    }
}
