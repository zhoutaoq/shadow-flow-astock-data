

# 影流数据（Shadow Flow Data）

股票日K线数据管理与策略回测系统。行情数据来自外部行情数据服务，支持落库 MySQL 或实时获取；基于原生 HTML + ECharts 提供数据可视化、K线分析与策略回测；内置**实时盯盘提醒**，行情取自新浪财经，涨跌/封板推送至钉钉群。

---

## 线上服务

| 环境 | 地址 | 说明 |
|---|---|---|
| 生产 | <http://111.231.171.218:8080> | 影流数据线上环境；实时资讯 / K线分析匿名可用，盯盘提醒、我的账户等功能需注册登录 |

> 线上为无数据库（文件存储）模式。发布只走 GitHub Actions（推到 `github` 分支即打包上线），流程与 Secrets 见 `sh/使用说明.md`。

---

## 一、项目特性

| 特性 | 说明 |
|---|---|
| **数据源** | 外部行情数据服务 API（需配置访问凭据） |
| **数据存储** | 支持 **MySQL 8** 持久化或 **无数据库** 实时模式（默认） |
| **可视化** | 原生 HTML + ECharts，无外部 UI 框架依赖 |
| **策略回测** | 支持自定义买卖规则、涨跌停成交策略、滑点手续费 |
| **实时盯盘** | 监控价格波动、封板/开板，通过钉钉机器人推送（基于新浪实时行情） |
| **账户管理** | 多账户、持仓管理、当日/总收益与当日操作盈亏、支持导入导出；超管可平铺查看多个用户的所有账户（只读） |

---

## 二、技术栈与模块结构

- **后端**：Java 8, Spring Boot 2.7.6, JdbcTemplate
- **前端**：原生 JavaScript, ECharts, HTML/CSS
- **数据库**：MySQL 8.0 (可选) 或本地文件存储
- **构建工具**：Maven 3.6+

工程为 Maven 多模块聚合，`groupId` = `io.github.zhoutaoq`：

```
shadow-flow-parent            ← 聚合 pom
├── shadow-flow-notify        ← 通知 SDK：通用通道 / 目标解析 / 时段闸门，不含任何业务词
└── shadow-flow-biz           ← Web 服务：业务与页面，依赖 notify；产物 shadow-flow-biz.jar
```

`shadow-flow-notify` 是可直接复用的通用通知能力（钉钉群机器人通道、允许发送时段、
目标解析 SPI），业务文案一律由 `shadow-flow-biz` 组装后传入。

---

## 三、快速启动

### 1. 环境准备

确保已安装 JDK 8 和 Maven。

```bash
java -version
mvn -version
```

### 2. 数据库配置（仅数据库模式需要）

系统启动时会自动建表（脚本 `shadow-flow-biz/src/main/resources/schema.sql`），
请**预先创建空库**：

```sql
CREATE DATABASE mydb DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

配置连接信息于 `shadow-flow-biz/src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/mydb?...
    username: root
    password: ${DB_PASSWORD:}   # 口令不写进本文件，真实值放 app.env（见下）
```

### 3. 凭据配置（app.env，v1.6.4 起在 classpath）

**所有密钥集中在 `shadow-flow-biz/src/main/resources/app.env`**（已列入 `.gitignore` 不入库，
打包时随 jar 走），`application.yml` 里只留 `${...}` 占位符。启动时
`ShadowFlowApplication.main()` 先调 `AppEnv.load()` 从 classpath 读出该文件、
把键值设成 JVM 系统属性（**不经过环境变量**），占位符解析时自然取到真实值；
**读不到该文件服务直接拒绝启动**，值仍是「在此填写…」占位文字的项按未配置处理：

| 配置键 | 用途 |
|---|---|
| `DB_PASSWORD` | 数据库口令（仅数据库模式用） |
| `ADMIN_PASSWORD` | 超管初始口令（只在 admin 账号还不存在时用于初始化） |
| `SHADOWFLOW_API_TOKEN` | 行情数据服务令牌 —— 数据更新的核心凭据 |
| `DINGTALK_WEBHOOK` | 系统通知机器人（发布通知 / 注册通知 / 用户反馈，兼作盯盘默认群） |

- 模板见同目录 `app.env.example`，复制成 `app.env` 后填真实值。
- 本地开发与服务器行为完全一致，本地跑也要先建好 `app.env`；
  临时覆盖单项仍可用 `-D`（如 `-DDINGTALK_WEBHOOK=…`）或命令行 `--watch.dingtalk.webhook=…`
  —— 同名系统属性已存在时 `AppEnv` 不覆盖。
- 行情令牌与钉钉机器人**还有一层「系统配置页」**：优先读超管在
  「系统配置 → 第三方对接」页维护的配置行（全局一份、保存即生效），
  配置行没填时才回落到 `app.env` 里的出厂值 —— 所以换令牌 / 换机器人不用重启、也不用改文件。
- 线上到底装没装进来，看「系统配置」页右上角的**环境查看**（只读弹窗：
  凭据脱敏、运行参数明文）。

### 4. 运行项目

```bash
# 开发启动
mvn -pl shadow-flow-biz -am spring-boot:run

# 打包（在仓库根目录执行，一次产出两个模块）
mvn clean package -DskipTests
java -jar shadow-flow-biz/target/shadow-flow-biz.jar --server.port=8081
```

访问地址：`http://127.0.0.1:8081/`

> ⚠️ **注意**：如果环境变量中存在 `SERVER__PORT`，可能会意外覆盖配置中的端口。

