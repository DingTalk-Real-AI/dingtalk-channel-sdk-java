package io.github.typefield.dingtalk.channel.safety;

import io.github.typefield.dingtalk.channel.ChannelError;

import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;

/**
 * SSRF 防护：拦截指向内网/私有地址的下载请求。
 */
public final class SsrfGuard {

    private SsrfGuard() {}

    public static void assertPublicUrl(String url) {
        assertPublicUrl(url, null);
    }

    /** 白名单匹配：精确主机名或 *.suffix 通配。 */
    static boolean hostAllowed(String host, java.util.List<String> allowlist) {
        if (allowlist == null || allowlist.isEmpty() || host == null) {
            return false;
        }
        String h = host.toLowerCase();
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        for (String entry : allowlist) {
            String e = entry.toLowerCase();
            if (e.endsWith(".")) {
                e = e.substring(0, e.length() - 1);
            }
            if (h.equals(e)) {
                return true;
            }
            if (e.startsWith("*.") && h.endsWith(e.substring(1))) {
                return true;
            }
        }
        return false;
    }

    public static void assertPublicUrl(String url, java.util.List<String> allowlist) {
        URL parsed;
        try {
            parsed = new URL(url);
        } catch (Exception e) {
            throw new ChannelError(ChannelError.ErrorCode.SSRF_BLOCKED, "invalid URL: " + url, e);
        }

        String proto = parsed.getProtocol().toLowerCase();
        if (!proto.equals("http") && !proto.equals("https")) {
            throw new ChannelError(ChannelError.ErrorCode.SSRF_BLOCKED, "non-http scheme: " + proto);
        }

        String host = parsed.getHost();
        if (host == null || host.isEmpty()) {
            throw new ChannelError(ChannelError.ErrorCode.SSRF_BLOCKED, "empty host in URL: " + url);
        }

        // 白名单豁免（企业内网 CDN/专有云场景）
        if (hostAllowed(host, allowlist)) {
            return;
        }

        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new ChannelError(ChannelError.ErrorCode.SSRF_BLOCKED, "cannot resolve host: " + host, e);
        }

        for (InetAddress addr : addrs) {
            if (isBlocked(addr)) {
                throw new ChannelError(ChannelError.ErrorCode.SSRF_BLOCKED,
                        "blocked address: " + addr.getHostAddress());
            }
        }
    }

    private static boolean isBlocked(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        if (bytes.length == 4) {
            return isBlockedIPv4(bytes);
        } else if (bytes.length == 16) {
            return isBlockedIPv6(bytes, addr);
        }
        return false;
    }

    private static boolean isBlockedIPv4(byte[] b) {
        int o1 = b[0] & 0xFF;
        int o2 = b[1] & 0xFF;
        int o3 = b[2] & 0xFF;

        if (o1 == 0) return true;                         // 0.0.0.0/8
        if (o1 == 10) return true;                        // 10.0.0.0/8
        if (o1 == 127) return true;                       // 127.0.0.0/8
        if (o1 == 169 && o2 == 254) return true;          // 169.254.0.0/16
        if (o1 == 172 && o2 >= 16 && o2 <= 31) return true; // 172.16.0.0/12
        if (o1 == 192 && o2 == 168) return true;          // 192.168.0.0/16
        if (o1 == 100 && o2 >= 64 && o2 <= 127) return true; // 100.64.0.0/10
        if (o1 == 192 && o2 == 0 && o3 == 0) return true; // 192.0.0.0/24
        if (o1 == 192 && o2 == 0 && o3 == 2) return true; // 192.0.2.0/24
        if (o1 == 198 && o2 >= 18 && o2 <= 19) return true; // 198.18.0.0/15
        if (o1 == 198 && o2 == 51 && o3 == 100) return true; // 198.51.100.0/24
        if (o1 == 203 && o2 == 0 && o3 == 113) return true; // 203.0.113.0/24
        if (o1 >= 224 && o1 <= 239) return true;          // 224.0.0.0/4
        if (o1 >= 240) return true;                       // 240.0.0.0/4

        return false;
    }

    private static boolean isBlockedIPv6(byte[] b, InetAddress addr) {
        if (addr.isLoopbackAddress()) return true;
        if (addr.isAnyLocalAddress()) return true;
        if (addr.isLinkLocalAddress()) return true;
        if (addr.isSiteLocalAddress()) return true;
        if (addr.isMulticastAddress()) return true;

        if ((b[0] & 0xFE) == 0xFC) return true;           // fc00::/7 (fc/fd)
        if ((b[0] & 0xFF) == 0xFF) return true;           // ff00::/8

        if (b[10] == (byte) 0xFF && b[11] == (byte) 0xFF) {
            byte[] v4 = new byte[4];
            System.arraycopy(b, 12, v4, 0, 4);
            return isBlockedIPv4(v4);
        }

        return false;
    }
}
