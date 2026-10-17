# Copilot / 其它 AI 助手入口

本仓库的 AI 规则以根目录 [`AGENTS.md`](../AGENTS.md) 为准，动代码前先读它。

其中必须遵守的两条：

1. **每次 commit 必须注明模型名**：提交信息末尾加 `Model: <名称>`，如 `Model: dsv4.1flash`。
   缺行时 `.githooks/commit-msg` 会按 `git config ai.model` 自动补。
2. **密钥不入库**：口令 / 令牌 / 钉钉 webhook / 服务器凭据只放 `app.env`、`sh/deploy.env`
   （均已 gitignore），代码里只用 `${ENV:}` 占位符。
