package io.github.zhoutaoq.shadowflow.biz.watch;

/**
 * 盯盘名单里的一行：名称 + 识别出来的代码 + 新浪的带市场前缀代码。
 *
 * <p>新浪用 <code>sh600000</code> / <code>sz000001</code> 这样的前缀区分交易所，
 * 港股是 <code>hk00700</code>（5 位代码）。
 */
public class WatchTarget {

    private final String name;
    private final String code;
    private final String symbol;

    public WatchTarget(String name, String code) {
        this.name = name == null ? code : name;
        this.code = code;
        this.symbol = prefixOf(code) + code;
    }

    /**
     * 代码 → 新浪市场前缀。
     *
     * <p>品种比想象的多，按前缀分：
     * <pre>
     *   6 开头   沪主板 / 科创板        sh
     *   5 开头   沪市 ETF / LOF         sh
     *   0 / 3    深主板 / 创业板         sz
     *   1 开头   深市 ETF / LOF         sz   ← 这条以前漏了，159915 被当成沪市
     *   9 开头   北交所                 bj
     *   5 位    港股                   hk
     * </pre>
     * 港股 5 位这一条必须放最前面判断：00700 只有 5 位，落到下面任何一个分支都是错的。
     */
    public static String prefixOf(String code) {
        if (code == null) {
            return "sh";
        }
        if (code.length() == 5 && code.chars().allMatch(Character::isDigit)) {
            return "hk";
        }
        if (code.length() != 6) {
            return "sh";
        }
        char first = code.charAt(0);
        if (first == '0' || first == '1' || first == '3') {
            return "sz";
        }
        if (first == '9') {
            return "bj";
        }
        return "sh";
    }

    /** 港股：报价字段与 A 股完全不是一套，解析时要分开走 */
    public boolean isHk() {
        return symbol != null && symbol.startsWith("hk");
    }

    public String getName() {
        return name;
    }

    public String getCode() {
        return code;
    }

    public String getSymbol() {
        return symbol;
    }

    @Override
    public String toString() {
        return name + "(" + code + ")";
    }
}
