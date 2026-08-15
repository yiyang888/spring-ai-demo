package cn.yiyang.langchain4j.controller;

import cn.yiyang.langchain4j.dao.CodeReviewAssistant;
import cn.yiyang.langchain4j.model.CodeReviewResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LangChain4jController {
    private final CodeReviewAssistant codeReviewAssistant;

    // 直接注入代理对象，用法和普通 Spring Bean 一样
    public LangChain4jController(CodeReviewAssistant codeReviewAssistant){
        this.codeReviewAssistant = codeReviewAssistant;
    }

    // 测试1：简单问答
    @GetMapping("/lc4j/chat")
    public String chat(@RequestParam(defaultValue = "什么是依赖注入？") String question){
        return codeReviewAssistant.chat(question);
    }


    // 测试2：代码审查，返回文本
    @GetMapping("/lc4j/review")
    public String review(@RequestParam(defaultValue = "public String login(String username, String password) {\n" +
            "                            String sql = \"SELECT * FROM users WHERE username='\" + username + \"'\";\n" +
            "                            return sql;\n" +
            "                        }") String code){
        return codeReviewAssistant.reviewCode(code);
    }

    // 测试3：代码审查，返回结构化 Bean
    @GetMapping("/lc4j/review/structured")
    public CodeReviewResult reviewStructured(@RequestParam(defaultValue = "public String login(String username, String password) {\n" +
            "                            String sql = \"SELECT * FROM users WHERE username='\" + username + \"'\";\n" +
            "                            return sql;\n" +
            "                        }") String code){
        return codeReviewAssistant.reviewCodeStructured(code);
    }



}
