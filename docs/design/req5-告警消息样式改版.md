# 需求文档：盯盘告警消息样式改版（涨跌配色 + 卡片渲染 + 行情平台跳转）

> 目标实例：`http://localhost/`（80 端口，当前版本 **1.6.2**）。
> 本文档为**设计稿**，未动任何代码；实施版本号建议 **v1.6.3**（纯后端改动，升 pom 版本即可，前端静态戳不动）。

---

## 1. 背景：现在的告警长什么样

盯盘侧三类告警全部是**纯文本单行长句**，逗号串联、无颜色、无链接、无结构，在钉钉群里就是一坨：

| 告警类型 | 拼文案位置 | 现状示例 |
|---|---|---|
| 波动告警 | `PriceWatchTask.buildAlert`（PriceWatchTask.java:424） | `贵州茅台 短线快速拉升，过去 30s 内累计涨幅 2.10%，现价 1520.00 元，当前涨跌幅 2.35%，拉升成交量：1234.56 万手，拉升成交额：2.10 亿元。` |
| 涨跌停封板 / 开板 | `sendLimitAlert`（PriceWatchTask.java:632） | `贵州茅台 涨停，现价 1520.00 元，涨跌幅为 10.01%，本窗口成交量 1234 手，本窗口成交额 200.00 万元，总成交额 45.00 亿元。` |
| 涨跌停放量 | `sendSurgeAlert`（PriceWatchTask.java:661） | `贵州茅台 涨停放量，现价 1520.00 元，涨跌幅 10.01%，本区间成交额 2000.00 万元（上区间 100.00 万元，放大 20.0 倍），本区间成交量 1234 手。` |

消息经 `NotifyService.send(target, prefix, content)`（NotifyService.java:126）→ `NotifyMessage.render()` 前缀拼「`[关键词-主机] 时刻`」（NotifyMessage.java:83）→ `DingTalkWebhookNotifier` 走 **text** msgtype（DingTalkWebhookNotifier.java:87）。

**两个关键事实（决定了本需求的实现路径）：**

1. **Markdown 通道已经存在但没人用**：`NotifyMessage` 有 `.markdown()` builder（NotifyMessage.java:120），`DingTalkWebhookNotifier` 已实现 markdown msgtype（DingTalkWebhookNotifier.java:91，正文首行即 title）。样式改版**不需要动通知协议层**（除非选 actionCard 方案，见 R2）。
2. **股票跳转体系已在网页端落地，但推送侧没有复用**：URL 模板在 `application.yml` 的 `app.stock-link`（application.yml:88-108）—— 百度股市通 `https://finance.baidu.com/stock/ab-{code}?name={name}`、雪球 `https://xueqiu.com/S/{market}{code}`；配置读写走 `StockLinkService`（StockLinkService.java:52 `current()`）；`{market}` 由代码前缀推导（6/9→SH，4/8→BJ，其余 SZ，common.js:646 `stockMarketOf`）。**后端拼告警时完全没碰这套配置**，而且雪球的 market 推导目前只有 JS 一份实现，后端要拼链接必须补一份同口径 Java 实现。

---

## 2. 需求明细

### R1 涨跌颜色标记（涨红跌绿，国内约定）

钉钉 markdown **不支持彩色文字**（只支持标题/加粗/斜体/链接/引用/列表/图片），颜色用 emoji 方案表达：

| 场景 | 涨（红） | 跌（绿） | 判定依据 |
|---|---|---|---|
| 波动告警 | 🔺 | 🔻 | `Hit.delta` 的符号（buildAlert 里 `up = maxDelta.signum() >= 0`，PriceWatchTask.java:431） |
| 涨停 / 打开涨停 | 🔴 / ⭕ | 🟢 / ⭕ | `limitState`（LIMIT_UP=1 / LIMIT_DOWN=-1，PriceWatchTask.java:52-54） |
| 放量告警 | 🔴 | 🟢 | 跟随封板方向的 `state` 参数 |

具体规则：

1. **消息标题行**首放方向 emoji：`🔺 贵州茅台 短线快速拉升` / `🔻 贵州茅台 短线快速下挫`；
2. **数字着色**：正文里「累计涨跌幅」「当前涨跌幅」两处百分比紧跟涨跌 emoji（如 `累计涨幅 🔺2.10%`、`当前涨跌幅 🔻1.20%`）；涨跌停消息的「涨跌幅」同理；
3. **措辞分档不变**（<1.5% 上涨/下跌，1.5~3% 快速拉升/下挫，≥3% 急速拉升/下挫，PriceWatchTask.java:463 `phraseFor`），只加颜色不改措辞；
4. 数值缺失时（`changePercent()` 为 null，展示 `—`）**不标色**，避免「—» 配红绿造成误读。

