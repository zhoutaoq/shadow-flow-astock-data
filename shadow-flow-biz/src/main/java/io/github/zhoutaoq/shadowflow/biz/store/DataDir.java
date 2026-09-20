package io.github.zhoutaoq.shadowflow.biz.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 无数据库模式的数据文件根目录。
 *
 * <p>所有落盘数据都以 {@code waizao.store.data-dir}（默认 {@code ./data}）为根，
 * 子路径由各 Repository 自己命名。相对路径按进程工作目录解析，启动时确保目录存在。
 *
 * <p>目录建不出来时只告警不抛异常：数据落不了盘最多丢历史，不该把整个应用拖起不来。
 */
@Component
public class DataDir {

    private static final Logger log = LoggerFactory.getLogger(DataDir.class);

    private final StoreProperties props;
    private volatile Path root;

    public DataDir(StoreProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        root = resolve(props.getDataDir());
        log.info("[store] 数据文件根目录 {}（数据库模式下不会用到）", root.toAbsolutePath().normalize());
    }

    /** 根目录（绝对路径）；目录本身按需创建，数据库模式下不会平白多出一个空目录 */
    public Path root() {
        Path p = root;
        return p == null ? resolve(props.getDataDir()) : p;
    }

    /**
     * 根目录下的文件，例如 {@code file("sys-config.json")}；父目录不存在时自动创建。
     */
    public Path file(String relative) {
        Path p = root().resolve(relative);
        Path parent = p.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                log.warn("[store] 数据目录创建失败 {}：{}", parent, e.getMessage());
            }
        }
        return p;
    }

    /** 根目录下的子目录，例如 {@code dir("backtest")}；不存在时创建 */
    public Path dir(String relative) {
        Path p = root().resolve(relative);
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            log.warn("[store] 子目录创建失败 {}：{}", p, e.getMessage());
        }
        return p;
    }

    private static Path resolve(String configured) {
        String p = (configured == null || configured.trim().isEmpty()) ? "./data" : configured.trim();
        Path path = Paths.get(p);
        return path.isAbsolute() ? path : Paths.get(System.getProperty("user.dir", "."), p);
    }
}