---

## 四、运行模式详解

系统通过 `application.yml` 中的 `shadowflow.db.enabled` 切换模式：

| 模式 | 开关 | 数据来源 | 写入存储 | 适用场景 |
|---|---|---|---|---|
| **无数据库模式** | `false` (默认) | 每次实时请求行情数据服务 | 仅存配置（JSON） | 快速试用、免部署 MySQL |
| **数据库模式** | `true` | 本地 MySQL 日K表 | 落库 MySQL | 长期投资、批量更新、大量历史数据 |

*首次使用建议使用默认模式（无需 MySQL），待熟悉功能后再开启数据库模式。*

---

## 五、功能模块概览

### 1. 分析回测
- **K线分析**：支持日K查看、拖拽缩放。
- **策略回测**：支持配置复杂的买入/卖出条件（如均线、成交量、涨跌幅），并模拟成交。
- **股票列表**：沪深A股名录管理。

### 2. 我的账户（需登录）
- **资金与持仓**：管理多账户，支持持仓增加、买卖操作记录。
- **收益计算**：区分「当日盈亏」与「总盈亏」，持仓支持按买入时间分批核算基准价。
- **数据迁移**：支持单账户一键导出（ZIP）和导入。

### 3. 实时资讯
- **热点板块/个股**：聚合东方财富、新浪、同花顺数据（匿名可用，自动刷新）。

### 4. 实时盯盘提醒
- **配置**：在「实时通知配置」页开启。
- **触发**：价格偏离阈值（默认 1%）、涨停/跌停/打开涨跌停。
- **推送**：通过钉钉 Webhook 发送消息。
- **盯盘名单**：配置于 `resources/watch-list.txt` 或前端配置页。

---

## 六、核心接口

| 接口路径 | 方法 | 说明 |
|---|---|---|
| `/api/stocks/import` | POST | 从 `stock-list.json` 导入股票列表 |
| `/api/kline/chart` | GET | 获取 K 线数据（ECharts 用） |
| `/api/backtest/run` | POST | 执行策略回测 |
| `/api/account/summary` | GET | 账户收益汇总（需登录） |
| `/api/watch/config` | GET/PUT | 获取/保存盯盘配置（需登录） |

---

## 七、注意事项

1. **调用量**：无数据库模式下每次预览都会真实请求行情数据服务；批量更新超过 20 只会先弹二次确认。
2. **盯盘开关**：钉钉机器人需配置 Keyword 或 Secret，否则会被拦截。
3. **股票范围**：系统目前仅支持沪深 A 股（代码以 60/68/00/30 开头）。
4. **持仓数量**：每日 00:00 (北京时间) 系统会自动将所有账户的「可用数量」对齐至「持仓数量」（允许卖出隔夜持仓）。

---

## 八、参与开发与合并流程（Pull Request）

本项目按开源项目惯例走 **Pull Request**，不接受直接向主干推提交：

```
fork / 建分支 → 提交 → 本地自测 → 提 PR → 评审 → 合并 → CI 自动发布
```

### 1. 建分支

从发布分支切出，分支名带类型前缀：

```bash
git switch -c feat/admin-account-board github
# 常见前缀：feat/  fix/  refactor/  docs/  chore/
```

### 2. 提交规范

沿用 [Conventional Commits](https://www.conventionalcommits.org/)，范围写模块名
（`kline` / `watch` / `auth` / `admin` / `deploy` …）；**提交信息最后一行必须带模型名**：

```
feat(account): 超管账户管理支持平铺多用户

- 默认预览用户落系统级配置，进入页面即平铺
- 卡片增加当日收益率（当日收益 / 持仓市值）

Model: dsv4.1flash
```

纯人工提交写 `Model: human`。仓库钩子 `.githooks/commit-msg` 会自动补这一行，
但规范上仍要求自己写。详细规则见 [`AGENTS.md`](AGENTS.md) 第 1 节。

### 3. 本地自测

```bash
mvn -B clean package -DskipTests
node scripts/check_inline_js.js shadow-flow-biz/src/main/resources/static
```

改了行情 / 通知 / 账户口径时，再本地起一次服务手工点一遍关键页面（`--server.port=8081`）。

### 4. 提 PR

```bash
git push origin feat/admin-account-board
```

然后在 GitHub 上以 `github` 为 base 开 PR，PR 描述建议写清三件事：

| 段落 | 写什么 |
|---|---|
| 改了什么 | 行为层面的变化，不是文件清单 |
| 为什么 | 对应的问题或需求；有 trade-off 一并写 |
| 怎么验 | 复现步骤 / 自测命令 / 关键接口返回；界面改动附截图 |

### 5. 评审与合并

- 至少一位维护者 review 通过后再合并，**优先 squash merge**：主干上一条提交对应一件事；
- 合并进 `github` 即触发 GitHub Actions（打包 → 上传 → 远端重启 → 探活），发布详情见 [`sh/使用说明.md`](sh/使用说明.md)；
- 紧急修复同样走 PR，不走「本地打包直传」——线上版本只认 CI 产物。

### 6. 不要提交的东西

密钥与运行资产一律不进仓库：`app.env`、`sh/deploy.env`、`data/`、`target/`、本地发布脚本
（`sh/deploy.bat` / `plink.exe` / `pscp.exe`）。提交前可自查：

```bash
git diff --cached | grep -inE 'password|passwd|secret|token|webhook|access_token'
```

发现明文凭据：**先撤销入库 + 轮换凭据**，再改代码 —— 历史里的值不会因为新提交而消失。