### R2 单条消息卡片渲染

两个实现方案，**推荐方案 A**：

| | 方案 A：Markdown 排版卡片（推荐） | 方案 B：actionCard 真卡片 |
|---|---|---|
| 协议改动 | 无（markdown msgtype 已实现） | 需给 `DingTalkWebhookNotifier` 新增 `actionCardBody`（`msgtype:actionCard`），`NotifyMessage` 增加按钮/跳转字段 |
| 钉钉端效果 | 标题 + 列表 + 引用的结构化排版，「卡片感」 | 带按钮、可整卡跳转的真卡片 |
| 关键词前缀 | 正常保留在正文首行 | 必须进 `text` 字段正文，否则被自定义关键词机器人拒收（DingTalkWebhookNotifier.java:38 已注明该坑） |
| 风险 | 低，改拼文案即可 | 中，协议层新字段 + 机器人兼容性待验 |

**方案 A 消息模板（三种告警的 before → after）：**

波动告警：
```markdown
[影子-主机] 14:32:05
### 🔺 贵州茅台 短线快速拉升

- 过去 30s：累计涨幅 🔺2.10%
- 现价：1520.00 元（当前涨跌幅 🔺2.35%）
- 拉升成交量：1234.56 万手 / 拉升成交额：2.10 亿元

[查看行情](https://finance.baidu.com/stock/ab-600519?name=%E8%B4%B5%E5%B7%9E%E8%8C%85%E5%8F%B0) · [雪球](https://xueqiu.com/S/SH600519)
```

涨停封板（跌停对称换 🟢）：
```markdown
[影子-主机] 14:32:05
### 🔴 贵州茅台 涨停

- 现价：1520.00 元（涨跌幅 🔴10.01%）
- 本窗口：1234 手 / 200.00 万元
- 总成交额：45.00 亿元

[查看行情](…) · [雪球](…)
```

涨停放量：
```markdown
[影子-主机] 14:32:05
### 🔴 贵州茅台 涨停放量

- 本区间成交额：2000.00 万元（上区间 100.00 万元，放大 20.0 倍）
- 本区间成交量：1234 手
- 现价：1520.00 元（涨跌幅 🔴10.01%）

[查看行情](…) · [雪球](…)
```

排版规范：
- 标题行 = `### ` + 方向 emoji + 名称 + 动作短语，**首行同时承担 markdown title**（DingTalkWebhookNotifier.java:93 取首行当 title）；
- 关键数字一行一条，用无序列表；次要信息合并，单条卡片 ≤ 6 行正文 + 1 行链接；
- 链接行固定在末尾，用 `·` 分隔；
- **`PushEnvelope` 关键词前缀（`[关键词-主机] 时刻`）必须保留在正文第一行**，不得挪进 title 之外的位置——这是钉钉自定义关键词机器人的安全校验，丢了直接被服务端拒收。

### R3 链接跳转到系统配置的百度股市通 / 雪球

1. **后端复用现有配置**：`PriceWatchTask` 注入 `StockLinkService`，发送前调 `current()`（StockLinkService.java:52）拿启用的 external 目标拼 URL；占位符替换口径与前端一致：`{code}` 6 位代码、`{name}` 就地 URL 编码、`{market}` 按代码前缀推导。
2. **新增同口径 Java 实现**：把 `stockMarketOf`（common.js:646）的规则（6/9→SH，4/8→BJ，其余 SZ）落到后端（建议放 `StockLinkService` 或新建 `StockLinks` 工具类），**并在注释里互相标注「与 JS 版共改」**，防止两边口径漂移。指数代码的固有歧义（000001 双市同码）与前端同口径：不猜，按深市算。
3. **链接取什么**：
   - 末尾链接行列出**当前启用的所有 external 平台**（出厂配置即百度股市通 + 雪球）；超管在「系统配置」页停用某个平台后，告警里的对应链接自动消失——跳转目标始终跟随系统配置，不写死；
   - 内部目标（`chart` 本系统 K 线）是相对路径，钉钉里点了打不开，**不进链接行**；
   - 标题行股票名称本身是否带链接：不做（钉钉 markdown 标题内嵌链接的显示效果差且兼容性不稳），链接统一收在末行。
