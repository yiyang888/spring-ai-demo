# Spring AI 知识库 RAG 系统

> 基于 Spring AI 1.0.0 + LangChain4j 构建的企业级知识库检索增强生成（RAG）系统
>
> 覆盖文档管理、向量检索、混合召回、Cross-Encoder 重排序、多轮对话、SSE 流式输出、版本管理、缓存降级全链路

## 系统架构

```
                              ┌─────────────────────────────────────────────────┐
                              │                   前端 (frontend/)               │
                              │    文档上传 │ 语义检索 │ RAG 问答 │ 版本管理     │
                              └────────────────────┬────────────────────────────┘
                                                   │ HTTP / SSE
                              ┌────────────────────▼────────────────────────────┐
                              │              Spring Boot 3.x (8080)              │
                              │  ┌───────────────────────────────────────────┐  │
                              │  │           Controller Layer                │  │
                              │  │  DocumentController │ VectorIndexController│  │
                              │  │  ChunkController │ IndexVersionController  │  │
                              │  │  ChatController │ StructuredController     │  │
                              │  └───────────────────┬───────────────────────┘  │
                              │  ┌───────────────────▼───────────────────────┐  │
                              │  │            Service Layer                  │  │
                              │  │                                           │  │
                              │  │  ┌─────────────┐  ┌──────────────────┐   │  │
                              │  │  │ DocumentSvc │  │ VectorIndexSvc   │   │  │
                              │  │  │ 文档上传/切分│  │ 检索/RAG/流式输出│   │  │
                              │  │  └──────┬──────┘  └────────┬─────────┘   │  │
                              │  │         │                   │             │  │
                              │  │  ┌──────▼──────────────────▼─────────┐   │  │
                              │  │  │       HybridSearchService          │   │  │
                              │  │  │  向量检索 + 关键词检索 + 元数据检索 │   │  │
                              │  │  │         RRF 融合排序               │   │  │
                              │  │  └────────────────┬──────────────────┘   │  │
                              │  │  ┌────────────────▼──────────────────┐   │  │
                              │  │  │  RerankerService │ QueryRewriteSvc│   │  │
                              │  │  │  Cross-Encoder重排│ LLM查询改写   │   │  │
                              │  │  └────────────────┬──────────────────┘   │  │
                              │  │  ┌────────────────▼──────────────────┐   │  │
                              │  │  │  KbCacheService │ DegradeHandler  │   │  │
                              │  │  │  Redis缓存层     │ 降级兜底策略    │   │  │
                              │  │  └───────────────────────────────────┘   │  │
                              │  └───────────────────────────────────────────┘  │
                              └──────┬──────────────┬──────────────┬───────────┘
                                     │              │              │
                          ┌──────────▼──┐  ┌───────▼───────┐  ┌──▼──────────┐
                          │ PostgreSQL  │  │    Redis 7    │  │  百炼 API   │
                          │ + PgVector  │  │   缓存层      │  │ qwen-plus   │
                          │ HNSW 索引   │  │  TTL 30min   │  │ text-emb-v3 │
                          │ kb_document │  │  随机抖动     │  │ qwen3-rerank│
                          │ kb_chunk    │  │               │  │             │
                          │ kb_vector   │  │               │  │             │
                          └─────────────┘  └───────────────┘  └─────────────┘
```

## 技术栈

| 层级 | 技术选型 | 版本 |
|------|---------|------|
| **框架** | Spring Boot | 3.x |
| **AI 框架** | Spring AI | 1.0.0 |
| **AI 框架** | LangChain4j（对比集成） | 1.0.0-beta5 |
| **大模型** | 阿里云百炼 qwen-plus | - |
| **Embedding** | text-embedding-v3（1024 维） | - |
| **重排序** | qwen3-rerank（Cross-Encoder） | - |
| **向量数据库** | PostgreSQL + PgVector | PG 16 / HNSW |
| **缓存** | Redis | 7-alpine |
| **ORM** | Spring Data JPA | - |
| **构建工具** | Maven | - |
| **前端** | 原生 HTML + CSS + JS | - |

## 核心功能

### 文档管理
- 支持 TXT / MD / PDF / DOC / DOCX 格式上传
- 异步处理：上传即返回，后台自动切分 + 向量化
- 文档元数据：分类（category）、来源（author）、日期（docDate）
- 递归切分：chunkSize=500，overlap=100，防止信息碎片化

### 向量检索
- 基于 PgVector HNSW 索引的高性能语义检索
- 支持按版本、来源、分类、作者、日期多维度过滤
- similarityThreshold 可调（0~1，越大越严格）

### 混合召回（三通道 + RRF 融合）
```
用户查询
  ├── 向量检索（语义匹配）
  ├── 关键词检索（精确匹配，SQL ILIKE）
  └── 元数据检索（结构化过滤）
       │
       ▼
  RRF 融合排序（k=60）
       │
       ▼
  Top-K 结果
```

