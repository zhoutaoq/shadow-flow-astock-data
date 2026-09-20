package io.github.zhoutaoq.shadowflow.biz.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个「文件表」：{@code ./data} 下的单个 JSON 数组文件，启动时整表载入内存。
 *
 * <p>适用于行数可控、需要整体读写的实体（系统配置、股票列表）：
 * <ul>
 *   <li>启动时 {@link #load()} 一次，之后所有查询走内存；</li>
 *   <li>变更时改内存再 {@link #save(List)} 写穿到文件；</li>
 *   <li>保存前按 {@code StoreProperties.maxRows} 截断，避免文件无限膨胀。</li>
 * </ul>
 *
 * <p>行数可能突破上限且只增不减的数据（调用日志）不适用 —— 那类数据按设计直接不落盘。
 */
public final class FileTable<E> {

    private static final Logger log = LoggerFactory.getLogger(FileTable.class);

    private final DataDir dataDir;
    private final String relativePath;
    private final Class<E> elementType;
    private final int maxRows;

    public FileTable(DataDir dataDir, String relativePath, Class<E> elementType, int maxRows) {
        this.dataDir = dataDir;
        this.relativePath = relativePath;
        this.elementType = elementType;
        this.maxRows = maxRows > 0 ? maxRows : Integer.MAX_VALUE;
    }

    public Path file() {
        return dataDir.file(relativePath);
    }

    /** 整表载入；文件不存在返回空列表 */
    public List<E> load() {
        List<E> rows = JsonFileIo.readList(file(), elementType);
        return rows == null ? new ArrayList<>() : new ArrayList<>(rows);
    }

    /** 整表覆盖写盘；超过上限只保留最后 maxRows 行 */
    public boolean save(List<E> rows) {
        List<E> body = rows == null ? new ArrayList<>() : rows;
        if (body.size() > maxRows) {
            body = new ArrayList<>(body.subList(body.size() - maxRows, body.size()));
            log.warn("[store] {} 行数 {} 超过上限 {}，已只保留最近 {} 行",
                    relativePath, rows.size(), maxRows, maxRows);
        }
        return JsonFileIo.write(file(), body);
    }
}