4. **降级口径（必须逐条实现）**：
   - `stock-link` 总开关关闭 / 所有 external 目标都被停用 / URL 模板为空 → 该消息退回无链接行版本，其余排版不变；
   - 拼链接触发任何异常 → 捕获后按无链接降级，**绝不让链接问题阻塞告警本身**（与「通知失败不抛异常」的既有口径一致，NotifyService.java:68）。

---

## 3. 覆盖范围与不改项

| 消息 | 是否改 | 说明 |
|---|---|---|
| 波动告警 / 封板开板 / 放量 | ✅ 全改 | 本需求主体 |
| 配置变更通知（WatchNotifier.onStoreChange，WatchNotifier.java:170） | ✅ 轻改 | 加/移除行的「名称(代码)」带链接，沿用同一套拼链逻辑；规则摘要行不动 |
| 启动通知（WatchNotifier.buildStartup，WatchNotifier.java:185） | ❌ 不改 | 运维向平台消息，纯文本够用，与「盯盘侧 bypass=false」的边界无关 |
| 注册 / 反馈等其它通知 | ❌ 不改 | 不属于盯盘告警 |
| 通知 SDK 闸门（总开关、允许时段） | ❌ 不动 | 拼文案与发送闸门两层分离（WatchNotifier.java:38 已注明），本需求只碰前者 |

---

## 4. 非功能约束

1. **用户可见文案零开发词**：不得出现 webhook、配置文件名、接口、轮询、落盘等实现术语（全局红线）；
2. 首轮不推送、防刷屏（`volumeSurge` / `limitPrimed`）、采样与规则判断逻辑**全部不动**，只改三处拼文案方法 + 链接注入；
3. 发送失败口径不变：`log.warn` 不重试、不影响主流程；
4. markdown 正文经 `NotifyText.json` 转义进 JSON，换行/引号需验证不破坏 payload；
5. pom 版本升至 1.6.3；`?v=` 静态戳不动（无前端文件改动）。

## 5. 涉及文件清单

| 文件 | 改动 |
|---|---|
| `shadow-flow-biz/.../watch/PriceWatchTask.java` | `buildAlert`(:424) / `sendLimitAlert`(:632) / `sendSurgeAlert`(:661) 重写为 markdown 卡片 + emoji + 链接；注入 `StockLinkService` |
| `shadow-flow-biz/.../watch/WatchNotifier.java` | `onStoreChange`(:170) 差异行加链接 |
| `shadow-flow-biz/.../service/StockLinkService.java`（或新工具类） | 补 `{market}` 推导 + URL 拼接的 Java 实现 |
| `shadow-flow-notify/...` | **方案 A 不动**；选方案 B 才改 `DingTalkWebhookNotifier` / `NotifyMessage` |
| `docs/design/` | 新增本需求文档；实施后补 releasev1.6.3.md |

## 6. 验收标准（本地）

1. **假钉钉抓包**（`.workbuddy/ui-verify/` 的 `fake_dingtalk.py` + `--watch.dingtalk.webhook=http://127.0.0.1:18099/robot/send` + 无 `watch-config.json` 的目录启动 + `unset DINGTALK_WEBHOOK` 安全闸，见 MEMORY 约定）抓 payload 断言：
   - `msgtype=markdown`，`text` 首行含关键词前缀；
   - 三类消息各一例：涨方向含 🔺/🔴、跌方向含 🟢/🔻、方向错了断言 FAIL；
   - 链接行 URL 与 `app.stock-link` 模板拼出的结果逐字符一致（含 `{name}` URL 编码、雪球 `{market}` 前缀）。
2. **降级用例**：系统配置页停用全部 external 平台 / 关总开关 → payload 无链接行、其余排版不变；
3. **口径对齐**：同一批代码跑 `stockMarketOf`（JS）与后端推导，SH/SZ/BJ 三市各取一例比对一致；
4. **闸门回归**：`enforce-allowed-windows=true` + 窗口外 → 三类消息仍被拦（证明只改了文案没碰闸门）；
5. 日志无 GBK 乱码（启动带 `-Dfile.encoding=UTF-8`）。

## 7. 交付边界

本轮只做**本地改完 + 假钉钉验证 + payload 截图**，**未发布**；线上仍在 1.6.2，等明确发布指令再走打包-发布-线上验收流程。
