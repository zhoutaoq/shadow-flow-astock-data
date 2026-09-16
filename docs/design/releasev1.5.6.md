## releaseV1.5.6

fix:
1、卖出选择标的只能从持仓中选择且可用股数 > 0，且数量不能超过可用股数；现在可以随便选择，选择没持仓的空指针异常，抓紧修复
2、所有买卖记录，默认股数 100，日期默认当天。

feat:
1、添加持仓和修改持仓支持，标的、可用数量、持仓数量、成本均价、建仓日，可用和持仓数量默认 100，建仓日默认当天；
2、持仓配置和操作记录数据都需要落盘（添加持仓和改/删持仓不用存，且不影响账户可用金额，只影响账户总金额，因为它是计算出来的），无数据库模式下文件形式落盘。
3、支持单个账户一键导出导入，zip 格式，完整输出/导入 账户情况 + 持仓情况 + 操作记录；（添加持仓和改/删持仓不用存）
4、持仓页标的支持超链接一键跳转雪球，如天赐材料跳转 url 是 https://xueqiu.com/S/SZ002709。
5、账户详情页按钮重复、多余太多，删除右上角的 重新载入、重命名、维护资金 三个按钮，将 添加持仓 和 记一笔移到持仓配置同高度右方，持仓配置文案改成持仓列表；记一笔文案改成添加操作记录。
6、每天 00 点定时任务将所有用户的所有账户的所有标的的可用数量设置成持仓数量。
7、用户注册埋点，用系统钉钉配置推送说明用户注册时间，邮箱号码。


tips:
开发完成后本地启动，不允许提交代码和发布等任何多余步骤，先让我验收。

---

# 实现说明（v1.5.6）

版本 `pom.xml` `<version>1.5.6</version>`（第 10 行），静态资源版本戳 `?v=20260916b`（全站 17 个 HTML 统一替换）。

下面按**上游 → 下游**的数据流走一遍：需求 → 模型 → 仓储（两种落点）→ 业务 → 接口 → 页面。

## 一、模型层：`availableQty` 与「操作记录落盘」

| 文件 | 改动 |
|---|---|
| `account/Position.java:51` | 新增 `private Integer availableQty;` —— **可空**是刻意的：`null` 表示「全部可用」，v1.5.5 及以前的老数据不带这个字段，读出来就是 `null`，等价于旧行为，不需要数据迁移 |
| `account/TradeRecord.java` | 新增 `tradeDate`（成交日期，`yyyy-MM-dd`），与录入时刻 `time` 分开：**成交日期是业务语义**（补记昨天的交易要按昨天算），录入时刻只是审计 |
| `resources/schema.sql:128` | `waizao_position` 加 `available_qty INT NULL` |
| `resources/schema.sql:144` | 新增表 `waizao_trade_record`（`user_id + account_id` 索引，第 161 行） |

## 二、仓储层：一个接口，两种落点

`AccountRepository` 新增 5 个方法，两个实现各自补齐：

| 方法 | 作用 | 为什么需要 |
|---|---|---|
| `listUserIds()` | 有账户的全部用户 id | 第 ⑥ 条日切要**跨用户**遍历，其余接口都是「已知 userId 再取数据」 |
| `listRecords` / `saveRecord` / `trimRecords` / `clearRecords` | 操作记录的读写 | 第 ② 条要求落盘，之前记录只在 `AccountService` 的内存 map 里 |

- **文件模式** `FileAccountRepository`：账户 / 持仓 / 操作记录**在同一个 `account-{userId}.json` 里**（新增 `records` 段）—— 它们本来就是一棵树，拆文件反而要处理跨文件一致性。`listUserIds()` 取「内存已载入」∪「磁盘上已有 `account-*.json`」的并集：定时任务可能在进程起来后、还没有任何请求碰过账户时就触发，只看内存会把整个文件漏掉。
- **数据库模式** `DbAccountRepository`：`listUserIds()` 用 `SELECT DISTINCT user_id FROM waizao_account`；`trimRecords()` 用「按 id 子查询」删旧行（MySQL 不允许在 `DELETE` 的子查询里直接引用目标表）。

## 三、业务层 `AccountService`

