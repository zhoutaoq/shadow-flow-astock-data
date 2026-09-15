package com.stock.kline.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.stock.kline.store.DataDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 无数据库模式：账户与持仓落 {@code ./data/account/account-{userId}.json}。
 *
 * <p>每个用户一个文件、单次整文件读写 —— 账户与持仓是一对多的强关联，
 * 放同一个文件里可以整份读整份写，不必再像 SysConfig 那样维护一份内存索引。
 * 运行期读写内存 + 写穿到文件，与其它 File 仓储保持同一套行为：
 * 保存后立即生效、重启后从文件恢复。
 */
@Repository
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "false", matchIfMissing = true)
public class FileAccountRepository implements AccountRepository {

    private static final Logger log = LoggerFactory.getLogger(FileAccountRepository.class);

    private final DataDir dataDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectWriter writer = new ObjectMapper().writerWithDefaultPrettyPrinter();

    /** userId → 该用户的账户文件内容（账户 + 持仓），含内存里的最新值 */
    private final ConcurrentMap<Long, AccountFile> byUser = new ConcurrentHashMap<>();
    /** 持仓自增 id 游标 */
    private final AtomicLong seq = new AtomicLong(0);

    public FileAccountRepository(DataDir dataDir) {
        this.dataDir = dataDir;
    }

    @PostConstruct
    public void init() {
        log.info("[account] 无数据库模式：账户与持仓落 {}", dataDir.file("account").toAbsolutePath());
    }

    @Override
    public Account findAccount(long userId) {
        Account a = load(userId).account;
        return a == null ? null : copyAccount(a);
    }

    @Override
    public synchronized void saveAccount(Account account) {
        if (account == null) {
            throw new IllegalArgumentException("账户内容不能为空");
        }
        AccountFile f = load(account.getUserId());
        f.account = copyAccount(account);
        persist(account.getUserId(), f);
        log.info("[account] 用户 {} 账户已保存：期初市值 {}，可用金额 {}",
                account.getUserId(), account.getInitValue(), account.getCash());
    }

    @Override
    public List<Position> listPositions(long userId) {
        List<Position> out = new ArrayList<>();
        for (Position p : load(userId).positions.values()) {
            out.add(copyPosition(p));
        }
        out.sort(Comparator.comparing(Position::getCode, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    @Override
    public synchronized Position savePosition(Position position) {
        if (position == null) {
            throw new IllegalArgumentException("持仓内容不能为空");
        }
        AccountFile f = load(position.getUserId());
        if (position.getId() == null) {
            position.setId(seq.incrementAndGet());
        } else {
            // 前端可能沿用上一份快照里的 id 提交，这里让游标跨过它，避免后续新增撞 id
            long id = position.getId();
            seq.updateAndGet(cur -> Math.max(cur, id));
        }
        f.positions.put(position.getId(), copyPosition(position));
        persist(position.getUserId(), f);
        return copyPosition(position);
    }

    @Override
    public synchronized void deletePosition(long userId, long id) {
        AccountFile f = load(userId);
        if (f.positions.remove(id) == null) {
            return;
        }
        persist(userId, f);
    }

    @Override
    public synchronized void deleteAllPositions(long userId) {
        AccountFile f = load(userId);
        if (f.positions.isEmpty()) {
            return;
        }
        f.positions = new LinkedHashMap<>();
        persist(userId, f);
    }

    /* ---------------- 内部实现 ---------------- */

    /** 载入（必要时从文件读一次）某用户的账户文件；文件不存在给一份空结构 */
    private AccountFile load(long userId) {
        AccountFile f = byUser.get(userId);
        if (f != null) {
            return f;
        }
        AccountFile fresh = readFile(userId);
        AccountFile prev = byUser.putIfAbsent(userId, fresh);
        return prev == null ? fresh : prev;
    }

    private AccountFile readFile(long userId) {
        AccountFile f = new AccountFile();
        Path p = fileOf(userId);
        if (!Files.isRegularFile(p)) {
            return f;
        }
        try {
            AccountFile loaded = mapper.readValue(
                    new String(Files.readAllBytes(p), StandardCharsets.UTF_8), AccountFile.class);
            if (loaded == null) {
                return f;
            }
            if (loaded.account != null) {
                f.account = loaded.account;
            }
            if (loaded.positions != null) {
                f.positions = loaded.positions;
            }
            // 游标要跨过文件里已有的 id，否则新增持仓会撞上旧 id
            for (Long id : f.positions.keySet()) {
                if (id != null) {
                    seq.updateAndGet(cur -> Math.max(cur, id));
                }
            }
            log.info("[account] 用户 {} 的账户已载入 {}（{} 条持仓）", userId, p, f.positions.size());
        } catch (Exception e) {
            log.warn("[account] 账户文件解析失败 {}：{}，按空账户继续", p, e.getMessage());
        }
        return f;
    }

    private void persist(long userId, AccountFile f) {
        Path p = fileOf(userId);
        try {
            Path parent = p.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                Files.createDirectories(parent);
            }
            Files.write(p, writer.writeValueAsString(f).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 落盘失败不影响内存里已经生效的值，与其它 File 仓储同口径
            log.warn("[account] 账户落盘失败 {}：{}（内存里的值仍然生效）", p, e.getMessage());
        }
    }

    private Path fileOf(long userId) {
        return dataDir.file("account/account-" + userId + ".json");
    }

    private static Account copyAccount(Account src) {
        Account out = new Account();
        out.setUserId(src.getUserId());
        out.setInitValue(src.getInitValue());
        out.setCash(src.getCash());
        out.setRemark(src.getRemark());
        out.setCreateTime(src.getCreateTime());
        out.setUpdateTime(src.getUpdateTime());
        return out;
    }

    private static Position copyPosition(Position src) {
        Position out = new Position();
        out.setId(src.getId());
        out.setUserId(src.getUserId());
        out.setCode(src.getCode());
        out.setName(src.getName());
        out.setCostPrice(src.getCostPrice());
        out.setQuantity(src.getQuantity());
        out.setCreateTime(src.getCreateTime());
        out.setUpdateTime(src.getUpdateTime());
        return out;
    }

    /** 一个用户的账户文件内容：账户 + 持仓，一次读写 */
    public static class AccountFile {
        public Account account;
        public Map<Long, Position> positions = new LinkedHashMap<>();
    }
}
