package io.github.zhoutaoq.shadowflow.biz.quant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 当日的监控标的池（需求 v2.0 第 6 条）。
 *
 * <p>口径是需求原话：「所有量化策略定义的标的合集 + 当前量化持有的标的合集」。
 * 后半句容易被忽略但很关键 —— 策略里把某只票删掉了，只要还没卖出，它仍然必须被监控，
 * 否则就成了「删掉即不管」，止损永远不会触发。
 *
 * <p><b>为什么要在开盘前固定</b>：盘中改标的会让同一只票在同一轮里
 * 一半策略看得到、一半看不到，且当天成交记录无法用一份统一的池子复核。
 * 所以这里只在两个时点刷新：进程启动、每日 0 点。其余时候只读。
 */
@Service
public class QuantUniverseService {

    private static final Logger log = LoggerFactory.getLogger(QuantUniverseService.class);

    private final QuantRepository repository;

    /** strategyId → 当日固定标的（升序去重） */
    private final Map<Long, List<String>> byStrategy = new ConcurrentHashMap<>();

    public QuantUniverseService(QuantRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        try {
            refresh();
        } catch (RuntimeException e) {
            log.warn("[quant] 启动初始化标的池失败（下一轮会重试）：{}", e.getMessage());
        }
    }

    /** 重算全部策略的当日池：策略标的 ∪ 当前持仓 */
    public synchronized void refresh() {
        byStrategy.clear();
        for (Long userId : repository.listStrategyUserIds()) {
            for (QuantStrategy s : repository.listStrategies(userId)) {
                byStrategy.put(s.getId(), buildFor(s));
            }
        }
        log.info("[quant] 当日标的池已刷新，{} 个策略，去重后 {} 只",
                byStrategy.size(), allCodes().size());
    }

    /** 只刷一个策略（新建 / 编辑 / 手动初始化时用） */
    public void refreshOne(QuantStrategy strategy) {
        if (strategy == null) {
            return;
        }
        byStrategy.put(strategy.getId(), buildFor(strategy));
    }

    private List<String> buildFor(QuantStrategy s) {
        Set<String> set = new LinkedHashSet<>();
        if (s.getWatchCodes() != null) {
            for (String raw : s.getWatchCodes().split("[,，;；\\s]+")) {
                String code = EastmoneyClient.normalize(raw);
                if (!code.isEmpty()) {
                    set.add(code);
                }
            }
        }
        for (QuantHolding h : repository.listHoldings(s.getId())) {
            String code = EastmoneyClient.normalize(h.getCode());
            if (!code.isEmpty()) {
                set.add(code);
            }
        }
        return new ArrayList<>(set);
    }

    /** 某个策略的当日标的；没算过就现算一次，避免新建策略后第一轮空跑 */
    public List<String> codesOf(long strategyId) {
        List<String> cached = byStrategy.get(strategyId);
        if (cached != null) {
            return cached;
        }
        QuantStrategy s = findStrategy(strategyId);
        if (s == null) {
            return new ArrayList<>();
        }
        List<String> built = buildFor(s);
        byStrategy.put(strategyId, built);
        return built;
    }

    /** 所有策略的并集：批量取数只请求这一份，避免同一只票被多个策略重复拉 */
    public List<String> allCodes() {
        Set<String> set = new LinkedHashSet<>();
        for (List<String> codes : byStrategy.values()) {
            set.addAll(codes);
        }
        return new ArrayList<>(set);
    }

    public void remove(long strategyId) {
        byStrategy.remove(strategyId);
    }

    private QuantStrategy findStrategy(long strategyId) {
        for (Long userId : repository.listStrategyUserIds()) {
            QuantStrategy s = repository.findStrategy(userId, strategyId);
            if (s != null) {
                return s;
            }
        }
        return null;
    }
}