### Cross-Encoder 重排序
- 基于 qwen3-rerank 模型对检索结果二次精排
- 失败自动降级为 RRF 排序结果，不影响主流程
- 仅在多路召回或 Query 改写启用时可用

### RAG 问答
- 多轮对话：基于 ChatMemory 的上下文记忆
- SSE 流式输出：逐 Token 返回，用户体验更好
- Query 改写：LLM 改写查询提升召回率
- 引用来源：返回答案来源的文档信息

### 版本管理
- 向量版本状态流转：BUILDING → ACTIVE → ARCHIVED
- 同一时间只有一个 ACTIVE 版本
- 切换/回滚仅修改状态，不删除旧向量数据
- 向量 metadata 中标记 version 字段，检索时自动按版本过滤

### 缓存与降级
- Redis 缓存检索结果和 AI 回答，TTL 30 分钟 + 0~5 分钟随机抖动
- 缓存 key 包含过滤条件签名，不同过滤条件缓存隔离
- 知识库变更时自动清除缓存
- 模型不可用时：缓存兜底 → 降级检索 → 引导留言

### 工程化
- 全局异常处理：BusinessException + @RestControllerAdvice
- 请求超时分级控制：RerankerService 5s/30s、Spring AI 10s/60s、SSE 5min
- Redis 安全：BasicPolymorphicTypeValidator 防止反序列化漏洞
- 异步文档处理队列，不阻塞 HTTP 请求

## 快速开始

### 1. 环境准备

```bash
# 启动 PgVector 容器
docker run -d --name pgvector \
  -p 5432:5432 \
  -e POSTGRES_DB=vectordb \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  pgvector/pgvector:pg16

# 启动 Redis 容器
docker run -d --name kb-redis \
  -p 6379:6379 \
  redis:7-alpine
```

### 2. 配置 API Key

```bash
# 设置阿里云百炼 API Key（环境变量方式，不要硬编码到代码中）
# Windows PowerShell
$env:DASHSCOPE_API_KEY="your_api_key_here"

# Linux / macOS
export DASHSCOPE_API_KEY=your_api_key_here
```

### 3. 启动应用

```bash
mvn spring-boot:run
```

应用启动后访问 `http://localhost:8080`

### 4. 使用前端界面

打开 `frontend/index.html` 即可使用知识库管理界面：
- 上传文档（支持元数据填写）
- 语义检索（支持多条件过滤）
- RAG 问答（支持多轮对话、流式输出）
- 版本管理

## API 接口

### 文档管理

| 接口 | 方法 | 参数 | 说明 |
|------|------|------|------|
| `/doc/upload` | POST | file, chunkSize, overlap, category, author, docDate | 上传文档（异步处理） |
| `/doc/list` | GET | - | 文档列表 |
| `/doc/{id}` | GET | - | 文档详情 |
| `/doc/{id}` | PUT | title, description, category, author, docDate | 更新文档元数据 |
| `/doc/{id}` | DELETE | - | 删除文档及关联向量 |
| `/doc/{id}/reembed` | POST | - | 重新向量化文档 |
| `/doc/{id}/rechunk` | POST | chunkSize, overlap | 重新切分文档 |

### 检索与问答

| 接口 | 方法 | 参数 | 说明 |
|------|------|------|------|
| `/search` | GET | question, topK, threshold, hybrid, rewrite, rerank, source, category, author, dateFrom, dateTo | 语义检索 |
| `/ask` | POST | question, topK, threshold, hybrid, rewrite, rerank, conversationId, filter | RAG 问答 |
| `/ask/stream` | POST | 同 /ask | RAG 流式问答（SSE） |
| `/conversation/{id}` | DELETE | - | 清除对话记忆 |

### 分块管理

| 接口 | 方法 | 说明 |
|------|------|------|
| `/chunk/list` | GET | 分块列表（按文档 ID） |
| `/chunk/{id}` | PUT | 更新分块内容 |
| `/chunk/{id}` | DELETE | 删除分块 |

### 版本管理

| 接口 | 方法 | 说明 |
|------|------|------|
| `/version/list` | GET | 版本列表 |
| `/version/create` | POST | 创建新版本（BUILDING） |
| `/version/{id}/activate` | PUT | 发布版本（ACTIVE） |
| `/version/{id}/archive` | PUT | 归档版本（ARCHIVED） |

