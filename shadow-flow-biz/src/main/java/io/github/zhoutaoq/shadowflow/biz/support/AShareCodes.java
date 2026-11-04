package io.github.zhoutaoq.shadowflow.biz.support;

/**
 * 沪深A股代码口径：6 位数字且以 60 / 68 / 00 / 30 开头。
 *
 * <p>本系统只关注沪深A股，「市场」不作为业务维度对外暴露，
 * 名录载入、JSON 导入、候选过滤统一走这一个判断，避免各处各写一套前缀。
 */
public final class AShareCodes {

    /** 沪主板 60、科创板 68、深主板 00、创业板 30 */
    private static final String[] PREFIXES = {"60", "68", "00", "30"};

    private AShareCodes() {
    }

    /** 是沪深A股代码返回 true；5 位港股、1/5 开头基金债券、9 开头北交所都返回 false */
    public static boolean isAShare(String code) {
        if (code == null || code.length() != 6) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            if (!Character.isDigit(code.charAt(i))) {
                return false;
            }
        }
        for (String prefix : PREFIXES) {
            if (code.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 能不能进「账户」和「名录」——比 {@link #isAShare} 宽，因为要持仓的不只是股票。
     *
     * <p>收两类：6 位纯数字（A 股、ETF/LOF、北交所），5 位纯数字（港股）。
     * 1/5 开头的 ETF 和 5 位港股以前被 {@link #isAShare} 挡在名录外，
     * 结果账户里根本选不到标的，加不了持仓。
     *
     * <p>资讯板块和首页热门股仍然只认 A 股（用 {@link #isAShare}），那两处是「热点」口径，
     * 混进 ETF 和港股没有意义 —— 所以这里是新开一个判断，不是改旧的。
     */
    public static boolean isTradable(String code) {
        if (code == null) {
            return false;
        }
        int len = code.length();
        if (len != 5 && len != 6) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (!Character.isDigit(code.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
