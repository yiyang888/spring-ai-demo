-- ============================================================
-- 知识库 CRUD 模块建表脚本
-- Spring Boot 启动时自动执行（spring.sql.init.mode=always）
--
-- 三张表说明：
--   1. kb_document  —— 文档元数据表（本脚本创建）
--   2. kb_chunk     —— 分块内容表（本脚本创建）
--   3. kb_vector    —— 向量数据表（Spring AI PgVectorStore 自动创建，本脚本不干预）
--      kb_vector 标准结构：id(UUID) / content(TEXT) / metadata(JSONB) / embedding(vector)
--      由 application.yml 中 initialize-schema: true 触发自动创建
--
-- 表关系：
--   kb_document (1) ──FK── (N) kb_chunk (1) ──vector_id── (1) kb_vector
--   kb_chunk.document_id  → kb_document.id   （物理外键，ON DELETE CASCADE）
--   kb_chunk.vector_id    → kb_vector.id     （逻辑关联，无外键约束）
--   kb_vector.metadata    JSONB 中存 document_id / chunk_id / source（由代码写入）
--
-- vector_store 表是之前 demo 项目的遗留表，与本模块无关
-- ============================================================

-- ============================================================
-- 1. 文档元数据表：记录每个上传文档的基本信息、处理状态、切分参数
-- ============================================================
CREATE TABLE IF NOT EXISTS kb_document (
    id              BIGSERIAL    PRIMARY KEY,             -- 自增主键
    title           VARCHAR(255) NOT NULL,                -- 文档标题（通常等于文件名，可修改）
    file_name       VARCHAR(500) NOT NULL,                -- 原始文件名（唯一约束，防止同名重复上传）
    file_format     VARCHAR(20)  NOT NULL,                -- 文件扩展名：txt / md / pdf / doc / docx
    file_size       BIGINT       NOT NULL DEFAULT 0,      -- 文件大小（字节），用于展示和校验
    content_hash    VARCHAR(64),                          -- 文件内容的 MD5 哈希值（用于内容去重，相同内容拒绝重复上传）
    description     TEXT,                                 -- 文档描述/备注（用户可编辑）
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- 文档处理状态：PENDING(待处理) / PROCESSING(处理中) / READY(就绪) / FAILED(失败)
    chunk_count     INT          NOT NULL DEFAULT 0,      -- 切分后的分块总数（处理完成后回写）
    total_tokens    INT          NOT NULL DEFAULT 0,      -- 所有分块的 Token 总数（向量化时统计）
    chunk_size      INT,                                  -- 切分时每块的目标字符数（如 500，NULL 表示用默认值）
    overlap         INT,                                  -- 切分时相邻块的重叠字符数（如 100，保证上下文连贯性）
    splitter_type   VARCHAR(50)  DEFAULT 'RECURSIVE',     -- 切分器类型：RECURSIVE(递归切分) / TOKEN(按Token切分) / etc.
    raw_text        TEXT,                                 -- 从文件提取的原始全文（冗余存储，用于重新切分时无需重新读取文件）
    error_message   TEXT,                                 -- 处理失败时的异常信息（status=FAILED 时填充，便于排查）
    category        VARCHAR(100),                         -- 文档分类（如：技术/产品/管理/论文，用于按分类精准过滤）
    author          VARCHAR(200),                         -- 文档来源/作者（文档出处，如：官网/内部wiki/某作者）
    doc_date        DATE,                                 -- 文档日期（文档内容所属时间，区别于上传时间 created_at，用于按时间范围过滤）
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- 记录创建时间（文档上传时间）
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- 记录最后更新时间（状态变更/信息修改时自动刷新）
    UNIQUE(file_name)                                                     -- 唯一约束：同一文件名不能重复上传
);

-- 兼容已存在的库：为旧表补充新增的元数据字段（IF NOT EXISTS 避免重复执行报错）
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS category VARCHAR(100);
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS author   VARCHAR(200);
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS doc_date DATE;

-- 索引：按状态筛选文档列表（如"只看 READY 的文档"）
CREATE INDEX IF NOT EXISTS idx_kb_document_status ON kb_document(status);
-- 索引：按内容哈希查重（上传时检查是否已存在相同内容的文档）
CREATE INDEX IF NOT EXISTS idx_kb_document_hash  ON kb_document(content_hash);
-- 索引：按分类筛选文档（元数据精准过滤）
CREATE INDEX IF NOT EXISTS idx_kb_document_category ON kb_document(category);