| 需求 | 落点 | 关键点 |
|---|---|---|
| fix ① 卖出空指针 | `addRecord(...):536` | 三道闸**提到解引用之前**：①该账户是否持有这只标的 ②`usableQty > 0` ③`数量 ≤ usableQty`。v1.5.5 只在「没填成交价」那条路径上判过空，页面一带上价就走到 `hit.getQuantity()` 上空指针 → 抛异常 → 前端看到「服务异常」 |
| — | `usableQty(Position):1160` | 唯一口径：`null` → 返回持仓数量；否则夹到 `[0, 持仓数量]`。前端、校验、日切全用这一个 |
| fix ② 默认值 | 页面侧（见第五节） | 后端只做校验：`数量 ≤ 0` 报错、成交日期非法报错（`checkTradeDate:1177`，与建仓日共用 `checkYmd`） |
| feat ① 可用数量 | `savePosition(...):388` | 校验 `0 ≤ 可用数量 ≤ 持仓数量`；**改持仓时不提交该字段则继承原值**，避免「只想改个名称、可用数量被清空」 |
| feat ② 记录落盘 | `addRecord:536` → `repository.saveRecord` + `trimRecords` | 保持每账 200 条上限。补记历史（`tradeDate` 不是今天）**不进当天买入池、不算当日已实现**，否则会污染当日盈亏 |
| feat ② 增删改持仓不留记录 | 只在 `addRecord` 里写记录 | 「添加 / 改 / 删持仓」三个动作本来就不生成操作记录，所以**不需要存**；它们也**不动可用金额**（只有买卖才动），只影响账户总市值 —— 而总市值是现价算出来的，不落盘 |
| feat ③ 导出 / 导入 | `AccountTransferService:80 / :160` | 见下 |
| feat ⑥ 日切 | `resetAvailableQty():474` | 遍历所有用户 → 所有账户 → 所有持仓，把可用数量对齐到持仓数量。**已经是「可用 = 持仓」的不重写**：每天全量重写会把落盘文件 mtime 全刷一遍，出问题反而看不出哪几行真变过。做成 `public` 是为了能脱离 HTTP 单独验证，不必为一个内部动作多开写接口 |

### 单账户导入的语义是「新建」，不是「覆盖」

`AccountTransferService.importZip()`：zip 里 `account.json` / `positions.json` / `records.json`。
**不带 id、不带 userId**，所以跨机器跨账号都导得进去，也不会把导出者的归属带过去。
导入时先**全量校验通过再落盘**（账户 → 持仓 → 记录），中途失败不会留下半本账；
重名走 `uniqueName()` 自动加「（导入）」后缀，**已有的账一行都不动**。
读 zip 做了路径穿越（`../`）与单条目大小上限的防护，非法文件给出「这个文件里没有账户信息（account.json），不是本系统导出的账户文件」这类可读提示，不是「服务异常」。

## 四、调度层：每天 00:00 的日切

| 文件 | 说明 |
|---|---|
| `account/AvailableQtyResetTask.java:42` | `@Scheduled(cron = "${waizao.account.available-reset-cron:0 0 0 * * ?}", zone = "Asia/Shanghai")`。**时区写死东八区**：服务器时区不是 UTC+8 时按默认时区算会落到别的时刻，口径与 `TradeSessions` 保持一致。任务里 `catch` 所有异常：抛出去会把调度线程整个弄停、以后再也不触发 |
| `account/AccountScheduleConfig.java` | 账户模块自己的 `@EnableScheduling`。**不蹭盯盘那边的** —— `WatchConfig` 挂在 `watch.enabled` 开关下，关掉盯盘会把日切一起带走，这两件事没有依赖关系。两边同时开着也无害（同一个基础设施 bean 名，重复注册被忽略），已实测 |
| `application.yml:89 / :91` | `available-reset-cron` / `available-reset-enabled`。本地验证时把 cron 改成 `"0/15 * * * * ?"`，不用真等到半夜 |

## 五、前端 `pages/account.html`

