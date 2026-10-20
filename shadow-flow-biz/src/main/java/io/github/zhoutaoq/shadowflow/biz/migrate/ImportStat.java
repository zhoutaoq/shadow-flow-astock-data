package io.github.zhoutaoq.shadowflow.biz.migrate;

/**
 * 一个维度导入后的统计。
 *
 * <p>三个数各有含义，页面上要分开显示，不能笼统报「导入 N 行」：
 * <ul>
 *   <li>{@link #inserted} —— 目标环境原本没有、这次新写进去的；</li>
 *   <li>{@link #updated} —— 唯一键 / 主键撞上已有行，按「导入数据覆盖」改写的；</li>
 *   <li>{@link #unchanged} —— 撞上了但内容完全一致（MySQL 的 ON DUPLICATE KEY
 *       UPDATE 返回 0），等于这行什么都没动。</li>
 * </ul>
 * 覆盖条数是给人看「这次动了多少存量」的关键数字，缺了它就没法判断该不该回滚。
 */
public class ImportStat {

    /** 本维度在包里读到 / 写入的总行数 */
    private long rows;
    /** 新增行数 */
    private long inserted;
    /** 覆盖行数 */
    private long updated;
    /** 撞上但内容未变的行数 */
    private long unchanged;

    public static ImportStat of(long rows, long inserted, long updated, long unchanged) {
        ImportStat s = new ImportStat();
        s.rows = rows;
        s.inserted = inserted;
        s.updated = updated;
        s.unchanged = unchanged;
        return s;
    }

    public void add(ImportStat other) {
        if (other == null) {
            return;
        }
        rows += other.rows;
        inserted += other.inserted;
        updated += other.updated;
        unchanged += other.unchanged;
    }

    public long getRows() {
        return rows;
    }

    public long getInserted() {
        return inserted;
    }

    public long getUpdated() {
        return updated;
    }

    public long getUnchanged() {
        return unchanged;
    }
}
