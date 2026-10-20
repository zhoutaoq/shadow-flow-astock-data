package io.github.zhoutaoq.shadowflow.biz.migrate;

/**
 * 可迁移的数据维度。
 *
 * <p><b>顺序即依赖顺序</b>：导入必须按 {@link #getOrder()} 从小到大处理 ——
 * 持仓要挂在账户上、流水要挂在账户上、回测的每日快照要挂在回测主记录上，
 * 先导子表就会出现一堆指不到父行的孤儿数据。导出同样按这个顺序写进 zip，
 * 导出的包本身就是一份「可以照着顺序重放」的数据集。
 *
 * <p>维度是<b>产品语义</b>而不是物理表：回测在三张表里（主记录 + 每日快照 + 成交明细），
 * 对超管只呈现为「回测记录」一项；账户 / 持仓 / 操作记录虽然同在一个文件里，
 * 也拆成三项 —— 有人只想搬账户和持仓、不想要历史流水。
 */
public enum MigrateDimension {

    /** 账号：含口令哈希与 GitHub 绑定，导入后原密码仍可登录 */
    USERS("users", "用户账号", 10,
            "邮箱、角色、口令哈希与 GitHub 绑定；导入后原账号仍用原密码登录"),

    /** 系统配置：第三方对接凭据、菜单、股票跳转等 */
    SYS_CONFIG("sys_config", "系统配置", 20,
            "第三方对接、菜单名称顺序、股票跳转等配置行（含行情服务令牌）"),

    /** 股票名录 */
    STOCK_LIST("stock_list", "股票列表", 30,
            "沪深 A 股名录：代码、名称、是否启用"),

    /** 日 K 线：只有数据库模式才有存量 */
    KLINE("kline", "日K线", 40,
            "日K行情。仅数据库模式落盘；文件模式按需实时取数，没有存量可搬"),

    /** 账户 */
    ACCOUNT("account", "账户", 50,
            "每本账的名称、期初市值、可用金额与当日已实现盈亏"),

    /** 持仓 */
    POSITION("position", "持仓", 60,
            "账户下的持仓：成本价、数量、可用数量、建仓日与当天买入池。依赖「账户」"),

    /** 买卖流水 */
    TRADE_RECORD("trade_record", "操作记录", 70,
            "买入 / 卖出流水，含成交价、数量与操作后余额。依赖「账户」"),

    /** 账户分钟走势 */
    ACCOUNT_TREND("account_trend", "账户走势", 80,
            "账户分钟级市值快照（只保留最近 45 天）。依赖「账户」"),

    /** 回测记录（主记录 + 每日快照 + 成交明细） */
    BACKTEST("backtest", "回测记录", 90,
            "回测主记录、每日账户快照与成交明细，随包一起搬");

    private final String key;
    private final String label;
    private final int order;
    private final String description;

    MigrateDimension(String key, String label, int order, String description) {
        this.key = key;
        this.label = label;
        this.order = order;
        this.description = description;
    }

    public String getKey() {
        return key;
    }

    public String getLabel() {
        return label;
    }

    public int getOrder() {
        return order;
    }

    public String getDescription() {
        return description;
    }

    /** 按 key 找维度；未知 key 返回 null（页面提交了脏数据时按忽略处理） */
    public static MigrateDimension of(String key) {
        if (key == null) {
            return null;
        }
        String k = key.trim();
        for (MigrateDimension d : values()) {
            if (d.key.equals(k)) {
                return d;
            }
        }
        return null;
    }
}
