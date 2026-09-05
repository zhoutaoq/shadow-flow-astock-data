package com.stock.kline.support;

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
}
