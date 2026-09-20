package io.github.zhoutaoq.shadowflow.biz.store;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 持久化相关的运行参数（{@code shadowflow.store.*}）。
 *
 * <p>与模式无关：数据库模式下这些参数只是不生效，无数据库模式下才真正用到。
 */
@Component
@ConfigurationProperties(prefix = "shadowflow.store")
public class StoreProperties {

    /**
     * 数据文件根目录。相对路径按进程工作目录解析。
     *
     * <p>无数据库模式下所有持久化数据都以 JSON 文件落在这里，启动时不存在的目录会自动创建。
     */
    private String dataDir = "./data";

    /**
     * 单个文件表允许保留的最大行数。
     *
     * <p>对应设计里的「数据行超过一万的数据不落库」：会无限增长的数据（调用日志等）
     * 不进入持久化，超过上限的文件表也只保留最近的部分。
     */
    private int maxRows = 10000;

    /** 回测历史最多保留多少条（超出淘汰最旧的）。回测是主流程，必须落盘但有上限。 */
    private int backtestKeep = 500;

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public int getBacktestKeep() {
        return backtestKeep;
    }

    public void setBacktestKeep(int backtestKeep) {
        this.backtestKeep = backtestKeep;
    }
}
