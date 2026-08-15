# Spring AI 知识库 RAG 系统

基于 Spring AI 1.0.0 + LangChain4j 构建的知识库检索增强生成（RAG）系统，支持文档管理、向量检索、混合召回、多轮对话、流式输出等功能。

## 技术栈

| 组件 | 技术 |
|------|------|
| 框架 | Spring Boot 3.x + Spring AI 1.0.0 |
| 大模型 | 阿里云百炼（qwen-plus / text-embedding-v3 / qwen3-rerank） |
| 向量数据库 | PostgreSQL + PgVector（HNSW 索引） |
| 缓存 | Redis 7 |
| ORM | Spring Data JPA |
| LangChain4j | 1.0.0-beta5（对比集成） |

## 核心功能

- **文档管理**：支持 TXT / MD / PDF / DOC / DOCX 上传，异步切分+向量化
- **向量检索**：语义检索，支持按版本、来源、分类、作者、日期过滤
- **混合召回**：向量检索 + 关键词检索 + 元数据检索，RRF 融合排序
- **Cross-Encoder 重排序**：基于 qwen3-rerank 模型二次精排，失败自动降级
- **Query 改写**：LLM 改写查询提升召回率
- **RAG 问答**：多轮对话支持，SSE 流式输出
- **版本管理**：向量版本 BUILDING → ACTIVE → ARCHIVED 状态流转
- **缓存层**：Redis 缓存检索结果和 AI 回答，TTL 30min + 随机抖动
- **异常降级**：模型不可用时缓存兜底 + 引导留言

## 快速开始

### 1. 环境准备

```bash
# Docker 启动 PgVector + Redis
docker run -d --name pgvector -p 5432:5432 -e POSTGRES_DB=vectordb -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres pgvector/pgvector:pg16
docker run -d --name kb-redis -p 6379:6379 redis:7-alpine
```

### 2. 配置 API Key

```bash
# 设置百炼 API Key 环境变量
export DASHSCOPE_API_KEY=your_api_key_here
```

### 3. 启动应用

```bash
mvn spring-boot:run
```

### 4. 访问前端

打开 `frontend/index.html` 即可使用知识库管理界面。

## 项目结构

```
src/main/java/cn/yiyang/
├── SpringAiDemoApplication.java       # 启动类
├── langchain4j/                        # LangChain4j 集成模块
│   ├── config/                         # LangChain4j 配置
│   ├── controller/                     # LangChain4j 接口
│   ├── dao/                            # AI 服务接口
│   └── service/                        # 聊天记忆存储
├── repository/                         # JPA 仓库
├── springai/
│   ├── config/                         # Spring AI 配置（Redis/CORS/Async/Memory）
│   ├── controller/
│   │   ├── chat/                       # 聊天相关接口
│   │   ├── demo/                       # 基础演示接口
│   │   └── kb/                         # 知识库核心接口
│   ├── exception/                      # 全局异常处理
│   ├── model/                          # 实体/DTO/VO/枚举
│   ├── service/                        # 核心业务逻辑
│   │   ├── DocumentService.java        # 文档管理
│   │   ├── VectorIndexService.java     # 向量检索 + RAG
│   │   ├── HybridSearchService.java    # 混合召回
│   │   ├── RerankerService.java        # Cross-Encoder 重排序
│   │   ├── QueryRewriteService.java    # Query 改写
│   │   ├── IndexVersionService.java    # 版本管理
│   │   ├── KbCacheService.java         # Redis 缓存
│   │   └── DegradeHandler.java         # 降级处理
│   └── transformer/                    # 文本切分器
└── resources/
    ├── application.yml                 # 主配置
    └── schema.sql                      # 数据库建表脚本
```

## API 一览

| 接口 | 方法 | 说明 |
|------|------|------|
| `/doc/upload` | POST | 上传文档（异步处理） |
| `/doc/list` | GET | 文档列表 |
| `/doc/{id}` | PUT | 更新文档元数据 |
| `/doc/{id}` | DELETE | 删除文档 |
| `/search` | GET | 语义检索 |
| `/ask` | POST | RAG 问答 |
| `/ask/stream` | POST | RAG 流式问答（SSE） |
| `/conversation/{id}` | DELETE | 清除对话记忆 |
| `/version/*` | - | 版本管理接口 |
