## v1.3 持久化重构：落地说明

对应需求 [releasev1.3.md](./releasev1.3.md)。本文档说明最终落地结构，改代码前先看这里。

---

### 1. 目标与结论

| 需求条目 | 落地方式 |
| --- | --- |
| 1-1 模型统一、逻辑统一，只是持久化策略不同 | 每个实体一个 Repository 接口，业务层只依赖接口；两种策略实现由 `waizao.db.enabled` 条件装配 |
| 1-2 数据库模式落 MySQL | `Db*Repository`，沿用原表名与 SQL |
| 1-3 无数据库模式以 `./data` 为根，文件落盘 + 启动载入内存 | `store` 包 + `File*Repository`；启动时全量载入，运行期读写内存、写穿到文件 |
| 1-4 K线、超万行数据不落库 | K线无数据库模式直查供应商接口、不落盘；日志类数据在无数据库模式直接丢弃 |
| 2 开发环境关闭钉钉推送，yml 可开关 | `watch.dingtalk.enabled`（默认 false）总闸，`DingTalkNotifier.post` 统一拦截 |

### 2. 包结构

```
com.stock.kline.store        持久化基础设施（与模式无关）
  ├─ StorageMode             枚举：DB / FILE
  ├─ StoreProperties         waizao.store.* 配置
  ├─ DataDir                 ./data 根目录解析，父目录按需创建
  ├─ JsonFileIo              原子读写（tmp + move），解析失败按空数据处理
  └─ FileTable<E>            单文件整表：启动 load、变更 save、超 max-rows 截断

com.stock.kline.repo         各实体仓储：接口 + 双策略
  ├─ SysConfigRepository     DbSysConfigRepository / FileSysConfigRepository
  ├─ StockListRepository     DbStockListRepository / FileStockListRepository
  ├─ ApiCallLogRepository    DbApiCallLogRepository / DiscardApiCallLogRepository
  ├─ BacktestRepository      DbBacktestRepository / FileBacktestRepository
  └─ KlineRepository         DbKlineRepository / LiveKlineRepository
```

### 3. 实体 → 策略 → 落点

| 实体 | 数据库模式 | 无数据库模式 | 落盘文件 |
| --- | --- | --- | --- |
| 系统配置 | `waizao_sys_config` | 内存 + 文件 | `data/sys-config.json` |
| 股票列表 | `waizao_stock_list` | 内存 + 文件（首次用 `resources/stock-list.json` 播种） | `data/stock-list.json` |
| 回测 | `waizao_backtest` + `_daily` + `_trade` | 内存 + 分文件 | `data/backtest/bt-{id}.json` |
| 调用日志 | `waizao_api_call_log` | **丢弃**（只打应用日志） | — |
| 日K线 | `waizao_stock_kline_daily`，缺日先同步再读 | **直查供应商接口，不落盘** | — |
| 盯盘配置 | 文件（与数据库无关） | 同 | `data/watch-config.json` |

### 4. 为什么不落盘的两类数据

- **K线**：无数据库模式下现取现用。回看历史回测时按区间再取一次（与数据库模式「回测回看时反查日K表」是同一个动作，只是数据源不同）。
- **调用日志**：只增不减，几万行是常态，且不参与主流程（K线回测、盯盘告警都不读它）。请求次数在 `app.log` 里可事后统计。

主流程（K线回测、盯盘告警）所需的配置、股票名录、回测结果、盯盘名单，两种模式都落盘。

### 5. 已删除的旧实现

| 删除 | 替代 |
| --- | --- |
| `support/BacktestStore` + `DbBacktestStore` + `NoopBacktestStore` | `repo/BacktestRepository` + Db/File |
| `support/ApiLogSink` + `DbApiLogSink` + `NoopApiLogSink` | `repo/ApiCallLogRepository` + Db/Discard |
| `support/KlineSource` + `DbKlineSource` + `LiveKlineSource` | `repo/KlineRepository` + Db/Live |
| `support/DbStockDirectory` + `JsonStockDirectory` | `support/RepositoryStockDirectory`（一份代码读仓储） |
| `service/SysConfigService` | `repo/DbSysConfigRepository` |
| `service/StockListService` | `repo/DbStockListRepository` |
| `service/ApiCallLogService` | `repo/DbApiCallLogRepository` |

`service/StockKlineService` 保留，但只做「库里已有K线的后台管理」（数据库模式专属）；
抓取落库统一走 `KlineRepository#save`。

### 6. 配置项

```yaml
waizao:
  db:
    enabled: false        # 决定装配哪种策略
  store:
    data-dir: ./data      # 数据文件根目录
    max-rows: 10000       # 单文件表行数上限
    backtest-keep: 500    # 历史回测保留条数

watch:
  dingtalk:
    enabled: false        # 钉钉推送总开关，开发默认关
    webhook: "..."
```

`/api/mode` 新增 `storageMode`（`db` / `file`）、`storageLabel`、`dataDir`、
`backtestPersistent`、`callLogPersistent`，前端据此裁剪菜单。

### 7. 兼容与迁移

- 旧的 `./watch-config.json` 与 yml `watch.config-file` 仍会被读取，读到后在首次保存时自动迁到 `data/watch-config.json`。
- 无数据库模式下「股票列表」「系统配置」页此前是空壳（接口按库模式装配、404），现在真正可用。
- 无数据库模式首次启动会用 `resources/stock-list.json` 播种 `data/stock-list.json`，之后以落盘文件为准。

### 8. 验证情况

- 全量源码 javac 编译通过（本机 maven 启动器在 Git Bash 下不可用，改用 JDK 1.8 `javac` 直接编译 `src/main/java`）。
- 未在真机启动验证：运行期行为（文件播种、回测落盘、钉钉拦截）需本地 `mvn spring-boot:run` 实测。
