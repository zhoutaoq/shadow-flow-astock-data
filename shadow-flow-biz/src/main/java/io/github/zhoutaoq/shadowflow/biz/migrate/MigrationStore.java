package io.github.zhoutaoq.shadowflow.biz.migrate;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;

import java.io.IOException;

/**
 * 数据迁移的存储适配层：一套接口，两种落点（MySQL / ./data 下的 JSON 文件）。
 *
 * <p>为什么不让迁移服务直接读文件或直接写 SQL：两种模式的<b>内存状态规则不同</b>。
 * 数据库模式的仓储每次现查，写完立刻生效；文件模式的仓储启动时把数据整份载进内存，
 * 背着他改文件只会得到「文件变了、页面还是老数据」，下一次业务写入还会把内存里的旧值
 * 覆盖回去。所以导入统一走这一层：文件实现写完文件后调 {@link #reloadAfterImport()}
 * 把仓储的内存状态重新载一遍。
 *
 * <p>行格式只有一种：<b>键名与数据库列名一致</b>的 JSON 对象（{@code stock_code}、
 * {@code create_time} …）。数据库实现按列名拼 SQL；文件实现负责在「列名」与
 * 文件里的 camelCase 字段之间做一次翻译。这样导出的包不依赖当前环境的存储方式，
 * 从文件模式搬到数据库模式（或反过来）也能用。
 */
public interface MigrationStore {

    /** 当前存储模式，写进 manifest 供人看 */
    String mode();

    /** 该维度在当前模式下是否有可搬的数据（文件模式没有 K 线存量） */
    boolean available(MigrateDimension dim);

    /** 该维度当前的行数（用于页面展示与导出清单） */
    long count(MigrateDimension dim);

    /**
     * 把该维度全量写成一个 JSON 数组。
     *
     * <p>由调用方负责 {@code writeStartArray / writeEndArray}：实现只管逐个写对象，
     * 这样才能流式导出大表（日K可能上百万行），不必先在内存里攒一个巨型 List。
     */
    void exportTo(MigrateDimension dim, JsonGenerator gen) throws IOException;

    /**
     * 从一个 JSON 数组流里读入并落库 / 落文件。
     *
     * <p>语义固定为<b>增量 + 覆盖</b>：目标环境没有的行新增，撞上主键 / 唯一键的行
     * 用包里的数据覆盖。不做删除 —— 包里没有的行在目标环境原样保留。
     */
    ImportStat importFrom(MigrateDimension dim, JsonParser parser) throws IOException;

    /**
     * 导入结束后让内存状态与磁盘对齐（只有文件模式需要实现，数据库模式是空实现）。
     */
    void reloadAfterImport();
}
