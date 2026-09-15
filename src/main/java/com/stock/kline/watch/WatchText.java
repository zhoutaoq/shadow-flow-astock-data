package com.stock.kline.watch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 盯盘相关推送的通用文案/取值小工具，零状态。
 *
 * <p>feat3 的启动通知、变更通知、关键时点快报都需要拼文本、读本机 IP，
 * 把它们抽到这里避免各推送类重复实现。
 *
 * <p>IP 有两个口径，别混用：
 * <ul>
 *   <li>{@link #localIp()} —— 网卡上的局域网地址（10/172.16-31/192.168），只在本机/内网有意义；</li>
 *   <li>{@link #publicIp()} —— 出口公网 IPv4，由外部回显服务探得；钉钉消息前缀用它区分是哪台机器发的。</li>
 * </ul>
 */
final class WatchText {

    private static final Logger log = LoggerFactory.getLogger(WatchText.class);

    private WatchText() {
    }

    /** double → 无尾零文本：1.0 → "1"，0.5 → "0.5" */
    static String pct(double d) {
        return new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
    }

    /** 涨跌幅带符号：null → "—"，正值加 "+"；比率已经两位小数，这里只加符号 */
    static String signed(BigDecimal v) {
        if (v == null) {
            return "—";
        }
        return (v.signum() > 0 ? "+" : "") + v.toPlainString() + "%";
    }

    /** 现价文本：null → "—"，股价一律两位小数 */
    static String price(BigDecimal v) {
        return v == null ? "—" : v.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString();
    }

    /** 本机第一个可用的局域网 IPv4；拿不到给 "未知"。优先内网地址（10/172.16-31/192.168），其次任意 IPv4 */
    static String localIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs != null && addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a.isLoopbackAddress() || !(a instanceof Inet4Address)) {
                        continue;
                    }
                    String ip = a.getHostAddress();
                    if (ip.startsWith("169.254.")) {
                        continue; // 链路本地，不是真实内网
                    }
                    if (isPrivateIpv4(ip)) {
                        return ip;
                    }
                    if (fallback == null) {
                        fallback = ip;
                    }
                }
            }
            if (fallback != null) {
                return fallback;
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "未知";
        }
    }

    // ------------------------------------------------------------------
    // 公网 IP：出口地址，靠外部回显服务探（云主机上公网 IP 只做 NAT，网卡里根本没有它）
    // ------------------------------------------------------------------

    /**
     * 公网 IP 回显服务，按顺序试，第一个给出合法公网 IPv4 的胜出。
     *
     * <p>顺序按"在中国的可达性与响应速度"排：前两个是国内的动态域名/镜像站服务（毫秒级），
     * 后两个是国外公共回显；最后留一个纯 HTTP 的，防目标机 TLS 根证书不全时全军覆没。
     * 各服务返回的文本格式不一（纯 IP / {@code Current IP Address: 1.2.3.4} /
     * {@code 当前 IP：1.2.3.4 来自于：…}），所以不按格式解析，统一用正则捞第一个合法公网 IPv4。
     */
    private static final String[] PUBLIC_IP_URLS = {
            "https://ddns.oray.com/checkip",
            "https://api-ipv4.ip.sb/ip",
            "https://ifconfig.me/ip",
            "http://ip.3322.net",
    };

    /** 部分回显服务（如 ip.sb）会拒绝 Java 默认的 User-Agent，伪装成浏览器才放行 */
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 单次探测超时：连不上/读不到就换下一个源，别把告警线程拖住 */
    private static final int PROBE_TIMEOUT_MS = 2000;

    /** 所有源都失败后的重试间隔：避免每条告警都白等一轮超时 */
    private static final long RETRY_AFTER_MS = 10 * 60 * 1000L;

    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3})");

    private static final Object PUBLIC_IP_LOCK = new Object();

    /** 探测成功的公网 IP；进程内有效（公网 IP 不会变）。失败不写这里，靠 {@link #nextProbeAt} 退避 */
    private static volatile String cachedPublicIp;

    /** 上一次探测全部失败的时刻 + 退避间隔；到点之前不再探测，直接返回"没有" */
    private static volatile long nextProbeAt;

    /**
     * 后台预热一次公网 IP 解析，让第一条告警不必同步等网络。
     *
     * <p>幂等：已解析出结果、或还在退避窗口内都直接返回。守护线程，不挡应用退出。
     */
    static void warmUpPublicIp() {
        if (cachedPublicIp != null || System.currentTimeMillis() < nextProbeAt) {
            return;
        }
        Thread t = new Thread(WatchText::publicIp, "watch-public-ip");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 本机出口<b>公网</b> IPv4（不是 10/172.16-31/192.168 内网地址，也不是 127.0.0.1）。
     *
     * <p>探到的结果进程内长期缓存；探不到返回 <code>null</code>，由调用方降级
     * （例如消息前缀退回不带 IP 的老样式），且 10 分钟内不再重试。
     *
     * @return 形如 <code>210.22.131.83</code> 的公网 IPv4；没探到则 null
     */
    static String publicIp() {
        String cached = cachedPublicIp;
        if (cached != null) {
            return cached;
        }
        if (System.currentTimeMillis() < nextProbeAt) {
            return null;
        }
        synchronized (PUBLIC_IP_LOCK) {
            if (cachedPublicIp == null && System.currentTimeMillis() >= nextProbeAt) {
                String resolved = resolvePublicIp();
                if (resolved != null) {
                    cachedPublicIp = resolved;
                } else {
                    nextProbeAt = System.currentTimeMillis() + RETRY_AFTER_MS;
                }
            }
            return cachedPublicIp;
        }
    }

    /** 依次试各个回显服务，返回第一个合法公网 IPv4；全试完都没结果返回 null */
    private static String resolvePublicIp() {
        for (String url : PUBLIC_IP_URLS) {
            try {
                String ip = firstPublicIpv4(httpGet(url));
                if (ip != null) {
                    log.info("[watch] 公网 IP 探测成功：{}（来源 {}）", ip, url);
                    return ip;
                }
                log.warn("[watch] 公网 IP 探测无有效结果，换下一个源：{}", url);
            } catch (Exception e) {
                log.warn("[watch] 公网 IP 探测失败，换下一个源：{} → {}", url, e.getMessage());
            }
        }
        log.warn("[watch] 所有公网 IP 探测源都不可用，{} 分钟内不再重试（钉钉消息前缀退回不带 IP）",
                RETRY_AFTER_MS / 60000);
        return null;
    }

    /** 文本里第一个<b>合法公网</b> IPv4：内网/环回/保留地址一律跳过，找不到给 null */
    private static String firstPublicIpv4(String body) {
        if (body == null) {
            return null;
        }
        Matcher m = IPV4.matcher(body);
        while (m.find()) {
            if (isPublicIpv4(m.group(1))) {
                return m.group(1);
            }
        }
        return null;
    }

    private static String httpGet(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(PROBE_TIMEOUT_MS);
        conn.setReadTimeout(PROBE_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept", "text/plain,*/*");
        try {
            int status = conn.getResponseCode();
            if (status >= 400) {
                throw new IOException("HTTP " + status);
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int n;
                // 回显服务的响应都很短，读满 4KB 就够（也防它返回一个巨大的页面）
                while (out.size() < 4096 && (n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 是否"能对外的公网 IPv4"：内网 / 环回 / 链路本地 / CGNAT / 组播 / 各类保留段全部排除。
     *
     * <p>探测服务给的答案一定得过这一关 —— 上游劫持、企业代理、认证明文页都可能返回
     * {@code 192.168.x.x} 甚至 {@code 127.0.0.1}，直接拿来做消息前缀毫无意义。
     */
    private static boolean isPublicIpv4(String ip) {
        String[] p = ip.split("\\.");
        if (p.length != 4) {
            return false;
        }
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            if (p[i].isEmpty() || p[i].length() > 3) {
                return false;
            }
            n[i] = Integer.parseInt(p[i]);
            if (n[i] > 255) {
                return false;
            }
        }
        if (n[0] == 0 || n[0] == 10 || n[0] == 127) {
            return false; // 本网络 / 内网 / 环回
        }
        if (n[0] == 100 && n[1] >= 64 && n[1] <= 127) {
            return false; // 运营商 CGNAT（100.64.0.0/10）
        }
        if (n[0] == 169 && n[1] == 254) {
            return false; // 链路本地
        }
        if (n[0] == 172 && n[1] >= 16 && n[1] <= 31) {
            return false; // 内网
        }
        if (n[0] == 192 && n[1] == 168) {
            return false; // 内网
        }
        if (n[0] == 192 && n[1] == 0 && n[2] == 0) {
            return false; // 192.0.0.0/24 IETF 保留
        }
        if (n[0] == 192 && n[1] == 0 && n[2] == 2) {
            return false; // TEST-NET-1
        }
        if (n[0] == 198 && (n[1] == 18 || n[1] == 19)) {
            return false; // 基准测试 198.18.0.0/15
        }
        if (n[0] == 198 && n[1] == 51 && n[2] == 100) {
            return false; // TEST-NET-2
        }
        if (n[0] == 203 && n[1] == 0 && n[2] == 113) {
            return false; // TEST-NET-3
        }
        return n[0] < 224; // 224 起是组播 / 保留
    }

    /** 判断 IPv4 是否内网地址：10.* / 172.16~31.* / 192.168.* */
    private static boolean isPrivateIpv4(String ip) {
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) {
            return true;
        }
        if (ip.startsWith("172.")) {
            try {
                int second = Integer.parseInt(ip.substring(4, ip.indexOf('.', 4)));
                return second >= 16 && second <= 31;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }
}
