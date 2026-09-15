-- ============================================================
-- 数据源：waizao（歪枣网 行情供应商）
-- 表命名规范：waizao_xxx
-- ============================================================

-- 通用配置表：按「归属用户 + 场景 + 键」存放运行期可变的变量（目前是 waizao 的 token）
-- 接口地址、超时、并发度、启用开关等稳定参数仍写在 application.yml，不进这张表
-- owner_id = 0 表示系统级：未登录请求、以及该用户没单独配过时的兜底
CREATE TABLE IF NOT EXISTS waizao_sys_config (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    owner_id     BIGINT       NOT NULL DEFAULT 0      COMMENT '归属用户 id；0 = 系统级兜底',
    config_scene VARCHAR(64)  NOT NULL                COMMENT '场景/分组，如 waizao',
    config_key   VARCHAR(64)  NOT NULL                COMMENT '配置键，如 token',
    config_value TEXT         NULL                    COMMENT '配置值（令牌类会频繁更换，页面保存后即时生效）',
    remark       VARCHAR(255) NULL                    COMMENT '备注',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_waizao_sys_config_owner_scene_key (owner_id, config_scene, config_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '通用配置表（各场景可变参数）';

-- 股票列表（来源 resources/stock-list.json）
CREATE TABLE IF NOT EXISTS waizao_stock_list (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    stock_code   VARCHAR(16)  NOT NULL                COMMENT '股票代码',
    stock_name   VARCHAR(128) NOT NULL                COMMENT '股票名称',
    market       VARCHAR(16)  NOT NULL DEFAULT 'stock' COMMENT '市场/品种: stock-沪深A股, hk-港股 等（对应接口 symbol 参数）',
    enabled      TINYINT      NOT NULL DEFAULT 1      COMMENT '是否启用: 1-启用 0-停用',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_waizao_stock_list_code_market (stock_code, market),
    KEY idx_waizao_stock_list_name (stock_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'waizao-股票列表';

-- 接口调用日志（只记录状态码，不记录响应体）
CREATE TABLE IF NOT EXISTS waizao_api_call_log (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    scene          VARCHAR(16)  NOT NULL DEFAULT 'batch' COMMENT '调用场景: dev-调试页 batch-批处理抓取',
    stock_code     VARCHAR(16)  NOT NULL DEFAULT ''     COMMENT '股票代码（接口 code 参数）',
    market         VARCHAR(16)  NOT NULL DEFAULT ''     COMMENT '市场/品种（接口 symbol 参数）',
    req_start_date DATE         NULL                    COMMENT '请求日期参数-起（接口 startDate）',
    req_end_date   DATE         NULL                    COMMENT '请求日期参数-止（接口 endDate）',
    request_time   DATETIME(3)  NOT NULL                COMMENT '请求发生时间',
    http_status    INT          NOT NULL DEFAULT 0      COMMENT 'HTTP 响应状态码，0 表示未拿到响应（超时/连接失败）',
    cost_ms        INT          NOT NULL DEFAULT 0      COMMENT '请求耗时（毫秒）',
    row_count      INT          NOT NULL DEFAULT 0      COMMENT '解析出的K线行数',
    success        TINYINT      NOT NULL DEFAULT 0      COMMENT '是否成功: 1-成功 0-失败',
    error_msg      VARCHAR(512) NULL                    COMMENT '失败原因（响应体不入表）',
    PRIMARY KEY (id),
    KEY idx_waizao_api_log_time (request_time),
    KEY idx_waizao_api_log_scene_time (scene, request_time),
    KEY idx_waizao_api_log_code_time (stock_code, request_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'waizao-接口调用日志';

-- 股票日K线数据
-- 主键即业务唯一键：stock_code + trade_date（同一代码同一交易日只有一条，重复抓取覆盖更新）
CREATE TABLE IF NOT EXISTS waizao_stock_kline_daily (
    stock_code  VARCHAR(16)     NOT NULL                COMMENT '股票代码',
    market      VARCHAR(16)     NOT NULL DEFAULT 'stock' COMMENT '市场/品种（对应接口 symbol 参数）',
    trade_date  DATE            NOT NULL                COMMENT '交易日',
    open_price  DECIMAL(18, 4)  NULL                    COMMENT '开盘价 o',
    close_price DECIMAL(18, 4)  NULL                    COMMENT '收盘价 c',
    high_price  DECIMAL(18, 4)  NULL                    COMMENT '最高价 h',
    low_price   DECIMAL(18, 4)  NULL                    COMMENT '最低价 l',
    volume      BIGINT          NULL                    COMMENT '成交量（股）v',
    amount      DECIMAL(24, 4)  NULL                    COMMENT '成交额（元）a',
    pre_close   DECIMAL(18, 4)  NULL                    COMMENT '前收盘价 pc',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (stock_code, trade_date),
    KEY idx_waizao_kline_trade_date (trade_date),
    KEY idx_waizao_kline_market (market)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'waizao-股票日K线数据';

-- 系统用户（v1.3 SaaS 基础）
-- 用户名固定为邮箱；口令只存 salt + SHA-256 哈希，任何接口都不回传这两个字段
CREATE TABLE IF NOT EXISTS waizao_user (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username         VARCHAR(128) NOT NULL                COMMENT '用户名（邮箱），唯一',
    password_hash    VARCHAR(128) NOT NULL                COMMENT '口令哈希 SHA-256(salt:明文) hex',
    password_salt    VARCHAR(64)  NOT NULL                COMMENT '口令随机盐 hex，每个用户独立',
    role             VARCHAR(16)  NOT NULL DEFAULT 'user' COMMENT '角色: admin-超管 user-普通用户',
    enabled          TINYINT      NOT NULL DEFAULT 1      COMMENT '是否启用: 1-启用 0-停用（停用后登录态立即失效）',
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    last_login_time  DATETIME     NULL                    COMMENT '最近一次登录时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_waizao_user_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '系统用户（SaaS 账号）';

-- ============================================================
-- 我的账户（v1.5.1 建立 / v1.5.2 改为账户维度）
-- 一个用户可以有多本账；持仓挂到账户上（account_id），一本账里一只标的只能有一条
-- 收益类字段（现价/市值/当日收益/总收益）一律实时算，不入表
-- 老库升级由 DbAccountRepository.init() 里的幂等 ALTER 完成
-- ============================================================

-- 账户：名称 + 期初市值 + 可用金额
CREATE TABLE IF NOT EXISTS waizao_account (
    id           BIGINT         NOT NULL AUTO_INCREMENT COMMENT '账户 id（主键）',
    user_id      BIGINT         NOT NULL                COMMENT '归属用户 id',
    account_name VARCHAR(64)    NOT NULL DEFAULT '默认账户' COMMENT '账户名称，同一用户下不重名',
    init_value   DECIMAL(20, 4) NOT NULL DEFAULT 0      COMMENT '账户期初市值（元）',
    cash         DECIMAL(20, 4) NOT NULL DEFAULT 0      COMMENT '可用金额（元）：买入扣减、卖出加回',
    remark       VARCHAR(255)   NULL                    COMMENT '备注',
    create_time  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_waizao_account_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'waizao-我的账户';

-- 持仓：账户 + 标的 + 成本价 + 持仓数量
CREATE TABLE IF NOT EXISTS waizao_position (
    id          BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT         NOT NULL                COMMENT '归属用户 id',
    account_id  BIGINT         NOT NULL DEFAULT 0      COMMENT '归属账户 id',
    stock_code  VARCHAR(16)    NOT NULL                COMMENT '标的代码',
    stock_name  VARCHAR(128)   NOT NULL DEFAULT ''     COMMENT '标的名称',
    cost_price  DECIMAL(20, 4) NOT NULL DEFAULT 0      COMMENT '成本价（元）',
    quantity    INT            NOT NULL DEFAULT 0      COMMENT '持仓数量（股）',
    create_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_waizao_position_account_code (account_id, stock_code),
    KEY idx_waizao_position_user (user_id),
    KEY idx_waizao_position_account (account_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'waizao-持仓配置';

-- ============================================================
-- 回测（数据源固定：供应商 waizao + 日K表 waizao_stock_kline_daily）
-- 费率一律以小数存储：页面按 % 录入，0.01% 存 0.0001
-- ============================================================

-- 回测主表：一次回测一条，策略以 JSON 存（策略种类扩展不动表结构）
CREATE TABLE IF NOT EXISTS waizao_backtest (
    id           BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    stock_code   VARCHAR(16)    NOT NULL                COMMENT '股票代码',
    market       VARCHAR(16)    NOT NULL DEFAULT 'stock' COMMENT '市场/品种',
    start_date   DATE           NOT NULL                COMMENT '回测起始日',
    end_date     DATE           NOT NULL                COMMENT '回测结束日',
    init_amount  DECIMAL(20, 4) NOT NULL                COMMENT '账户起始金额',
    fee_rate     DECIMAL(12, 8) NOT NULL DEFAULT 0.00010000 COMMENT '摩擦成本费率(小数)，0.01% = 0.0001',
    buy_rule     TEXT           NOT NULL                COMMENT '买入策略组 JSON: {logic:"or|and", conditions:[...]}',
    sell_rule    TEXT           NOT NULL                COMMENT '卖出策略组 JSON',
    final_amount DECIMAL(20, 4) NULL                    COMMENT '期末账户总金额',
    total_profit DECIMAL(20, 4) NULL                    COMMENT '盈亏金额（期末总额 - 起始金额）',
    profit_ratio DECIMAL(12, 8) NULL                    COMMENT '盈亏比例(小数)',
    trade_count  INT            NOT NULL DEFAULT 0      COMMENT '成交笔数（买入 + 卖出）',
    trade_days   INT            NOT NULL DEFAULT 0      COMMENT '回测区间内交易日天数',
    create_time  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_waizao_backtest_code_time (stock_code, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'waizao-回测主表';

-- 回测每日账户快照（回测区间内每个交易日一条）
CREATE TABLE IF NOT EXISTS waizao_backtest_daily (
    id              BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    backtest_id     BIGINT         NOT NULL                COMMENT '回测 id',
    trade_date      DATE           NOT NULL                COMMENT '交易日',
    close_price     DECIMAL(18, 4) NULL                   COMMENT '当日收盘价',
    cash            DECIMAL(20, 4) NOT NULL DEFAULT 0     COMMENT '当日收盘后现金',
    position_shares INT            NOT NULL DEFAULT 0      COMMENT '当日收盘后持仓股数',
    position_value  DECIMAL(20, 4) NOT NULL DEFAULT 0      COMMENT '当日收盘后持仓市值',
    total_amount    DECIMAL(20, 4) NOT NULL DEFAULT 0      COMMENT '账户总金额 = 现金 + 持仓市值',
    profit_amount   DECIMAL(20, 4) NOT NULL DEFAULT 0      COMMENT '盈亏金额 = 账户总额 - 起始金额',
    profit_ratio    DECIMAL(12, 8) NOT NULL DEFAULT 0      COMMENT '盈亏比例(小数)',
    PRIMARY KEY (id),
    UNIQUE KEY uk_waizao_bt_daily (backtest_id, trade_date),
    KEY idx_waizao_bt_daily_bt (backtest_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'waizao-回测每日账户快照';

-- 回测交易明细（每次成交一条）
CREATE TABLE IF NOT EXISTS waizao_backtest_trade (
    id          BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    backtest_id BIGINT         NOT NULL                COMMENT '回测 id',
    trade_date  DATE           NOT NULL                COMMENT '成交日（信号确认日，按收盘价成交）',
    action      VARCHAR(8)     NOT NULL                COMMENT 'buy-买入 sell-卖出',
    shares      INT            NOT NULL                COMMENT '成交股数（100 的整数倍）',
    price       DECIMAL(18, 4) NOT NULL                COMMENT '成交价（信号日收盘价）',
    turnover    DECIMAL(20, 4) NOT NULL                COMMENT '成交额 = 股数 × 成交价',
    fee         DECIMAL(20, 4) NOT NULL DEFAULT 0      COMMENT '摩擦成本 = 成交额 × 费率',
    cash_after  DECIMAL(20, 4) NOT NULL DEFAULT 0      COMMENT '成交后现金余额',
    PRIMARY KEY (id),
    KEY idx_waizao_bt_trade_bt (backtest_id, trade_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'waizao-回测交易明细';
