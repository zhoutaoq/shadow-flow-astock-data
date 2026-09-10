package com.stock.kline.watch;

import java.math.BigDecimal;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * 盯盘相关推送的通用文案/取值小工具，零状态。
 *
 * <p>feat3 的启动通知、变更通知、关键时点快报都需要拼文本、读本机 IP，
 * 把它们抽到这里避免各推送类重复实现。
 */
final class WatchText {

    private WatchText() {
    }

    /** double → 无尾零文本：1.0 → "1"，0.5 → "0.5" */
    static String pct(double d) {
        return new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
    }

    /** 涨跌幅带符号：null → "—"，正值加 "+" */
    static String signed(BigDecimal v) {
        if (v == null) {
            return "—";
        }
        return (v.signum() > 0 ? "+" : "") + v.toPlainString() + "%";
    }

    /** 现价文本：null → "—" */
    static String price(BigDecimal v) {
        return v == null ? "—" : v.toPlainString();
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
