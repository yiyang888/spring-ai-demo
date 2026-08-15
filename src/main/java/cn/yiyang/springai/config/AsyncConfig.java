package cn.yiyang.springai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置
 *
 * 用途：文档上传后的异步处理（文本提取 → 切分 → 向量化 → 入库）
 *       确保在线检索服务不受文档导入影响
 *
 * 线程池参数说明：
 *   corePoolSize=2     核心线程数（同时处理 2 个文档）
 *   maxPoolSize=4      峰值线程数（积压时最多 4 个并发）
 *   queueCapacity=50   等待队列（超过核心线程数的任务排队，最多 50 个）
 *   CallerRunsPolicy   队列满时由提交者线程执行（背压，不丢任务）
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("kbTaskExecutor")
    public ThreadPoolTaskExecutor kbTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("kb-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
