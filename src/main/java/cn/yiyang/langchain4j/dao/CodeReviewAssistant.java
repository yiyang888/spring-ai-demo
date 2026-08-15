package cn.yiyang.langchain4j.dao;


import cn.yiyang.langchain4j.model.CodeReviewResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j 的 AiServices 接口
 *
 * 你只定义接口，LangChain4j 用动态代理自动生成实现类
 * 对比 Spring AI 的 ChatClient（链式 DSL），这里是声明式接口
 */
public interface CodeReviewAssistant {

    // ===== 用法1：简单问答，返回 String =====
    @SystemMessage("你是一名高级 Java 开发工程师，回答简洁专业")
    String chat(@UserMessage String question);

    @SystemMessage("你是一名代码审查专家，请从安全漏洞、代码规范、性能、设计四个维度审查")
    @UserMessage("请审查以下代码：\n{{code}}")
    String reviewCode(@V("code") String code);


    @SystemMessage("你是一名代码审查专家，请分析代码并返回结构化结果,用中文")
    @UserMessage("请审查以下代码：\n{{code}}")
    CodeReviewResult reviewCodeStructured(@V("code") String code);
}
