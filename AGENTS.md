# AGENTS.md — 项目 AI 协作规则

> 本文件是本仓库的**仓库级 AI 规则**。任何 AI 助手（WorkBuddy / Copilot / Cursor / Claude Code …）在本仓库读写代码前先读它。
> 分工：目录落位看全局 `~/.workbuddy/RULES.md`，提交与密钥看本文件。

一句话：**小步提交、每次提交带模型名、密钥一律不进仓库、产物只落 `.idea`。**

---

## 1. 提交规范

### 1.1 每次 commit 必须注明模型名（强制）

提交信息最后一段固定加一行 trailer：

```
Model: dsv4.1flash
```

- **必填**，一条 commit 对应一个主责模型。
- 写法：厂商缩写 + 版本，**全小写、不带空格**，例：
  - `dsv4.1flash`（DeepSeek-V4.1-Flash）
  - `gpt5.1codex`、`claudeopus4.6`、`gemini3pro`
  - 实在不确定就写 `unknown` 或自定一个短名，**不要留空、不要写中文描述**。
- 多个模型协作（如 A 写码、B 复核）时，再补一行 `Model-2: <名称>`，主责模型仍写在 `Model:`。
- 纯人工提交也必须保留该行，值写 `human`。

完整示例：

```
feat(kline): 抓取任务支持按周区间补齐

- 补数窗口按月切分，避免单次请求跨度过大被供应商拒绝
- 失败重试沿用 fetch-interval-ms 限流

Model: dsv4.1flash
```

类型沿用 Conventional Commits：`feat / fix / refactor / docs / test / chore / perf / build / ci`，范围用模块名（`kline` / `watch` / `auth` / `deploy` …）。

### 1.2 钩子保障（已启用）

`.githooks/commit-msg` 会在提交时检查是否已有 `Model:` 行；缺失时按以下顺序取名称并**自动补在消息末尾**：

1. 环境变量 `AI_MODEL`
2. `git config ai.model`
3. 兜底 `unknown`（并打印警告，提示尽快补配置）

启用方式（本仓库已执行）：

```bash
git config core.hooksPath .githooks
git config ai.model dsv4.1flash      # 每位使用者按自己用的模型改
```

换模型时只改 `git config ai.model`，不要再往仓库里写任何跟人相关的配置。

- `Merge` / `Revert` / `fixup!` / `squash!` 开头的提交由 git 自己生成，钩子直接放行。
- 钩子只做「补齐」，不拦截：补丁式 amend、`--no-verify` 都能用，但**规范上仍必须有该行**。

---

## 2. 密钥与敏感信息（强制）

**绝不入库**：数据库口令、超管初始口令、行情服务令牌、钉钉 webhook（含 `access_token`）、服务器 IP + 账号 + 口令 + 主机指纹、任何 `secret` / `apikey`。

落地方式：

| 场景 | 做法 |
| --- | --- |
| 应用密钥 | 只放 `shadow-flow-biz/src/main/resources/app.env`（已 gitignore，随包走） |
| 部署凭据 | 只放 `sh/deploy.env`（已 gitignore） |
| 配置文件 | 一律用 `${ENV_NAME:}` 空兜底占位符，**不允许**写明文默认值 |
| 模板文件 | `*.env.example` 只写「在此填写…」类占位符 |
| 校验/测试脚本 | 真实值从环境变量或本机 `app.env` 读取；取不到就 **SKIP**，禁止把真实值写进源码当「探针」 |
| 新环境接入 | 由使用者从 `*.example` 复制后自行填写，仓库里永远只有模板 |

提交前自检：

```bash
git diff --cached | grep -inE 'password|passwd|pwd|secret|token|webhook|access_token' 
git grep -nE 'access_token=[0-9a-f]{16,}|[0-9]{1,3}(\.[0-9]{1,3}){3}' -- .   # 只看是否新增了真实值
```

发现明文凭据：**先撤销入库 + 轮换凭据**，再谈改代码（历史里的值不会因为新提交而消失）。

---

## 3. 目录与产物落位

遵循全局规则，项目内只有 `<项目>/.idea/` 这一处存放非交付产物：

| 产物 | 位置 |
| --- | --- |
| 文档 / 报告 / 分析 | `.idea/docs` |
| 脚本 / 工具 / 调试代码 | `.idea/scripts` |
| 记忆 / 日志 | `.idea/.workbuddy` |
| 截图 / 一次性验证产物 | **不留在仓库根**；确需保留放 `.idea/docs/` 下 |

- 禁止出现 `<项目>/.workbuddy`。
- 根目录不留 `*.png` / `*.log` / `shot_*`（`.gitignore` 已兜底，但仍不落盘更干净）。
- 运行期数据（`data/`）、构建产物（`target/`）、`docs/` 均已在 `.gitignore` 里，不要为了让它们「能看见」去掉这些规则。

---

## 4. 构建与验证

```bash
# 后端（JDK 8 + Maven 3.6+）
mvn -B clean package -DskipTests

# 前端内联 JS 语法体检
node scripts/check_inline_js.js shadow-flow-biz/src/main/resources/static
```

- 版本验收脚本（`scripts/check_v16*.js`、`e2e_v16.js`、`verify_race.js`、`test_stock_picker.js`）依赖隔离工作区里的 `jsdom`，跑法见各文件头注释；它们是一次性验收资产，保留但不需要每次提交都跑。
- 改到行情/通知/账户口径时，至少手工验证一次本地起服务（`--server.port=8081`）的关键页面。

---

## 5. 代码约定

- Java 8、Spring Boot 2.7.6；持久化用 `JdbcTemplate`，**不引入 MyBatis / JPA**。
- 新增仓储要同时提供文件模式与数据库模式两条实现（无数据库模式是默认模式）。
- 前端原生 JS + ECharts，不引框架；新页面沿用 `static/js/common.js` 的请求/弹窗封装。
- 注释与界面文案用简体中文，注释写「为什么」，不写「做了什么」。
- 改 `application.yml` 的凭据键时，同步改 `app.env.example` 与本文件第 2 节。

---

## 6. 不要做的事

- 不擅自 `git push`、不改写历史、不强推（需人工明确授权）。
- 不删 `data/`、`docs/`、`sh/deploy.env`、`app.env` 这类运行/配置资产。
- 不把 `target/`、截图、日志提交进仓库。
- 不在提交信息里贴口令、令牌、内网地址。
