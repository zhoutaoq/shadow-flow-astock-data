package io.github.zhoutaoq.shadowflow.biz.watch;

/**
 * 盯盘名单里的一行：名称 + 识别出来的代码 + 新浪的带市场前缀代码。
 *
 * <p>新浪用 <code>sh600000</code> / <code>sz000001</code> 这样的前缀区分交易所，
 * 沪深A股的规则是 60 / 68 开头归上证，00 / 30 开头归深证。
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

    /** 沪深A股的代码 → 新浪市场前缀：60/68 上证，00/30 深证 */
    public static String prefixOf(String code) {
        if (code == null || code.length() != 6) {
            return "sh";
        }
        char first = code.charAt(0);
        // 60 沪主板、68 科创板归上证；00 深主板、30 创业板归深证
        if (first == '6') {
            return "sh";
        }
        if (first == '0' || first == '3') {
            return "sz";
        }
        return "sh";
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