| 需求 | 落点 |
|---|---|
| feat ④ 跳雪球 | `xueqiuUrl(code):206` —— 沪市 `6/9` → `SH`、深市 `0/2/3` → `SZ`、北交所 `4/8` → `BJ`；持仓表代码渲染成 `<a class="code-link" target="_blank" rel="noopener">` |
| feat ⑤ 页头去重 | 二级页头 `#acctPanel .ahead-r` 三个按钮（重新载入 / 重命名 / 维护资金）整块删除；「＋ 添加持仓 / ＋ 添加操作记录」移到持仓列表卡头 `.acard-acts`，与标题同一行右侧；`持仓配置 → 持仓列表`、`记一笔 → 添加操作记录` |
| fix ① 卖出只认持仓 | `sellCandidates():821` 作为选择器的 `source` 只给「该账户里可用数量 > 0 的持仓」，`allowManual` 卖出时关掉 —— 手输代码一律不认；`emptyTip` 给出「这本账户里没有可卖出的持仓，先添加持仓或买入一只」 |
| fix ② 默认值 | 持仓弹窗：数量 / 可用数量默认 `100`、建仓日默认今天；操作记录弹窗：数量默认 `100`、日期默认今天且「当天」勾选框默认勾上 |
| 卖出三道闸（前端） | `paintRecordNote():963` 把可用数量写进说明文字并设成数量输入框的 `max`（提前挡住）；`submitRecord():1005` 再做一次校验兜底 |
| feat ① 可用数量列 | 持仓表扩到 12 列，`可用数量` 紧跟 `持仓数量`；弹窗里可用数量超过持仓数量时前端先拦 |
| feat ③ 导出 / 导入 | `exportAccount(id):572` 走 `fetch` 拿 zip → `a.download` 存本机；`pickImportFile():604` + `submitImport(input):617`（隐藏的 `#impFile` 点按钮时代替它弹窗） |

`css/account.css` 配套加 `.acard-acts` / `.afile` / `.atable .code-link`，页面头部注释升到 v1.5.6。

## 六、共用组件

`js/stock-picker.js` 新增 `source`（本地候选来源）/ `emptyTip` / `setPlaceholder()` / `refresh()`，支持「候选只来自本地名单、不查名录」的卖出场景。
这个文件被 chart / backtest / update-* / api-log / debug / watch 共用，**改动后必须跑跨页回归**（见下）。

---

# 追加需求：注册埋点（钉钉推送「注册时间 + 邮箱」）

需求原文第 7 条。与上面 6 条没有依赖，是独立的一小块：新用户注册成功 → 推一条钉钉。

## 一、先解决一个不显然的坑：唯一的钉钉出口带着时段闸门

`watch/DingTalkNotifier.java:135` 新增 `postAlways(webhook, content)`，与原来的 `post()`（`:113`）共用同一个私有实现（`:139`），**只差一个 `ignoreSessionGate` 标志**。

为什么必须加这条通道 —— 这是本次最容易踩空的地方：

| | |
|---|---|
| `post()` 的地位 | 所有钉钉消息的**唯一出口**：盯盘告警、关键时点播报、启动通知、用户反馈全走它，一处拦截就是全部拦截 |
| 它挂着的闸门 | `watch.dingtalk.push-only-in-trade-sessions`（默认 `true`，窗口 `09:15-11:30,13:00-15:00`，周一至周五）；非交易时段**一条都不发、只写日志** |
| 那道闸门为什么存在 | 治「盯盘进程在非交易时段照旧轮询、重启后第一轮把已经封板误报成刚封板」—— 是**机器轮询产生的噪声** |
| 注册为什么不属于这类 | 人什么时候注册就是什么时候，注册高峰常在晚上；走 `post()` 会被静默掉，埋点等于没做。一天几条，也不构成刷屏 |

`watch.dingtalk.enabled` 总开关**仍然管着它** —— 本地开发关着，一条都出不去，这正是我们要的。

## 二、链路（上游 → 下游）

| 层 | 文件:行 | 做了什么 |
|---|---|---|
| 入口 | `auth/AuthController.java:41` | `POST /api/auth/register`；匿名可用（在 `AuthInterceptor.PUBLIC_WRITES` 的例外名单里） |
| 业务收口 | `auth/AuthService.java:88` | `register()` —— 全项目**唯一**的注册入口（管理端只有列表 / 重置密码 / 代理登录，没有建号接口；`ensureAdmin()` 建超管不算注册） |
| 埋点触发 | `auth/AuthService.java:108` | 落库成功、打完 `[auth] 新用户注册` 日志之后调 `notifyRegistered(u)` |
| 推送器 | `auth/RegistrationNotifier.java:74` | 取「注册这一刻」的北京时间 → 丢进单线程 daemon 池，**立即返回** |
| 真正发送 | `auth/RegistrationNotifier.java:90` | 从系统配置取 webhook（`watch.dingtalk.webhook`）→ 调 `postAlways` |
| 出口 | `watch/DingTalkNotifier.java:135` | 跳过时段闸门，仍受 `watch.dingtalk.enabled` 约束 |

