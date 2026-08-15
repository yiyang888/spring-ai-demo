/**
 * 知识库管理系统 —— Vue 3 应用
 */
const { createApp, ref, reactive, onMounted, onUnmounted } = Vue;

createApp({
    setup() {
        // ========== 导航 ==========
        const tabs = [
            { key: 'dashboard', icon: '📊', label: '仪表盘' },
            { key: 'documents', icon: '📄', label: '文档管理' },
            { key: 'chunks',    icon: '🧩', label: '分块管理' },
            { key: 'search',    icon: '🔍', label: '向量检索' },
            { key: 'rag',       icon: '💬', label: 'RAG 问答' },
            { key: 'versions',  icon: '🔄', label: '索引版本' },
        ];
        const activeTab = ref('dashboard');

        // ========== 全局状态 ==========
        const loading = ref(false);
        const toast = ref({ show: false, message: '', type: 'success' });

        function showToast(message, type = 'success') {
            toast.value = { show: true, message, type };
            setTimeout(() => { toast.value.show = false; }, 3000);
        }

        // ========== 仪表盘 ==========
        const stats = ref({});

        async function loadStats() {
            try {
                stats.value = await api.getStats();
            } catch (e) {
                showToast('加载统计失败: ' + e.message, 'error');
            }
        }

        // ========== 文档管理 ==========
        const documents = ref([]);
        const allDocuments = ref([]); // 用于分块管理的选择器
        const docPage = ref(0);
        const docSize = ref(10);
        const docTotal = ref(0);
        const docStatusFilter = ref('');

        const uploadFile = ref(null);
        const uploadChunkSize = ref(500);
        const uploadOverlap = ref(100);
        const uploadCategory = ref('');
        const uploadAuthor = ref('');
        const uploadDocDate = ref('');
        const dragover = ref(false);

        const docDetail = ref(null);
        const showDocDetail = ref(false);

        const resplitDoc = ref(null);
        const showResplit = ref(false);
        const resplitChunkSize = ref(500);
        const resplitOverlap = ref(100);

        async function loadDocuments() {
            try {
                const data = await api.listDocuments(docPage.value, docSize.value, docStatusFilter.value);
                documents.value = data.documents || [];
                docTotal.value = data.total || 0;
            } catch (e) {
                showToast('加载文档列表失败: ' + e.message, 'error');
            }
        }

        async function loadAllDocuments() {
            try {
                const data = await api.listDocuments(0, 1000, '');
                allDocuments.value = data.documents || [];
            } catch (e) {
                showToast('加载文档列表失败: ' + e.message, 'error');
            }
        }

        function handleFileSelect(e) {
            const file = e.target.files[0];
            if (file) uploadFile.value = file;
        }

        function handleDrop(e) {
            dragover.value = false;
            const file = e.dataTransfer.files[0];
            if (file) uploadFile.value = file;
        }

        async function uploadDocument() {
            if (!uploadFile.value) return;
            loading.value = true;
            try {
                await api.uploadDocument(uploadFile.value, uploadChunkSize.value, uploadOverlap.value,
                    uploadCategory.value, uploadAuthor.value, uploadDocDate.value);
                showToast('文档已提交，正在异步处理中...');
                uploadFile.value = null;
                uploadCategory.value = '';
                uploadAuthor.value = '';
                uploadDocDate.value = '';
                await loadDocuments();
                await loadStats();
                startPolling();  // 启动轮询，监控处理进度
            } catch (e) {
                showToast('上传失败: ' + e.message, 'error');
            } finally {
                loading.value = false;
            }
        }

        async function viewDocument(id) {
            try {
                docDetail.value = await api.getDocument(id);
                showDocDetail.value = true;
            } catch (e) {
                showToast('加载详情失败: ' + e.message, 'error');
            }
        }

        async function deleteDocument(id) {
            if (!confirm('确认删除此文档？关联的分块和向量将一并删除。')) return;
            loading.value = true;
            try {
                await api.deleteDocument(id);
                showToast('文档已删除');
                await loadDocuments();
                await loadStats();
            } catch (e) {
                showToast('删除失败: ' + e.message, 'error');
            } finally {
                loading.value = false;
            }
        }

        async function reindexDocument(id) {
            if (!confirm('确认重新向量化此文档？')) return;
            loading.value = true;
            try {
                await api.reindexDocument(id);
                showToast('重新向量化完成');
                await loadDocuments();
            } catch (e) {
                showToast('重新向量化失败: ' + e.message, 'error');
            } finally {
                loading.value = false;
            }
        }

        function showResplitModal(doc) {
            resplitDoc.value = doc;
            resplitChunkSize.value = doc.chunkSize || 500;
            resplitOverlap.value = doc.overlap || 100;
            showResplit.value = true;
        }

        async function doResplit() {
            if (!resplitDoc.value) return;
            loading.value = true;
            try {
                await api.resplitDocument(resplitDoc.value.id, resplitChunkSize.value, resplitOverlap.value);
                showToast('重新切分完成');
                showResplit.value = false;
                await loadDocuments();
                await loadStats();
            } catch (e) {
                showToast('重新切分失败: ' + e.message, 'error');
            } finally {
                loading.value = false;
            }
        }

        const categories = ref([]);
        const authors = ref([]);

        async function loadCategories() {
            try {
                categories.value = await api.listCategories();
            } catch (e) { /* ignore */ }
        }

        async function loadAuthors() {
            try {
                authors.value = await api.listAuthors();
            } catch (e) { /* ignore */ }
        }

        // ========== 分块管理 ==========
        const chunkDocId = ref('');
        const chunks = ref([]);

        const editChunk = ref(null);
        const showEditChunk = ref(false);
        const editChunkContent = ref('');

        async function loadChunks() {
            if (!chunkDocId.value) {
                chunks.value = [];
                return;
            }
            try {
                chunks.value = await api.listChunks(chunkDocId.value);
            } catch (e) {
                showToast('加载分块失败: ' + e.message, 'error');
            }
        }

        async function viewChunkDetail(id) {
            try {
                const chunk = await api.getChunk(id);
                // 在编辑弹窗中展示完整内容
                editChunk.value = chunk;
                editChunkContent.value = chunk.content;
                showEditChunk.value = true;
            } catch (e) {
                showToast('加载分块详情失败: ' + e.message, 'error');
            }
        }

        function showEditChunkModal(chunk) {
            editChunk.value = chunk;
            editChunkContent.value = chunk.content;
            showEditChunk.value = true;
        }

        async function saveChunk() {
            if (!editChunk.value) return;
            loading.value = true;
            try {
                await api.updateChunk(editChunk.value.id, editChunkContent.value);
                showToast('分块已更新并重新向量化');
                showEditChunk.value = false;
                await loadChunks();
            } catch (e) {
                showToast('保存失败: ' + e.message, 'error');
            } finally {
                loading.value = false;
            }
        }

        async function deleteChunk(id) {
            if (!confirm('确认删除此分块？关联的向量也将删除。')) return;
            loading.value = true;
            try {
                await api.deleteChunk(id);
                showToast('分块已删除');
                await loadChunks();
                await loadStats();
            } catch (e) {
                showToast('删除失败: ' + e.message, 'error');
            } finally {
                loading.value = false;
            }
        }

        // ========== 批量重建 ==========
        const rebuildResult = ref(null);
        const showRebuildResult = ref(false);

        async function rebuildAll() {
            if (!confirm('全量重建将删除所有文档的旧向量并重新向量化，可能耗时较长，确认继续？')) return;
            loading.value = true;
            try {
                rebuildResult.value = await api.rebuildAll();
                showRebuildResult.value = true;
                await loadStats();
            } catch (e) {
                showToast('全量重建失败: ' + e.message, 'error');
            } finally {
                loading.value = false;
            }
        }

        async function rebuildFailed() {
            loading.value = true;
            try {
                rebuildResult.value = await api.rebuildByStatus('FAILED');
                showRebuildResult.value = true;
                await loadStats();
                showToast('失败文档重试完成');
            } catch (e) {
                showToast('重试失败: ' + e.message, 'error');
            } finally {
                loading.value = false;
            }
        }

        // ========== 索引版本管理 ==========
        const versions = ref([]);
        const activeVersion = ref(null);
        const versionActionResult = ref(null);
        const showVersionResult = ref(false);
        const rebuildVersionDesc = ref('');

        async function loadVersions() {
            try {
                versions.value = await api.listVersions();
                // 从版本列表中找 ACTIVE 版本（数据已是实时统计的）
                activeVersion.value = versions.value.find(v => v.status === 'ACTIVE') || null;
            } catch (e) {
                showToast('加载版本列表失败: ' + e.message, 'error');
            }
        }

        async function initFirstVersion() {
            if (!confirm('初始化首个版本将创建 v1 并激活，此后新向量将自动标记版本号。确认继续？')) return;
            loading.value = true;
            try {
                versionActionResult.value = await api.initFirstVersion('初始化首个版本');
                showVersionResult.value = true;
                await loadVersions();
                await loadStats();
            } catch (e) {
                showToast('初始化失败: ' + e.message, 'error');
            } finally {
                loading.value = false;
            }
        }

        async function rebuildWithNewVersion() {
            if (!confirm('灰度重建将创建新版本并全量重新向量化，完成后自动切换。此操作可能耗时较长，确认继续？')) return;
            loading.value = true;
            try {
                versionActionResult.value = await api.rebuildWithNewVersion(rebuildVersionDesc.value);
                showVersionResult.value = true;
                rebuildVersionDesc.value = '';
                await loadVersions();
                await loadStats();
            } catch (e) {
                showToast('灰度重建失败: ' + e.message, 'error');
            } finally {
                loading.value = false;
            }
        }

        async function activateVersion(id, label) {
            if (!confirm(`确认激活版本 ${label}？当前活跃版本将自动归档。`)) return;
            loading.value = true;
            try {
                versionActionResult.value = await api.activateVersion(id);
                showVersionResult.value = true;
                await loadVersions();
                await loadStats();
            } catch (e) {
                showToast('激活失败: ' + e.message, 'error');
            } finally {
                loading.value = false;
            }
        }

        async function rollbackToVersion(id, label) {
            if (!confirm(`确认回滚到版本 ${label}？当前活跃版本将自动归档，回滚是秒级操作。`)) return;
            loading.value = true;
            try {
                versionActionResult.value = await api.rollbackToVersion(id);
                showVersionResult.value = true;
                await loadVersions();
                await loadStats();
            } catch (e) {
                showToast('回滚失败: ' + e.message, 'error');
            } finally {
                loading.value = false;
            }
        }

        async function cleanupVersion(id, label) {
            if (!confirm(`确认清理版本 ${label} 的向量数据？此操作不可恢复，将永久删除该版本的所有向量。`)) return;
            loading.value = true;
            try {
                versionActionResult.value = await api.cleanupVersion(id);
                showVersionResult.value = true;
                await loadVersions();
                await loadStats();
            } catch (e) {
                showToast('清理失败: ' + e.message, 'error');
            } finally {
                loading.value = false;
            }
        }

        // ========== 向量检索 ==========
        const searchQuery = ref('');
        const searchTopK = ref(10);
        const searchThreshold = ref(0.5);
        const searchResults = ref([]);
        const searchDone = ref(false);
        const searchCached = ref(false);
        const searchDegradeLevel = ref('');
        const searchHybrid = ref(false);
        const searchRewrite = ref(false);
        const searchRerank = ref(false);
        const searchReranked = ref(false);
        const searchSourceFilter = ref('');
        const searchChannels = ref(null);
        const searchRewrittenQueries = ref([]);
        const searchCategory = ref('');
        const searchAuthor = ref('');
        const searchDateFrom = ref('');
        const searchDateTo = ref('');

        async function doSearch() {
            loading.value = true;
            searchDone.value = false;
            try {
                const metadata = {};
                if (searchCategory.value) metadata.category = searchCategory.value;
                if (searchAuthor.value) metadata.author = searchAuthor.value;
                if (searchDateFrom.value) metadata.dateFrom = searchDateFrom.value;
                if (searchDateTo.value) metadata.dateTo = searchDateTo.value;
                const res = await api.search(searchQuery.value, searchTopK.value, searchThreshold.value,
                    searchHybrid.value, searchSourceFilter.value, searchRewrite.value, searchRerank.value, metadata);
                searchResults.value = res.results || [];
                searchCached.value = res.cached || false;
                searchReranked.value = res.reranked || false;
                searchDegradeLevel.value = res.degradeLevel || 'NORMAL';
                searchChannels.value = res.channels || null;
                searchRewrittenQueries.value = res.rewrittenQueries || [];
                searchDone.value = true;
            } catch (e) {
                showToast('检索失败: ' + e.message, 'error');
            } finally {
                loading.value = false;
            }
        }

        // ========== RAG 问答 ==========
        const ragQuestion = ref('');
        const ragTopK = ref(10);
        const ragThreshold = ref(0.3);
        const ragResult = ref(null);
        const ragLoading = ref(false);
        const ragHybrid = ref(false);
        const ragRewrite = ref(false);
        const ragRerank = ref(false);
        const ragSourceFilter = ref('');
        const ragCategory = ref('');
        const ragAuthor = ref('');
        const ragDateFrom = ref('');
        const ragDateTo = ref('');
        const ragConversationId = ref('');
        const ragStreamMode = ref(false);
        const ragStreamAnswer = ref('');
        const ragStreaming = ref(false);
        let ragAbortController = null;

        async function askRAG() {
            ragLoading.value = true;
            try {
                const metadata = {};
                if (ragCategory.value) metadata.category = ragCategory.value;
                if (ragAuthor.value) metadata.author = ragAuthor.value;
                if (ragDateFrom.value) metadata.dateFrom = ragDateFrom.value;
                if (ragDateTo.value) metadata.dateTo = ragDateTo.value;
                ragResult.value = await api.ask(ragQuestion.value, ragTopK.value, ragThreshold.value,
                    ragHybrid.value, ragSourceFilter.value, ragRewrite.value, ragRerank.value,
                    ragConversationId.value, metadata);
            } catch (e) {
                showToast('问答失败: ' + e.message, 'error');
            } finally {
                ragLoading.value = false;
            }
        }

        async function askRagStream() {
            if (!ragQuestion.value.trim()) return;
            ragStreaming.value = true;
            ragStreamAnswer.value = '';
            ragAbortController = new AbortController();
            try {
                const metadata = {};
                if (ragCategory.value) metadata.category = ragCategory.value;
                if (ragAuthor.value) metadata.author = ragAuthor.value;
                if (ragDateFrom.value) metadata.dateFrom = ragDateFrom.value;
                if (ragDateTo.value) metadata.dateTo = ragDateTo.value;
                await api.askStream(
                    ragQuestion.value, ragTopK.value, ragThreshold.value,
                    ragHybrid.value, ragSourceFilter.value, ragRewrite.value, ragRerank.value,
                    ragConversationId.value, metadata,
                    (token) => { ragStreamAnswer.value += token; },
                    ragAbortController.signal
                );
            } catch (e) {
                if (e.name !== 'AbortError') {
                    showToast('流式问答失败: ' + e.message, 'error');
                }
            } finally {
                ragStreaming.value = false;
                ragAbortController = null;
            }
        }

        function stopRagStream() {
            if (ragAbortController) {
                ragAbortController.abort();
            }
        }

        async function clearRagConversation() {
            if (!ragConversationId.value) {
                showToast('请先输入会话ID');
                return;
            }
            try {
                await api.clearConversation(ragConversationId.value);
                showToast('会话记忆已清除');
            } catch (e) {
                showToast('清除失败: ' + e.message, 'error');
            }
        }

        // ========== 工具函数 ==========
        function formatSize(bytes) {
            if (!bytes) return '0 B';
            if (bytes < 1024) return bytes + ' B';
            if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
            return (bytes / 1048576).toFixed(1) + ' MB';
        }

        function formatTime(time) {
            if (!time) return '-';
            return time.replace('T', ' ').substring(0, 19);
        }

        // ========== 清除过滤条件 ==========
        function clearSearchFilters() {
            searchSourceFilter.value = '';
            searchCategory.value = '';
            searchAuthor.value = '';
            searchDateFrom.value = '';
            searchDateTo.value = '';
            showToast('已清除所有过滤条件');
        }

        function clearRagFilters() {
            ragSourceFilter.value = '';
            ragCategory.value = '';
            ragAuthor.value = '';
            ragDateFrom.value = '';
            ragDateTo.value = '';
            showToast('已清除所有过滤条件');
        }

        // ========== 文档状态轮询（异步处理进度监控） ==========
        let pollingTimer = null;

        /**
         * 启动轮询：当文档列表中有 PENDING/PROCESSING 状态的文档时，每 3 秒自动刷新
         * 所有文档处理完成后自动停止轮询
         */
        function startPolling() {
            stopPolling();
            pollingTimer = setInterval(async () => {
                const hasPending = documents.value.some(d =>
                    d.status === 'PENDING' || d.status === 'PROCESSING');
                if (hasPending) {
                    await loadDocuments();
                    await loadStats();
                } else {
                    stopPolling();
                    showToast('文档处理完成');
                }
            }, 3000);
        }

        function stopPolling() {
            if (pollingTimer) {
                clearInterval(pollingTimer);
                pollingTimer = null;
            }
        }

        function switchTab(key) {
            activeTab.value = key;
            if (key === 'dashboard') loadStats();
            if (key === 'documents') {
                loadDocuments();
                startPolling();  // 切换到文档管理时启动轮询
            } else {
                stopPolling();  // 切换到其他 tab 时停止轮询
            }
            if (key === 'chunks') loadAllDocuments();
            if (key === 'search') {
                loadAllDocuments();  // 搜索页需要文档列表做来源过滤
                loadCategories();
                loadAuthors();
            }
            if (key === 'rag') {
                loadAllDocuments();     // RAG 页需要文档列表做来源过滤
                loadCategories();
                loadAuthors();
            }
            if (key === 'versions') loadVersions();
        }

        // ========== 初始化 ==========
        onMounted(() => {
            loadStats();
        });

        onUnmounted(() => {
            stopPolling();
        });

        return {
            // 导航
            tabs, activeTab, switchTab,
            // 全局
            loading, toast, showToast,
            // 仪表盘
            stats, loadStats,
            // 文档
            documents, allDocuments, docPage, docSize, docTotal, docStatusFilter,
            uploadFile, uploadChunkSize, uploadOverlap, uploadCategory, uploadAuthor, uploadDocDate, dragover,
            docDetail, showDocDetail,
            resplitDoc, showResplit, resplitChunkSize, resplitOverlap,
            loadDocuments, loadAllDocuments, handleFileSelect, handleDrop,
            uploadDocument, viewDocument, deleteDocument, reindexDocument,
            showResplitModal, doResplit,
            categories, authors, loadCategories, loadAuthors,
            // 分块
            chunkDocId, chunks,
            editChunk, showEditChunk, editChunkContent,
            loadChunks, viewChunkDetail, showEditChunkModal, saveChunk, deleteChunk,
            // 批量重建
            rebuildResult, showRebuildResult, rebuildAll, rebuildFailed,
            // 索引版本管理
            versions, activeVersion, versionActionResult, showVersionResult, rebuildVersionDesc,
            loadVersions, initFirstVersion, rebuildWithNewVersion, activateVersion, rollbackToVersion, cleanupVersion,
            // 检索
            searchQuery, searchTopK, searchThreshold, searchResults, searchDone, searchCached, searchReranked, searchDegradeLevel, searchHybrid, searchRewrite, searchRerank, searchSourceFilter, searchChannels, searchRewrittenQueries,
            searchCategory, searchAuthor, searchDateFrom, searchDateTo,
            doSearch, clearSearchFilters,
            // RAG
            ragQuestion, ragTopK, ragThreshold, ragResult, ragLoading, ragHybrid, ragRewrite, ragRerank, ragSourceFilter,
            ragCategory, ragAuthor, ragDateFrom, ragDateTo,
            ragConversationId, ragStreamMode, ragStreamAnswer, ragStreaming,
            askRAG, askRagStream, stopRagStream, clearRagConversation, clearRagFilters,
            // 工具
            formatSize, formatTime,
        };
    }
}).mount('#app');
