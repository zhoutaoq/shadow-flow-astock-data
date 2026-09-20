package io.github.zhoutaoq.shadowflow.biz.store;

/**
 * 持久化策略。
 *
 * <p>v1.3 重构后，每个业务实体只有一个 Repository 接口，按运行模式装配不同策略：
 * <ul>
 *   <li>{@link #DB} —— 数据库模式（{@code waizao.db.enabled=true}）：落 MySQL；</li>
 *   <li>{@link #FILE} —— 无数据库模式：以 {@code ./data} 为根目录落 JSON 文件，
 *       启动时全量载入内存，运行期读写内存 + 写穿到文件。</li>
 * </ul>
 *
 * <p>两种策略共用同一套模型与同一套业务代码，业务侧不需要判断当前是什么模式。
 */
public enum StorageMode {

    DB("db", "数据库（MySQL）"),
    FILE("file", "数据文件（./data）");

    private final String code;
    private final String label;

    StorageMode(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }
}
