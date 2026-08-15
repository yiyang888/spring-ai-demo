package cn.yiyang.springai.controller.chat;

import cn.yiyang.springai.model.JobAnalysisResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JobController {
    private final ChatClient chatClient;
    public JobController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @GetMapping("/job/analyze")
    public JobAnalysisResult analyzeJob(@RequestParam(defaultValue = "") String jd){
        if(jd.isBlank()){
            jd = """
                 岗位：Java 后端开发工程师
                 公司：某互联网公司
                 薪资：15-25K·14薪
                 
                 任职要求：
                 1. 3年以上 Java 后端开发经验
                 2. 熟悉 Spring Boot、Spring Cloud 微服务架构
                 3. 熟悉 MySQL，了解索引优化、分库分表
                 4. 熟悉 Redis，有缓存设计经验
                 5. 熟悉 RabbitMQ 或 Kafka 消息中间件
                 6. 有 Docker、K8s 容器化经验者优先
                 7. 有高并发系统设计经验者优先
                 """;
        }
    // 你的简历技能（实际项目里可以从数据库或配置读取）
        String mySkills = "Java、Spring Boot、Spring MVC、MyBatis、MySQL、Redis、Git";

        return chatClient.prompt()
                .system("你是一名资深技术招聘专家，擅长分析 JD 并评估候选人匹配度。")
                .user("""
                      请分析以下 JD，提取岗位信息，并根据候选人的技能评估匹配度。

                      JD 原文：
                      %s

                      候选人技能：
                      %s

                      要求：
                      1. 提取岗位名称、公司名称、薪资范围
                      2. 对比候选人技能和 JD 要求，计算匹配度评分（0-100）
                      3. 列出候选人已具备的技能
                      4. 列出候选人缺少的技能
                      5. 给出补强建议
                      """.formatted(jd, mySkills)).call().entity(JobAnalysisResult.class);
    }

}