## 数据库设计

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   kb_document    │     │    kb_chunk     │     │   kb_vector     │
├─────────────────┤     ├─────────────────┤     ├─────────────────┤
│ id (PK)         │◄──┐ │ id (PK)         │◄──┐ │ id (PK)         │
│ title           │   └─│ document_id (FK) │   └─│ chunk_id (FK)   │
│ file_name       │     │ content          │     │ content         │
│ file_hash       │     │ chunk_index      │     │ embedding       │
│ file_type       │     │ token_count      │     │ metadata (JSONB)│
│ file_size       │     │ created_at       │     │  - source       │
│ chunk_count     │     └─────────────────┘     │  - version      │
│ category        │                              │  - category     │
│ author          │     ┌─────────────────┐     │  - author       │
│ doc_date        │     │ kb_index_version │     │  - doc_date     │
│ description     │     ├─────────────────┤     │ distance        │
│ status          │     │ id (PK)         │     │ created_at      │
│ created_at      │     │ version_number   │     └─────────────────┘
│ updated_at      │     │ status           │
└─────────────────┘     │ created_at       │
                        └─────────────────┘
```

## 项目结构

```
spring-ai-demo/
├── src/main/java/cn/yiyang/
│   ├── SpringAiDemoApplication.java            # 启动类
│   ├── langchain4j/                            # LangChain4j 对比集成
│   │   ├── config/LangChain4jConfig.java       #   配置（@Lazy 延迟初始化）
│   │   ├── controller/                         #   接口
│   │   ├── dao/                                #   AI 服务接口
│   │   └── service/InMemoryChatMemoryStore     #   聊天记忆
│   ├── repository/                             # JPA 仓库
│   │   ├── DocumentRepository.java
│   │   ├── ChunkRepository.java
│   │   └── IndexVersionRepository.java
│   └── springai/
│       ├── config/                             # 配置
│       │   ├── RedisConfig.java                #   Redis（安全反序列化）
│       │   ├── AsyncConfig.java                #   异步线程池
│       │   ├── ChatMemoryConfig.java           #   对话记忆
│       │   └── CorsConfig.java                 #   跨域
│       ├── controller/
│       │   ├── chat/                           # 聊天接口
│       │   ├── demo/                           # 基础演示
│       │   └── kb/                             # 知识库核心接口
│       ├── exception/                          # 全局异常
│       │   ├── BusinessException.java
│       │   └── GlobalExceptionHandler.java
│       ├── model/
│       │   ├── dto/                            # DTO（MetadataFilter 等）
│       │   ├── entity/                         # 实体（KbDocument 等）
│       │   ├── enums/                          # 枚举（状态流转）
│       │   └── vo/                             # VO
│       ├── service/
│       │   ├── DocumentService.java            # 文档管理
│       │   ├── DocumentAsyncService.java       # 异步处理
│       │   ├── VectorIndexService.java         # 向量检索 + RAG
│       │   ├── HybridSearchService.java        # 混合召回 + RRF
│       │   ├── RerankerService.java            # Cross-Encoder 重排序
│       │   ├── QueryRewriteService.java        # Query 改写
│       │   ├── IndexVersionService.java        # 版本管理
│       │   ├── KbCacheService.java             # Redis 缓存
│       │   ├── DegradeHandler.java             # 降级处理
│       │   └── ChunkService.java               # 分块管理
│       └── transformer/
│           └── RecursiveTextSplitter.java      # 递归文本切分
├── src/main/resources/
│   ├── application.yml                         # 主配置
│   └── schema.sql                              # 建表脚本
├── frontend/                                   # 前端界面
│   ├── index.html
│   ├── app.js
│   ├── api.js
│   └── style.css
├── pom.xml
└── mvnw / mvnw.cmd
```

## 配置说明

### application.yml 关键配置

```yaml
spring:
  ai:
    openai:
      api-key: ${DASHSCOPE_API_KEY}              # 百炼 API Key（环境变量）
      base-url: https://dashscope.aliyuncs.com/compatible-mode  # 去掉 /v1
      chat:
        options:
          model: qwen-plus                       # 可换 qwen-turbo / qwen-max
      embedding:
        options:
          model: text-embedding-v3               # 1024 维
    vectorstore:
      pgvector:
        table-name: kb_vector                    # 自定义表名
        index-type: HNSW                         # 推荐索引类型
        distance-type: COSINE_DISTANCE           # 余弦距离
        dimensions: 1024                         # 必须和 embedding 模型一致
        initialize-schema: true                  # 自动建表
```

## 开发说明

### 环境要求
- JDK 17+
- Maven 3.6+
- Docker（用于 PgVector 和 Redis）
- 阿里云百炼 API Key

### 本地开发

```bash
# 编译
mvn compile

# 运行测试
mvn test

# 打包
mvn package -DskipTests

# 运行 JAR
java -jar target/spring-ai-demo-0.0.1-SNAPSHOT.jar
```

### 注意事项
1. PgVector 容器必须先于应用启动
2. API Key 通过环境变量 `DASHSCOPE_API_KEY` 配置，不要硬编码
3. LangChain4j 的 `@Lazy` 注解确保数据库不可用时不影响应用启动
4. 首次启动会自动执行 `schema.sql` 创建数据库表
5. Embedding 批量大小不超过 10（百炼 API 限制）

## License

MIT
