/**
 * API 客户端 —— 封装所有后端接口调用
 * 后端地址：http://localhost:8080
 */
const API_BASE = 'http://localhost:8080';

const api = {

    // ========== 文档管理 ==========

    /** 上传文档 */
    async uploadDocument(file, chunkSize, overlap, category = '', author = '', docDate = '') {
        const formData = new FormData();
        formData.append('file', file);
        const resp = await fetch(
            `${API_BASE}/kb/document/upload?chunkSize=${chunkSize}&overlap=${overlap}` +
            `&category=${encodeURIComponent(category)}&author=${encodeURIComponent(author)}&docDate=${encodeURIComponent(docDate)}`,
            { method: 'POST', body: formData }
        );
        return resp.json();
    },

    /** 文档列表 */
    async listDocuments(page = 0, size = 10, status = '') {
        let url = `${API_BASE}/kb/document/list?page=${page}&size=${size}`;
        if (status) url += `&status=${status}`;
        const resp = await fetch(url);
        return resp.json();
    },

    /** 文档详情 */
    async getDocument(id) {
        const resp = await fetch(`${API_BASE}/kb/document/${id}`);
        return resp.json();
    },

    /** 更新文档信息 */
    async updateDocument(id, data) {
        const resp = await fetch(`${API_BASE}/kb/document/${id}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data)
        });
        return resp.json();
    },

    /** 查询所有分类 */
    async listCategories() {
        const resp = await fetch(`${API_BASE}/kb/document/categories`);
        return resp.json();
    },

    /** 查询所有来源/作者 */
    async listAuthors() {
        const resp = await fetch(`${API_BASE}/kb/document/authors`);
        return resp.json();
    },

    /** 删除文档 */
    async deleteDocument(id) {
        const resp = await fetch(`${API_BASE}/kb/document/${id}`, { method: 'DELETE' });
        return resp.text();
    },

    /** 重新向量化 */
    async reindexDocument(id) {
        const resp = await fetch(`${API_BASE}/kb/document/${id}/reindex`, { method: 'POST' });
        return resp.text();
    },

    /** 重新切分 */
    async resplitDocument(id, chunkSize, overlap) {
        const resp = await fetch(
            `${API_BASE}/kb/document/${id}/resplit?chunkSize=${chunkSize}&overlap=${overlap}`,
            { method: 'POST' }
        );
        return resp.json();
    },

    // ========== 分块管理 ==========

    /** 文档的分块列表 */
    async listChunks(documentId) {
        const resp = await fetch(`${API_BASE}/kb/chunk/document/${documentId}`);
        return resp.json();
    },

    /** 分块详情 */
    async getChunk(chunkId) {
        const resp = await fetch(`${API_BASE}/kb/chunk/${chunkId}`);
        return resp.json();
    },

    /** 修改分块内容 */
    async updateChunk(chunkId, content) {
        const resp = await fetch(`${API_BASE}/kb/chunk/${chunkId}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ content })
        });
        return resp.json();
    },

    /** 删除分块 */
    async deleteChunk(chunkId) {
        const resp = await fetch(`${API_BASE}/kb/chunk/${chunkId}`, { method: 'DELETE' });
        return resp.text();
    },

    /** 追加分块到文档末尾（实时更新） */
    async addChunk(documentId, content) {
        const resp = await fetch(`${API_BASE}/kb/chunk/document/${documentId}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ content })
        });
        return resp.json();
    },

    // ========== 向量索引管理 ==========

    /** 语义检索（hybrid=true 时启用多路召回, rerank=true 时启用 Cross-Encoder 重排序） */
    async search(query, topK = 10, similarityThreshold = 0.3, hybrid = false, sourceFilter = '', rewrite = false, rerank = false, metadata = {}) {
        let url = `${API_BASE}/kb/vector/search?query=${encodeURIComponent(query)}&topK=${topK}&similarityThreshold=${similarityThreshold}&hybrid=${hybrid}&rewrite=${rewrite}&rerank=${rerank}`;
        if (sourceFilter) url += `&sourceFilter=${encodeURIComponent(sourceFilter)}`;
        if (metadata.category) url += `&category=${encodeURIComponent(metadata.category)}`;
        if (metadata.author) url += `&author=${encodeURIComponent(metadata.author)}`;
        if (metadata.dateFrom) url += `&dateFrom=${encodeURIComponent(metadata.dateFrom)}`;
        if (metadata.dateTo) url += `&dateTo=${encodeURIComponent(metadata.dateTo)}`;
        const resp = await fetch(url);
        return resp.json();
    },

    /** RAG 问答（hybrid=true 多路召回, rewrite=true Query改写, rerank=true Cross-Encoder重排序, conversationId 多轮对话） */
    async ask(question, topK = 10, similarityThreshold = 0.3, hybrid = false, sourceFilter = '', rewrite = false, rerank = false, conversationId = '', metadata = {}) {
        let url = `${API_BASE}/kb/vector/ask?question=${encodeURIComponent(question)}&topK=${topK}&similarityThreshold=${similarityThreshold}&hybrid=${hybrid}&rewrite=${rewrite}&rerank=${rerank}`;
        if (conversationId) url += `&conversationId=${encodeURIComponent(conversationId)}`;
        if (sourceFilter) url += `&sourceFilter=${encodeURIComponent(sourceFilter)}`;
        if (metadata.category) url += `&category=${encodeURIComponent(metadata.category)}`;
        if (metadata.author) url += `&author=${encodeURIComponent(metadata.author)}`;
        if (metadata.dateFrom) url += `&dateFrom=${encodeURIComponent(metadata.dateFrom)}`;
        if (metadata.dateTo) url += `&dateTo=${encodeURIComponent(metadata.dateTo)}`;
        const resp = await fetch(url);
        return resp.json();
    },

    /**
     * RAG 流式问答（SSE 逐 token 输出）
     * @param onToken 每收到一个 token 时的回调函数
     * @param signal AbortSignal，用于中断流
     */
    async askStream(question, topK, similarityThreshold, hybrid, sourceFilter, rewrite, rerank, conversationId, metadata, onToken, signal) {
        let url = `${API_BASE}/kb/vector/ask/stream?question=${encodeURIComponent(question)}&topK=${topK}&similarityThreshold=${similarityThreshold}&hybrid=${hybrid}&rewrite=${rewrite}&rerank=${rerank}`;
        if (conversationId) url += `&conversationId=${encodeURIComponent(conversationId)}`;
        if (sourceFilter) url += `&sourceFilter=${encodeURIComponent(sourceFilter)}`;
        if (metadata.category) url += `&category=${encodeURIComponent(metadata.category)}`;
        if (metadata.author) url += `&author=${encodeURIComponent(metadata.author)}`;
        if (metadata.dateFrom) url += `&dateFrom=${encodeURIComponent(metadata.dateFrom)}`;
        if (metadata.dateTo) url += `&dateTo=${encodeURIComponent(metadata.dateTo)}`;
        const resp = await fetch(url, { signal });
        const reader = resp.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop();
            for (const line of lines) {
                if (line.startsWith('data:')) {
                    onToken(line.slice(5));
                }
            }
        }
    },

    /** 清除会话记忆 */
    async clearConversation(conversationId) {
        const resp = await fetch(`${API_BASE}/kb/vector/conversation/${encodeURIComponent(conversationId)}`, { method: 'DELETE' });
        return resp.text();
    },

    /** 向量库统计 */
    async getStats() {
        const resp = await fetch(`${API_BASE}/kb/vector/stats`);
        return resp.json();
    },

    /** 删除文档的所有向量 */
    async deleteVectorsByDocId(documentId) {
        const resp = await fetch(`${API_BASE}/kb/vector/document/${documentId}`, { method: 'DELETE' });
        return resp.text();
    },

    /** 全量重建所有文档的向量（批量重建） */
    async rebuildAll() {
        const resp = await fetch(`${API_BASE}/kb/vector/rebuild`, { method: 'POST' });
        return resp.json();
    },

    /** 按状态批量重建（如重建所有 FAILED 的文档） */
    async rebuildByStatus(status) {
        const resp = await fetch(`${API_BASE}/kb/vector/rebuild/status/${status}`, { method: 'POST' });
        return resp.json();
    },

    // ========== 向量索引版本管理 ==========

    /** 列出所有版本 */
    async listVersions() {
        const resp = await fetch(`${API_BASE}/kb/version/list`);
        return resp.json();
    },

    /** 获取当前活跃版本 */
    async getActiveVersion() {
        const resp = await fetch(`${API_BASE}/kb/version/active`);
        return resp.json();
    },

    /** 初始化首个版本 */
    async initFirstVersion(description = '') {
        const url = `${API_BASE}/kb/version/init` + (description ? `?description=${encodeURIComponent(description)}` : '');
        const resp = await fetch(url, { method: 'POST' });
        return resp.json();
    },

    /** 创建新版本（BUILDING 状态） */
    async createVersion(description = '') {
        const url = `${API_BASE}/kb/version/create` + (description ? `?description=${encodeURIComponent(description)}` : '');
        const resp = await fetch(url, { method: 'POST' });
        return resp.json();
    },

    /** 灰度重建并切换（一键完成：创建新版本→全量向量化→原子切换） */
    async rebuildWithNewVersion(description = '') {
        const url = `${API_BASE}/kb/version/rebuild` + (description ? `?description=${encodeURIComponent(description)}` : '');
        const resp = await fetch(url, { method: 'POST' });
        return resp.json();
    },

    /** 激活版本（灰度切换） */
    async activateVersion(versionId) {
        const resp = await fetch(`${API_BASE}/kb/version/${versionId}/activate`, { method: 'POST' });
        return resp.json();
    },

    /** 回滚到指定版本 */
    async rollbackToVersion(versionId) {
        const resp = await fetch(`${API_BASE}/kb/version/${versionId}/rollback`, { method: 'POST' });
        return resp.json();
    },

    /** 清理指定版本的向量数据 */
    async cleanupVersion(versionId) {
        const resp = await fetch(`${API_BASE}/kb/version/${versionId}`, { method: 'DELETE' });
        return resp.json();
    }
};