## 三、三个刻意的决定

1. **异步发送**（`RegistrationNotifier.java:74`）。注册必须秒回，而钉钉的超时上限是「连接 5s + 读 10s」—— 同步发就等于把十几秒挂在用户的注册请求上。用一个单线程 daemon 池发；进程退出时丢掉未发出的消息可以接受，通知不是账本。
2. **吞掉一切异常**（`:104`）。推送失败绝不能让 `register()` 抛出去 —— 用户这时候已经建好了，不能因为「通知没发出去」就告诉他注册失败。失败只在 `app.log` 留 `[register]` 痕迹。
3. **webhook 在「发的那一刻」取**（`:93`）。不缓存到字段里：运维在盯盘页面「实时通知配置」改的正是 `props.getDingtalk().getWebhook()` 这个字段，注册埋点跟着走、不用重启。

## 四、配置

| 项 | 默认 | 说明 |
|---|---|---|
| `waizao.auth.notify.enabled` | `true` | 埋点开关。false 时注册不再推送，**注册本身完全不受影响** |
| `waizao.auth.notify.keyword` | `智能盯盘` | 机器人「自定义关键词」模式下必须命中的词；复用盯盘机器人时别改 |

**没有 webhook 配置项** —— 按需求「用系统钉钉配置推送」，直接用 `watch.dingtalk.webhook`，与盯盘告警、用户反馈进同一个群，不另拉群、不重复配机器人。

绑定类 `auth/RegistrationNotifyProperties.java:18`（前缀 `waizao.auth.notify`），与管登录态时长 / 注册开关的 `app.auth`（`AuthProperties`）分开：那个管「能不能注册」，这个管「注册完要不要吱一声」。

## 五、消息形态

```
[智能盯盘] 新用户注册
时间：2026-09-16 22:51:38
邮箱：someone@example.com
```

- 时间是**北京时间**（`TradeSessions.nowCn()`，`:85`），格式与项目其它模块一致（`yyyy-MM-dd HH:mm:ss`）；取「注册那一刻」而不是「发送那一刻」，推送排队了时间也还是真的注册时间。
- 邮箱就是用户名 —— 本项目的用户名规则本身就要求是合法邮箱（`AuthService.checkUsername`），不需要另取字段。
- 首行 `[关键词]` 前缀是钉钉机器人「自定义关键词」模式的硬要求，与 `FeedbackService` 同一套写法。

## 六、刻意**不**做的事（避免越界）

- **超管初始化不推**：`ensureAdmin()` 建 `admin` 不是注册。
- **登录 / 改密 / 维护资金都不推**：需求只要「注册」这一件事。
- **消息里不放 IP、用户 id**：需求写明「注册时间，邮箱号码」。要加的话在 `RegistrationNotifier.buildText`（`:112`）加一行即可，但别把实现细节写进给人看的消息里。

## 七、怎么把「非交易时段」这件事真的测出来

直接跑主用例只能证明「注册会推」，证明不了「时段闸门没拦住它」—— 万一闸门本来就没关，那条断言是白过的。所以验证脚本用**控制组**：

| 步骤 | 断言 | 说明 |
|---|---|---|
| 起后端时 | `--watch.dingtalk.trade-sessions="00:00-00:01"` | 让「闸门此刻关着」与运行时刻无关（除 00:00~00:01 外恒为关），不依赖「恰好晚上跑」 |
| 控制组 | `POST /api/feedback` → 返回「反馈没能送出」且机器人收到 0 条 | 反馈走的是**受闸门约束**的 `post()`；它被拦下 ⇒ 证明闸门确实关着、假机器人在线但没收到东西 |
| 主用例 | `POST /api/auth/register` → 机器人收到 1 条，正文含邮箱与时间 | 注册走 `postAlways`，**跨时段发出来了** |
| 反向 | 重复邮箱 / 密码不合规 → 都失败，且消息数不变 | 只有真注册成功才推 |
| 开关组 | 另起实例 `--waizao.auth.notify.enabled=false` → 注册 200 但机器人 0 条 | 反证断言是有效的（若代码无视开关照推，这一条会 FAIL） |

另有一条铁律：**验证时绝不能打开真实 webhook** —— `application.yml` 里 `watch.dingtalk.webhook` 指向真实钉钉群，一注册就会发到群里。所以起验证实例时统一覆盖成假机器人：

