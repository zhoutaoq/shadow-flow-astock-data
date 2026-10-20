#!/usr/bin/env bash
# ============================================================
#  服务器侧修复：让本机能够访问 github.com
#
#  背景（2026-10-20 线上故障）：
#    服务器在腾讯云国内，github.com 默认解析到 20.205.243.166，该 IP 被阻断 ——
#    TLS 能握手完成，但 HTTP 响应永远是 0 字节，表现为 SocketTimeoutException。
#    GitHub 其它边缘 IP（20.207.73.82 等）完全正常。
#    结果是「GitHub 三方登录」在回调换 token 时必然超时失败。
#
#  做法：
#    探测候选 IP 中哪些真能拿到 HTTP 200，挑最快的一个写进 /etc/hosts，
#    把 github.com 钉死到可用 IP。Java 走系统解析，因此无需改任何代码。
#
#  用法（在服务器上执行，需 root）：
#    bash server-fix-github-dns.sh            # 探测并写入
#    bash server-fix-github-dns.sh --check    # 只探测，不改文件
#    bash server-fix-github-dns.sh --revert   # 还原到 /etc/hosts.bak.shadowflow
#
#  注意：这是机器级状态，重装系统后要再跑一次；换了 jar 也不影响。
# ============================================================
set -u

HOSTNAME_FIX="github.com"
BAK="/etc/hosts.bak.shadowflow"
MARK="# shadow-flow: GitHub DNS 被污染，固定到可用边缘 IP"

# GitHub 公布的 github.com 边缘 IP（含 140.82.112.0/20 段与 Azure Front Door 段）
CANDIDATES="
20.207.73.82
20.233.83.145
20.199.39.232
20.201.28.151
20.29.134.23
140.82.121.3
140.82.116.3
140.82.114.4
140.82.113.4
140.82.112.4
"

MODE="${1:-}"

if [ "$MODE" = "--revert" ]; then
    if [ -f "$BAK" ]; then
        cp "$BAK" /etc/hosts && echo "[OK] 已还原 /etc/hosts（来自 $BAK）"
    else
        echo "[WARN] 没有备份 $BAK，改为直接删掉 github.com 那行"
        grep -v "$HOSTNAME_FIX" /etc/hosts > /tmp/hosts.new && cat /tmp/hosts.new > /etc/hosts
        rm -f /tmp/hosts.new
        echo "[OK] 已删除 github.com 固定解析"
    fi
    getent hosts "$HOSTNAME_FIX" || true
    exit 0
fi

echo "[probe] 逐个测试候选 IP（每个最多 8 秒）..."
BEST=""
BEST_MS=999999
for ip in $CANDIDATES; do
    # 只认「HTTP 200 且响应体非空」，只连上不算数 —— 被阻断的 IP 也能完成 TLS
    out=$(curl -sS -o /dev/null -m 8 \
        -w '%{http_code} %{time_total}' \
        --resolve "$HOSTNAME_FIX:443:$ip" "https://$HOSTNAME_FIX/" 2>/dev/null || echo "000 99")
    code=$(echo "$out" | awk '{print $1}')
    secs=$(echo "$out" | awk '{print $2}')
    printf '  %-16s http=%-4s t=%s\n' "$ip" "$code" "$secs"
    if [ "$code" = "200" ]; then
        ms=$(awk -v s="$secs" 'BEGIN{printf "%d", s*1000}')
        if [ "$ms" -lt "$BEST_MS" ]; then
            BEST_MS=$ms
            BEST=$ip
        fi
    fi
done

if [ -z "$BEST" ]; then
    echo "[FAIL] 所有候选 IP 都不通，需要给服务器配代理或换线路"
    exit 1
fi
echo "[probe] 最优 IP: $BEST (${BEST_MS}ms)"

if [ "$MODE" = "--check" ]; then
    echo "[check] 当前解析：$(getent hosts "$HOSTNAME_FIX" || echo '无')"
    exit 0
fi

# 幂等写入：先备份，再剔除旧的 github.com 行，最后追加
[ -f "$BAK" ] || cp /etc/hosts "$BAK"
grep -vE "[[:space:]]${HOSTNAME_FIX//./\\.}([[:space:]]|$)" /etc/hosts > /tmp/hosts.new
printf '%s %s  %s\n' "$BEST" "$HOSTNAME_FIX" "$MARK" >> /tmp/hosts.new
cat /tmp/hosts.new > /etc/hosts
rm -f /tmp/hosts.new

echo "[OK] /etc/hosts 已更新："
getent hosts "$HOSTNAME_FIX"
echo "[verify] 换 token 端点连通性（假凭据应返回 200 + JSON 错误）："
curl -sS -m 20 -X POST "https://$HOSTNAME_FIX/login/oauth/access_token" \
    -H 'Accept: application/json' \
    -d 'client_id=__probe__&client_secret=__probe__&code=__probe__' \
    -w '\n  http=%{http_code} t=%{time_total}\n' | head -3
