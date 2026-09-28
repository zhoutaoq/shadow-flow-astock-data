package io.github.zhoutaoq.shadowflow.biz.support;

import io.github.zhoutaoq.shadowflow.biz.repo.SysConfigRepository;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 本站对外可点开的基址（{@code http://主机:端口}），给「站内相对路径要在站外打开」的场合用。
 *
 * <p>为什么需要它：跳转目标里既有站外平台（百度股市通、雪球），也有<b>站内页面</b>
 * （本系统 K 线，配置里存的是 {@code /pages/chart.html?code={code}} 这种相对路径）。
 * 相对路径在钉钉里点不开 —— 消息是别人在手机上、在钉钉客户端里看的，
 * 那里没有「当前站点」这个概念。要让它可点，必须补成绝对地址。
 *
 * <p><b>地址只能来自配置，不能靠探测</b>：曾经这里用出口公网 IP 兜底，
 * 结果拼出来的是运营商 NAT 池地址（形如 {@code http://183.195.98.163}），
 * 本机根本不监听它 —— 钉钉里点开永远是「打不开」。猜错的地址比不给地址更糟：
 * 不给地址时调用方会顺延到站外平台，消息照样能跳到一个真能打开的地方。
 * 所以：<b>没配就返回 null</b>，让调用方走兜底。
 *
 * <p>取值优先级：超管在「系统配置」页填的站点对外地址 → 配置文件的 {@code app.site-base-url}。
 */
@Component
public class SiteBase {

    /** 配置落点：系统级 + 场景 site */
    private static final String SCENE = "site";
    private static final String KEY = "base-url";
    /** 配置文件里的键（application.yml / 系统属性） */
    private static final String PROP = "app.stock-link.site-base-url";

    private final Environment env;
    private final SysConfigRepository configs;

    public SiteBase(Environment env, SysConfigRepository configs) {
        this.env = env;
        this.configs = configs;
    }

    /**
     * @return 形如 {@code http://1.2.3.4:8080} 的基址（不带结尾斜杠）；没配置或填得不合法时返回 null
     */
    public String base() {
        String saved = configs == null ? null : configs.get(SCENE, KEY);
        if (hasText(saved)) {
            return normalize(saved);
        }
        String fromFile = env == null ? null : env.getProperty(PROP);
        if (hasText(fromFile)) {
            return normalize(fromFile);
        }
        return null;
    }

    /**
     * 规整成「协议 + 主机 + 可选端口」：去首尾空格与结尾斜杠，缺协议时补 {@code http://}。
     *
     * @return 规整后的地址；值里带路径（{@code http://host/app}）或看着不像主机时返回 null ——
     *         基址只该是站点根，带路径会把站内相对路径拼歪。
     */
    public static String normalize(String raw) {
        String v = raw == null ? "" : raw.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        if (v.isEmpty()) {
            return null;
        }
        if (!v.startsWith("http://") && !v.startsWith("https://")) {
            v = "http://" + v;
        }
        String rest = v.substring(v.indexOf("://") + 3);
        if (rest.isEmpty() || rest.contains("/") || rest.contains("?")) {
            return null;
        }
        return v;
    }

    private static boolean hasText(String v) {
        return v != null && !v.trim().isEmpty();
    }
}