-- ============================================================
-- 2. 分块内容表：记录每个文档切分后的文本块，以及与向量的关联关系
-- ============================================================
CREATE TABLE IF NOT EXISTS kb_chunk (
    id              BIGSERIAL    PRIMARY KEY,             -- 自增主键
    document_id     BIGINT       NOT NULL,                -- 所属文档ID（外键 → kb_document.id，删除文档时级联删除分块）
    chunk_index     INT          NOT NULL,                -- 分块序号（从0开始，标识该块在文档中的位置顺序）
    content         TEXT         NOT NULL,                -- 分块的文本内容（切分后的文本片段，可编辑修改）
    content_length  INT          NOT NULL DEFAULT 0,      -- 文本内容长度（字符数，用于展示和校验）
    token_count     INT          DEFAULT 0,               -- 该分块的 Token 数量（向量化时由模型返回）
    vector_id       VARCHAR(36),                          -- 关联的向量ID（→ kb_vector.id，Spring AI 生成的 UUID，向量化入库后回写）
    metadata        JSONB        DEFAULT '{}'::jsonb,     -- 扩展元数据（JSON格式，存储额外信息如来源页码、段落位置等）
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- 分块记录创建时间
    FOREIGN KEY (document_id) REFERENCES kb_document(id) ON DELETE CASCADE,  -- 外键：删除文档时自动删除其所有分块
    UNIQUE(document_id, chunk_index)                                      -- 唯一约束：同一文档内分块序号不能重复
);

-- 索引：按文档ID查询所有分块（查看文档详情时使用）
CREATE INDEX IF NOT EXISTS idx_kb_chunk_doc ON kb_chunk(document_id);
-- 索引：按向量ID查分块（语义检索命中向量后，反查对应的文本内容）
CREATE INDEX IF NOT EXISTS idx_kb_chunk_vec ON kb_chunk(vector_id);

-- ============================================================
-- 3. kb_vector 表由 Spring AI PgVectorStore 自动创建，结构如下（仅供参考，不在本脚本管理）：
--    id          UUID         PRIMARY KEY     -- Spring AI 自动生成的 UUID
--    content     TEXT                         -- 文本内容（与 kb_chunk.content 冗余，Spring AI 向量化时写入）
--    metadata    JSONB                        -- 元数据（存 document_id / chunk_id / source / chunkIndex / version）
--    embedding   vector(1024)                 -- 1024维向量（由 text-embedding-v3 模型生成）
--
--    ★ 版本管理：metadata 中的 version 字段标记每条向量所属的索引版本号
--      检索/RAG 问答时通过 FilterExpression 只查 ACTIVE 版本的向量
-- ============================================================

-- ============================================================
-- 4. 向量索引版本管理表：记录每个向量索引版本的生命周期
--    支持灰度切换（新版本就绪后原子切换）和一键回滚（切换回旧版本）
--
--    版本状态流转：
--      BUILDING → ACTIVE → ARCHIVED
--                   ↑         |
--                   └─回滚─────┘
--
--    灰度切换流程：
--      ① 创建新版本（BUILDING）→ ② 构建向量（metadata 带 version=新版本号）
--      → ③ 原子切换（旧版本 ACTIVE→ARCHIVED，新版本 BUILDING→ACTIVE）
--
--    回滚流程：
--      ① 选择一个 ARCHIVED 版本 → ② 原子切换（当前 ACTIVE→ARCHIVED，选定 ARCHIVED→ACTIVE）
--      → ③ 旧版本向量仍在 kb_vector 中，秒级回滚
-- ============================================================
CREATE TABLE IF NOT EXISTS kb_index_version (
    id              BIGSERIAL    PRIMARY KEY,                          -- 版本号（自增，也是版本ID）
    version_label   VARCHAR(50)  NOT NULL,                             -- 版本标签（如 v1, v2, v3，用于展示）
    status          VARCHAR(20)  NOT NULL DEFAULT 'BUILDING',           -- 版本状态：BUILDING(构建中) / ACTIVE(当前使用) / ARCHIVED(已归档)
    description     TEXT,                                              -- 版本描述（如 "embedding模型从v2升级到v3" / "全量重建"）
    vector_count    INT          NOT NULL DEFAULT 0,                   -- 该版本的向量数量（构建完成后统计）
    document_count  INT          NOT NULL DEFAULT 0,                   -- 该版本包含的文档数
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,   -- 版本创建时间
    activated_at    TIMESTAMP,                                         -- 变为 ACTIVE 的时间（灰度切换完成时间）
    archived_at     TIMESTAMP                                          -- 变为 ARCHIVED 的时间（被新版本替换时间）
);

-- 唯一约束：同一时间只能有一个 ACTIVE 版本（数据库层面保证灰度切换的原子性）
CREATE UNIQUE INDEX IF NOT EXISTS idx_kb_index_version_active
    ON kb_index_version(status) WHERE status = 'ACTIVE';

-- 索引：按状态查询版本列表
CREATE INDEX IF NOT EXISTS idx_kb_index_version_status ON kb_index_version(status);
