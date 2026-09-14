# feat3：盯盘 DingTalk 生命周期推送与关键时点播报

> 范围：仅改动 `com.stock.kline.watch` 包，与系统其它模块零耦合，延续盯盘模块自包含约定。
> 装配受 `watch.enabled` 控制（`@ConditionalOnProperty`，同 PriceWatchTask/WatchScheduler）。
> 未做运行验证（按项目「只编码、不主动验证」约定，静态自查通过）。

## 需求 → 实现映射

| 需求 | 实现类 | 触发点 |
|---|---|---|
| 启动后推送「系统IP + 盯盘股票 + 告警规则」 | `WatchNotifier` | `ApplicationReadyEvent`（应用就绪、配置已载入） |
| 每次盯盘股票/规则变更实时推送差异 | `WatchNotifier` | `WatchConfigStore.addListener`，与启动基线比对，仅股票或规则真变才推 |
| 关键时点对全部盯盘股涨跌幅合并推送 | `WatchKeyTimePusher` | 工作日 09:15:15 / 09:20:15 / 09:25:05 / 09:30:15 / 11:30:15 / 13:00:15 / 15:00:15 |

公共：`WatchText`（文本/涨跌幅格式化 + 本机局域网 IPv4 探测 + 出口公网 IPv4 探测）。

## 设计要点

### 1) WatchNotifier
- 构造注入 `WatchProperties / WatchConfigStore / DingTalkNotifier / Environment`。
- `@PostConstruct`：先取 `store.snapshot()` 作为基线（此时 store 启动 reload 已完成且早已
  `notifyListeners`，本类注册前那次不算变更），再 `store.addListener(this::onStoreChange)`。
- `onApplicationEvent(ApplicationReadyEvent)`：webhook 已配置才拼启动消息并**异步**发送。
- `onStoreChange(WatchSettings)`：把新快照的 (code→name) 与规则签名跟基线比对：
  - 新增/移除的盯盘股 → 列出；
  - 规则签名（保序 `intervals@percent`）变化 → 提示"告警规则已更新"；
  - 纯改 webhook / 开关 / 轮询间隔不属于"股票或规则变更"，不推送。
- 发送丢到独立单线程守护池（`watch-notify-*`），避免网络 POST（最长 ~10s 读超时）阻塞
  保存配置的 HTTP 请求；`@PreDestroy` 关池。

### 2) WatchKeyTimePusher
- 实现 `Runnable`，独立单线程守护调度器（`watch-clock-*`）。
- `@PostConstruct` 计算「距下一个工作日关键时点」毫秒并 `schedule(this)`；
  每次 `run()` 结束后在 `finally` 里续排下一次 → 跨时点不叠加、失败不断链、任意时刻启动都能续上。
- `shouldPush()`：webhook 已配置 && 盯盘已开启 && 名单非空。
- 触发时复用 `watchExecutor` 并发查现价（每只 `get(10s)`），取现价>0 的行，涨跌幅按今收昨收
  算（复用 `Quote.changePercent()`），逐只一行，末尾统计 上涨/下跌/平盘，整体一条发送。
- 无任何有效行情（全部未开盘/停牌）→ 只记日志不发送空消息。

### 3) 消息均走 DingTalkNotifier.send()
send() 自动加 `[智能盯盘-公网IP]` 前缀（含关键词"智能盯盘"，命中机器人"自定义关键词"；
又挂上出口公网 IP，多机共用一个群时能区分来源），webhook 未配置时 send 返回 false 不发送。
公网 IP 由 `WatchText.publicIp()` 探（外部回显服务）、进程内缓存；探不到退回 `[智能盯盘]`。

## 关键规避
- 不用 `@Scheduled(cron)`，改手动单线程链式调度，避免固定时点与轮询调度互相纠缠、便于 `watch.enabled` 整体裁剪。
- 变更推送按"与启动基线比对"，不依赖 store 是否把 reload/迁移也走 notifyListeners，避免启动即推一遍"变更"。
