## releaseV1.6.1

将项目中所有密钥相关内容，全部提取到 app.env 中，并在代码中用占位符标识、做出注释说明；
服务器上 env 保留真实配置；系统配置页添加「环境变量查看」按钮，点击弹窗显示当前服务用的
环境变量列表，只读。

---

### 一、密钥集中到 `sh/app.env`

改造前，四处凭据以明文躺在 `application.yml`（及 `AuthProperties` 的字段默认值）里、
随仓库一起提交：

| 凭据 | 改造前的位置 | 环境变量名 | 改造后 |
|---|---|---|---|
| 数据库口令 | `spring.datasource.password: root12…` | `DB_PASSWORD` | `${DB_PASSWORD:}` + 注释 |
| 超管初始口令 | `app.admin.password: 1`、`AuthProperties.Admin#password = "1"` | `ADMIN_PASSWORD` | `${ADMIN_PASSWORD:}` + 字段默认值改空串 |
| 行情数据服务令牌 | `shadowflow.api.token: 305c89…070c` | `SHADOWFLOW_API_TOKEN` | `${SHADOWFLOW_API_TOKEN:}` + 注释 |
| 系统通知机器人 | `watch.dingtalk.webhook: …access_token=ebcf…` | `DINGTALK_WEBHOOK` | `${DINGTALK_WEBHOOK:}` + 注释 |

新增两个文件，与既有的 `server.env` / `deploy.env` 保持同一套「按敏感性分文件」的口径：

```
sh/server.env        服务器运行参数（部署目录 / JDK / JVM / 启动参数）—— 入库，会上传服务器
sh/app.env           应用密钥（上面四项）—— 不入库，服务器上保留真实配置
sh/app.env.example   应用密钥模板（只有占位符与字段说明）—— 入库
```

- 格式沿用 `set "K=V"`（cmd 语法），与 `server.env` 完全一致：`.bat` 能 `call`，
  `remote.sh` 的解析器逐行读、自行剥 `\r`，`.gitattributes` 已把 `*.env` 钉成 CRLF；
- `sh/app.env` 已加入 `.gitignore`，不会被提交；
- **注入链路**：`deploy.bat` 发布时把 `app.env` 一起上传 → `remote.sh` 启动前
  `load_env_file app.env` 解析并 `export` → `start_new()` 起的 java 进程继承 →
  `application.yml` 里的 `${DB_PASSWORD}` 这类占位符据此取值。
  两个 env 文件共用同一个解析函数，格式或范围变了只改一处。
- 缺失容错：本机没有 `app.env` 时 `deploy.bat` 只告警不中断（服务器上那份保持不动）；
  远端没有 `app.env` 时 `remote.sh` 同样只告警 —— 服务照常启动，配置页填过的凭据不受影响
  （配置行优先于环境变量），只是 yml 占位符拿不到出厂兜底值。

> 之前那些明文值原样迁进了 `sh/app.env`，所以线上行为不变：配置页填过的凭据仍优先，
> 没填的仍回落到这份出厂值。

### 二、系统配置页 → 环境变量查看（只读）

- 后端新增 `EnvViewService` + `GET /api/admin/env`（超管；`/api/admin/**` 由拦截器保证已登录，
  控制器再判一次 `AuthContext.isAdmin()`）。返回两组：
  - **凭据**（`DB_PASSWORD` / `ADMIN_PASSWORD` / `SHADOWFLOW_API_TOKEN` / `DINGTALK_WEBHOOK`）
    —— 值一律脱敏（复用 `IntegrationConfigService#mask`，只露前后各 4 位），
    并按 `环境变量 / 系统配置页 / 未设置` 标出来源；
  - **运行参数**（`REMOTE_DIR` / `JAR_NAME` / `JAVA` / `OPTS` / `ARGS` / `LOG`）—— 无密钥，明文，
    用来核对本次发布实际带上了哪些启动参数。
- 只列本服务约定的这几个键，**不是把进程环境整个吐出来** —— 否则会把服务器上其它服务的
  变量（PATH / JAVA_HOME / 别家的口令）一并带到浏览器眼前。
- 页面：`system.html` 顶部工具条加「环境变量查看」按钮，点开 `#envMask` 弹窗（复用站内
  `.mask` / `.modal-lg` / `table.grid` 样式，无新增 CSS）。弹窗**只读**，没有写入口 ——
  值改在服务器上的 `sh/app.env`，改完跑一次发布即生效。说明文案走 `title` 原生气泡，不常驻表格。

### 三、验收

| 项 | 结果 |
|---|---|
| 构建 | `mvn clean package -DskipTests` ✅ |
| 仓库无明文密钥 | `grep -rn "root123456\|305c8974\|ebcfa771\|access_token="` 仅剩 `sh/app.env`（未入库）与文档示例 ✅ |
| 接口 | `GET /api/admin/env` 返回两组、凭据脱敏、来源正确 ✅ |
| 页面 | 只读弹窗可开可关、遮罩点击关闭、无控制台报错 ✅ |
| 发布 | jar 上线，`/api/version` = 1.6.1，探活 200，启动通知已发 ✅ |
