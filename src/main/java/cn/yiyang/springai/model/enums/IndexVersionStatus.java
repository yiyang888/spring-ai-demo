package cn.yiyang.springai.model.enums;

/**
 * 向量索引版本状态
 *
 * 状态流转：BUILDING → ACTIVE → ARCHIVED
 *               ↑                    |
 *               └────── 回滚 ────────┘
 */
public enum IndexVersionStatus {

    /** 构建中：新版本正在向量化，尚未切换 */
    BUILDING,

    /** 活跃中：当前检索和 RAG 问答使用的版本（同一时间只能有一个） */
    ACTIVE,

    /** 已归档：被新版本替换的旧版本，向量数据仍在 kb_vector 中，可用于回滚 */
    ARCHIVED
}