```
--watch.dingtalk.webhook=http://127.0.0.1:18099/robot/send --watch.dingtalk.enabled=true
```

假机器人 `ui-verify/fake_dingtalk.py` 把收到的 body 逐行记 JSONL，并留 `GET /_dump` 给断言用。

---

# 自测结论（本地，未发布）

后端以隔离数据目录本地启动、真实 Chromium 跑真页面，全部 PASS：

| 套件 | 脚本 | 结果 |
|---|---|---|
| v1.5.6 端到端（6 项改动逐条断言，真后端 + 真浏览器） | `.workbuddy/ui-verify/verify_v156.js` | **77/77** |
| v1.5.6 样式数值断言（亮色 + 暗色各一遍，`getComputedStyle` 逐项比） | `.workbuddy/ui-verify/verify_v156_style.js` | **46/46** |
| v1.5.6 可用数量日切（cron 覆盖成每 15 秒，含落盘核对） | `.workbuddy/ui-verify/verify_v156_reset.js` | **9/9** |
| 股票选择器跨页回归（改了共用组件，8 个页面 × 2 项） | `.workbuddy/ui-verify/verify_picker_pages.js` | **16/16** |
| v1.5.2 全量回归（含账户页两级、暗色、全站无 JS 报错） | `.workbuddy/ui-verify/verify_v152.js` | **92/92** |
| **注册埋点**主用例（跨时段推得出去 + 真浏览器注册） | `.workbuddy/ui-verify/verify_v156_register.js`（`MODE=main`） | **25/25** |
| 注册埋点反证：埋点开关关闭 → 注册成功但一条不推 | 同上（`MODE=off`） | **3/3** |
| 共用钉钉出口回归：闸门放开时 `post()` 仍发得出去 | 同上（`MODE=open`） | **3/3** |
| 账户全量回归（本次改动后复跑，确认无回归） | `.workbuddy/ui-verify/verify_v156.js` | **77/77** |
| 接口授权矩阵（本次改动后复跑） | `.workbuddy/ui-verify/verify_authz.py` | 23/30，见下方说明 |

关键断言（挑几条会被肉眼放过的）：

- **fix ①**：卖出未持仓的标的 → 「这本账户里还没有 贵州茅台 的持仓，无法卖出」，且断言**报错文案不是「服务异常」**（空指针回归判据）；可用数量为 0 的持仓可以被创建，但卖出时被拦。
- **feat ②**：买入后可用数量 100 → 200；卖出 100 股后持仓与可用数量同步减到 100；记录在**接口重读 + 落盘文件双份核对**后仍在。
- **feat ③**：导出的 zip 内含三份 JSON、**不含 `id` / `userId`**；导入后账户数 +1、**原账户持仓一条没动**；导入的持仓与源账户**逐项一致**；非法文件被可读提示拒绝；未登录导出返回 401。
- **feat ④**：`002709 天赐材料 → https://xueqiu.com/S/SZ002709`、`688 → SH`、`300 → SZ`、`8 → BJ` 逐个验；链接 `target=_blank rel=noopener`。
- **feat ⑤**：二级页头 `.ahead-r` 按钮数 = 0；两个录入按钮在持仓列表卡头，**与标题垂直中心对齐（容差 2px）** 且在其右侧；操作记录卡头不再有录入按钮。
- **feat ⑥**：日切前刻意造 `可用 50 / 持仓 200` 与 `可用 300 / 持仓 300` 两条，15 秒内前者对齐成 200、后者保持 300 不动，并**直接读 `account-{userId}.json` 确认落盘**。
- **样式**：`.acard-acts` 与 `.acc-ops` 的 `.btn-ghost.btn-sm` **逐项一致**（圆角 8px / 边框 1px / 内距 11px / 字号 12px / 行高 28px / 透明底 / 无阴影）；`.code-link` 不是浏览器默认链接（`text-decoration:none` + 虚线下边框，颜色不是 `rgb(0,0,238)`，沿用等宽字体 Consolas）；顶栏仍单行（总高 56px、各块垂直中心 maxDy 0.01px）；亮/暗两套主题**底色方向分别正确**（`rgb(244,246,249)` 与 `rgb(15,20,32)`），防止两张截图其实一模一样。
- **注册埋点**（第 7 条追加需求）：先跑**控制组**证明闸门确实关着 —— 同一时刻 `POST /api/feedback`（走受闸门约束的 `post()`）得到「反馈没能送出」且假机器人收到 **0** 条；紧接着注册，机器人**收到 1 条**，正文三行（`[智能盯盘] 新用户注册` / `时间：2026-09-16 22:58:19` / `邮箱：reg-main-…@example.com`），且消息里的时间与脚本记录的注册时刻**逐秒一致**。反向：重复邮箱、密码不合规都不推（消息数保持 1）。另跑两组反证 —— 埋点开关关闭时注册 200 但 0 条推送；闸门放开时 `post()` 仍能正常发出（防止把共用出口改死）。真浏览器走页面注册弹窗也触发推送，注册后顶栏进入登录态、无 JS 异常、除 `favicon.ico` 外无 4xx。
- 全站无 JS 报错；`node scripts/check_inline_js.js` 通过。

