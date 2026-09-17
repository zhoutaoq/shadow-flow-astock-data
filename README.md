

# 金牛数据（stock-kline-data-crawler）

股票日K线数据管理与策略回测系统。数据来自「歪枣网（waizao）」，支持落库 MySQL 或实时获取；基于原生 HTML + ECharts 提供数据可视化、K线分析与策略回测；内置**实时盯盘提醒**，行情取自新浪财经，涨跌/封板推送至钉钉群。

---

## 一、项目特性

| 特性 | 说明 |
|---|---|
| **数据源** | 「歪枣网」行情 API（需配置 Token） |
| **数据存储** | 支持 **MySQL 8** 持久化或 **无数据库** 实时模式（默认） |
| **可视化** | 原生 HTML + ECharts，无外部 UI 框架依赖 |
| **策略回测** | 支持自定义买卖规则、涨跌停成交策略、滑点手续费 |
| **实时盯盘** | 监控价格波动、封板/开板，通过钉钉机器人推送（基于新浪实时行情） |
| **账户管理** | 多账户、持仓管理、当日/总收益计算、支持导入导出 |

---

## 二、技术栈

- **后端**：Java 8, Spring Boot 2.7.6, JdbcTemplate
- **前端**：原生 JavaScript, ECharts, HTML/CSS
- **数据库**：MySQL 8.0 (可选) 或本地文件存储
- **构建工具**：Maven 3.6+

---

## 三、快速启动

### 1. 环境准备

确保已安装 JDK 8 和 Maven。

```bash
java -version
mvn -version
```

### 2. 数据库配置（仅数据库模式需要）

系统启动时会自动执行 `src/main/resources/schema.sql` 创建表结构，请**预先创建空库**：

```sql
CREATE DATABASE mydb DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

配置连接信息于 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/mydb?...
    username: root
    password: ${DB_PASSWORD:root123456}   # 建议使用环境变量注入
```

### 3. Waizao Token 配置

数据更新的核心凭证。
- **首次启动**：如果没有配置，系统级 Token（`waizao.api.token`）会自动作为种子写入配置表。
- **后续**：系统优先读取用户级配置（通过前端「实时通知配置」页保存），未配置用户回落到系统级 Token。

### 4. 运行项目

```bash
# 开发启动
mvn spring-boot:run

# 打包运行
mvn clean package -DskipTests
java -jar target/stock-kline-data-crawler.jar --server.port=8081
```

访问地址：`http://127.0.0.1:8081/`

> ⚠️ **注意**：如果环境变量中存在 `SERVER__PORT`，可能会意外覆盖配置中的端口。

---

## 四、运行模式详解

系统通过 `application.yml` 中的 `waizao.db.enabled` 切换模式：

| 模式 | 开关 | 数据来源 | 写入存储 | 适用场景 |
|---|---|---|---|---|
| **无数据库模式** | `false` (默认) | 每次实时请求供应商 | 仅存配置（JSON） | 快速试用、免部署 MySQL |
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

1. **Token 消耗**：供应商接口按次计费，无数据库模式下预览也会产生真实调用。
2. **盯盘开关**：钉钉机器人需配置 Keyword 或 Secret，否则会被拦截。
3. **股票范围**：系统目前仅支持沪深 A 股（代码以 60/68/00/30 开头）。
4. **持仓数量**：每日 00:00 (北京时间) 系统会自动将所有账户的「可用数量」对齐至「持仓数量」（允许卖出隔夜持仓）。