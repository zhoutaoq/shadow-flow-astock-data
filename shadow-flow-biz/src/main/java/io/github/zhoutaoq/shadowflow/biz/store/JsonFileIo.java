package io.github.zhoutaoq.shadowflow.biz.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;

/**
 * 数据文件的读写工具。
 *
 * <p>写盘一律走「先写临时文件、再原子替换」：进程在写到一半被 kill 时，
 * 目标文件要么还是旧内容、要么已是新内容，不会留下半截 JSON 导致下次启动解析失败。
 *
 * <p>读失败（文件不存在 / 内容损坏）统一返回空集合并记日志，不把异常抛给启动流程 ——
 * 历史数据丢了可以重建，应用起不来才是事故。
 */
public final class JsonFileIo {

    private static final Logger log = LoggerFactory.getLogger(JsonFileIo.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final ObjectWriter WRITER = MAPPER.writerWithDefaultPrettyPrinter();

    private JsonFileIo() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** 读一个 JSON 数组文件；文件不存在或损坏时返回空列表 */
    public static <T> List<T> readList(Path file, Class<T> elementType) {
        if (file == null || !Files.isRegularFile(file)) {
            return Collections.emptyList();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return Collections.emptyList();
            }
            return MAPPER.readValue(new String(bytes, StandardCharsets.UTF_8),
                    MAPPER.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (Exception e) {
            log.warn("[store] 数据文件解析失败 {}：{}（按空数据处理）", file, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 读一个 JSON 对象文件；文件不存在或损坏时返回 null */
    public static <T> T read(Path file, Class<T> type) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return null;
            }
            return MAPPER.readValue(new String(bytes, StandardCharsets.UTF_8), type);
        } catch (Exception e) {
            log.warn("[store] 数据文件解析失败 {}：{}（按空数据处理）", file, e.getMessage());
            return null;
        }
    }

    /** 按 TypeReference 读（泛型嵌套结构用） */
    public static <T> T read(Path file, TypeReference<T> ref) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return null;
            }
            return MAPPER.readValue(new String(bytes, StandardCharsets.UTF_8), ref);
        } catch (Exception e) {
            log.warn("[store] 数据文件解析失败 {}：{}（按空数据处理）", file, e.getMessage());
            return null;
        }
    }

    /**
     * 原子写盘。
     *
     * @return 是否写成功；失败只记日志，调用方已经改过的内存状态仍然有效
     */
    public static boolean write(Path file, Object value) {
        if (file == null) {
            return false;
        }
        try {
            Path parent = file.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                Files.createDirectories(parent);
            }
            byte[] body = WRITER.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, body);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Windows / 跨文件系统不支持原子移动，退化成 replace（窗口期极小）
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            log.warn("[store] 数据文件落盘失败 {}：{}（内存中的数据仍然生效）", file, e.getMessage());
            return false;
        }
    }

    /** 删除文件；不存在或删除失败都视为成功（数据已经从内存里移除了） */
    public static boolean delete(Path file) {
        if (file == null || !Files.exists(file)) {
            return true;
        }
        try {
            Files.delete(file);
            return true;
        } catch (IOException e) {
            log.warn("[store] 数据文件删除失败 {}：{}", file, e.getMessage());
            return false;
        }
    }
}