隔离构建产物：`.workbuddy/build-iso/target/stock-kline-data-crawler.jar`（135 个源文件编译通过，`BUILD SUCCESS`，版本 `1.5.6`）。

**发布状态：未发布。** 线上 `http://111.231.171.218:8080` 仍是 **v1.5.5**（buildTime `2026-09-16 19:21`），
代码**未提交**（按 tips 要求，等验收）。

## 验收过程中顺带修掉的两处

- `verify_v152.js` 里的 `#fInit` / `#fCash` 是**v1.5.5 改名前的旧 id**（现在叫 `fundInit` / `fundCash`，资金回显改成了只读的 `#fundRows`）——脚本写于 v1.5.2 时代、之后没跟着更新，一读就空指针把整个 step 打断。已按当前结构改为断言 `#fundRows` 的键值对。
- `verify_v152.js` 断言账户卡只有「进入 / 重命名 / 删除」三个按钮，本次新增「导出」后过期，已同步。

## 顺带发现、但**本次没有动**的一件事：`verify_authz.py` 与主线对不上

复跑 `verify_authz.py http://127.0.0.1:18090` 得到 **23/30**，7 条失败：

| 失败项 | 脚本期望 | 实际 |
|---|---|---|
| 匿名 `POST /api/stocks`、`POST /api/stocks/import`、`PUT/DELETE /api/stocks/1` | 401 | **200** |
| 普通用户 `GET/POST /api/sys-config`、`DELETE /api/sys-config/1` | 403 | **200** |

**与本次改动无关**，理由有两条硬证据：

1. 本次 diff 不含任何鉴权文件 —— `git diff --stat HEAD -- src/main/java/com/stock/kline/auth/AuthWebConfig.java` 为空（逐字节一致），失败路径也不在本次改动内（本次只碰 `AuthService` / `RegistrationNotifier` / `DingTalkNotifier` / `application.yml`）。
2. 脚本对不上的根因是**它对应的一套鉴权加固只躺在 git stash 里**：
   - `git log --all -S'ADMIN_ONLY' -- src/` 只命中 `78b2989`，那是 `index on (no branch)` 形态，即 `stash@{0}` / `stash@{1}`（基于 `3e2ba1c`，是 HEAD 的祖先）；
   - 那套实现是 `AuthInterceptor.ADMIN_ONLY`（登录 + 超管）+「`/api/**` 非 GET 一律要登录，例外 `PUBLIC_WRITES`」；
   - **主线从未合并**：`src/` 全局 `grep ADMIN_ONLY|PUBLIC_WRITES` 无命中，`AuthWebConfig` 只有一份 `PROTECTED` 显式前缀清单（`/api/backtest/**`、`/api/watch/**`、`/api/fetch/**`、`/api/admin/**`、`/api/sys-config/**`、`/api/account/**`、三个 auth 写接口），`/api/stocks/**` 不在其中。

判断（供决策，未动手）：

- `/api/sys-config` 那三条多半是**脚本过期**而非漏洞 —— v1.5.0「令牌按用户隔离」之后，普通用户读写**自己名下**的配置本来就是设计意图（`SysConfigController` 按 owner 收窄），不该再要求 403。
- `/api/stocks` 那四条是**真的匿名可达**：`POST /api/stocks/import` 可被匿名触发重导股票列表。这是主线既有状态（不是本次引入），是否收紧要单独定。

要不要把 stash 里那套加固捞回来、或只给 `/api/stocks/**` 补一条 `PROTECTED`，等指令。
