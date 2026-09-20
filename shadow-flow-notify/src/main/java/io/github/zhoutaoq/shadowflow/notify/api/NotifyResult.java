package io.github.zhoutaoq.shadowflow.notify.api;

/**
 * 一次投递的结果。
 *
 * <p>刻意做成「成功/失败 + 一句话原因」而不是抛异常：通知不是账本，
 * 发不出去不该把调用方的主流程带崩（注册、保存配置这些业务必须照常成功）。
 * 失败原因只用于调用方记日志，不面向最终用户。
 */
public final class NotifyResult {

    private static final NotifyResult OK = new NotifyResult(true, "ok");

    private final boolean ok;
    private final String detail;

    private NotifyResult(boolean ok, String detail) {
        this.ok = ok;
        this.detail = detail;
    }

    public static NotifyResult ok() {
        return OK;
    }

    /** 失败，{@code detail} 是给日志看的原因（不含密钥等敏感值） */
    public static NotifyResult fail(String detail) {
        return new NotifyResult(false, detail == null ? "fail" : detail);
    }

    public boolean isOk() {
        return ok;
    }

    public String detail() {
        return detail;
    }

    @Override
    public String toString() {
        return ok ? "ok" : "fail: " + detail;
    }
}
