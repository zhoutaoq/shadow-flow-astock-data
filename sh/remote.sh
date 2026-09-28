#!/usr/bin/env bash
# =============================================================================
# remote.sh — 远端发布执行逻辑（由本机 deploy.bat 上传后调用）
#
# 用法(在远端):
#     bash remote.sh {stop|start|restart|status}
# 本机 deploy.bat 的调用方式是先洗掉 CR 再喂给 bash（防止脚本被 Windows 改成 CRLF）：
#     cd <部署目录> && tr -d '\r' < remote.sh | REMOTE_DIR=<部署目录> bash -s -- restart
#
# 配置来源：
#   server.env  部署目录 / JDK 路径 / JVM 参数 / 启动参数（入库），本脚本解析后 export
#   app.env     应用密钥（不入库）—— v1.6.4 起打进 jar 随包走，由启动类的
#               AppEnv.load() 从 classpath 装载成系统属性，与本脚本无关
#
# 职责: 杀旧进程 -> 换新 jar -> setsid 后台启动 -> 探活 -> 打印状态
#   stop    仅停掉运行中的 java（不换 jar）
#   start   仅用当前 jar 启动（不杀）
#   restart 停旧 -> 换 .jar.new -> 启动   (deploy 默认走这个)
#   status  查进程 + 最近日志
# =============================================================================
set -u

# ---- 部署目录：优先取调用方传入的 REMOTE_DIR，其次内置兜底 ----
DIR="${REMOTE_DIR:-/home/stock-backtesting}"

# ---- 读取 server.env（cmd 的 set "K=V" 格式，逐行解析；REM / @echo off / 空行忽略）----
# 解析出的键一律 export 进本进程，start_new 起的 java 进程继承它们。
# 应用密钥（DB_PASSWORD / ADMIN_PASSWORD / SHADOWFLOW_API_TOKEN / DINGTALK_WEBHOOK）
# 自 v1.6.4 起不再走环境变量：app.env 在 jar 里，由启动类的 AppEnv.load() 装载。
load_env_file() {
  local file="$1" line body
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"                       # 容忍 CRLF
    case "$line" in
      'set "'*) ;;                             # 只认 set "K=V"
      *) continue ;;
    esac
    body="${line#set }"                        # -> "K=V"
    body="${body%\"}"                          # -> "K=V
    body="${body#\"}"                          # -> K=V
    case "$body" in
      *=*) ;;
      *) continue ;;
    esac
    export "${body%%=*}=${body#*=}"
  done < "$file"
}

ENV_FILE="$DIR/server.env"
if [ ! -f "$ENV_FILE" ]; then
  echo "[ERROR] 缺少服务器环境文件 $ENV_FILE"
  echo "        该文件由本机 sh/deploy.bat 上传；请先在本机跑一次 deploy.bat deploy"
  exit 1
fi
load_env_file "$ENV_FILE"
echo "[env] 已载入 server.env（app.env 在 jar 内由启动类装载，不经这里）"

# ---- 必需参数：缺了直接报错退出，避免用空值启出一个坏进程 ----
: "${JAVA:?server.env 里缺少 JAVA}"
: "${JAR_NAME:?server.env 里缺少 JAR_NAME}"
: "${ARGS:?server.env 里缺少 ARGS}"
OPTS="${OPTS:-}"
LOG="${LOG:-app.log}"

# 进程匹配: 直接按 jar 名匹配, 兼容命令行中带 JVM 参数的情况
# (如 ".../java -Xms.. -Xmx.. -jar shadow-flow-biz.jar")。
# 本脚本(bash remote.sh)自身命令行不含 jar 名, 故 pkill/pgrep 不会误伤自己。
P="${JAR_NAME}.jar"

# 探活端口：从 ARGS 里的 --server.port=80 取，取不到就不探。
# （旧写法用 ${ARGS##*=} 取，末位标志一变就取成 "true"，探活形同虚设）
PORT=""
case "$ARGS" in
  *--server.port=*) _t="${ARGS#*--server.port=}"; PORT="${_t%% *}" ;;
esac

cd "$DIR" || { echo "[ERROR] no dir $DIR"; exit 1; }

# ---------------- stop ----------------
stop_old() {
  echo "[stop] matching: $P"
  if pgrep -f "$P" >/dev/null 2>&1; then
    pkill -f "$P" 2>/dev/null && echo "  kill signal sent"
    sleep 4
    if pgrep -f "$P" >/dev/null 2>&1; then
      pkill -9 -f "$P" 2>/dev/null && echo "  force killed after grace"
      sleep 1
    fi
    pgrep -f "$P" >/dev/null 2>&1 && echo "  [WARN] still running, check manually" || echo "  all stopped"
  else
    echo "  no running process"
  fi
}

# ---------------- start ----------------
start_new() {
  rm -f "${JAR_NAME}.pid"
  # setsid 脱离会话 + nohup 挡 HUP + stdin 重定向, 保证 plink/ssh 断开后进程存活
  setsid bash -c "cd $DIR && $JAVA $OPTS -jar $P $ARGS >> $LOG 2>&1 < /dev/null" &
  echo $! > "${JAR_NAME}.pid"
  sleep 8
  if kill -0 "$(cat "${JAR_NAME}.pid" 2>/dev/null)" 2>/dev/null; then
    echo "[OK] started, pid=$(cat "${JAR_NAME}.pid")"
  else
    echo "[ERROR] process died, recent log:"
    tail -n 30 "$LOG"
    return 1
  fi
  # 探活(容忍服务启动慢)
  if [ -n "$PORT" ]; then
    sleep 2
    code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT}/" 2>/dev/null)
    echo "  http port ${PORT}: ${code:-no-response}"
  fi
}

# ---------------- swap jar ----------------
# 发布只保留「当前包 + 上一版备份」两份，历史版本一律清掉：
#   1) 先把在跑的包留成 <JAR_NAME>.jar.bak —— 直接覆盖上一版备份，滚动只留最近一份；
#   2) 再把 <JAR_NAME>.jar.new 换上去；
#   3) 最后扫一遍部署目录，把同名前缀的其它包（旧的 .bak.1 / .20260919 / .old 之类）删掉。
# 用 -maxdepth 1 + 完整前缀匹配，不碰目录里任何别的文件；-print -delete 会把删掉的打印出来，
# 日志里看得到清了哪些。注意 ${JAR_NAME}.pid 不匹配 "${JAR_NAME}.jar*"，不会被误删。
swap_jar() {
  if [ ! -f "${P}.new" ]; then
    echo "[swap] no .jar.new present, keep current jar"
    return 0
  fi
  if [ -f "$P" ]; then
    cp -p "$P" "${P}.bak" && echo "[backup] keep only latest: $P -> ${P}.bak"
  fi
  mv -f "${P}.new" "$P" && echo "[swap] jar updated -> $P"
  echo "[prune] removing jars other than $P / ${P}.bak"
  find . -maxdepth 1 -type f -name "${JAR_NAME}.jar*" \
       ! -name "$P" ! -name "${P}.bak" -print -delete 2>/dev/null || true
}

# ---------------- status ----------------
show_status() {
  echo "--- config ---"
  echo "  dir=$DIR  java=$JAVA  jar=$P  port=${PORT:-未从 ARGS 取到}"
  echo "--- process ---"
  pgrep -af "$P" || echo "(not running)"
  echo "--- recent log ---"
  tail -n 25 "$LOG" 2>/dev/null || echo "(no log)"
}

# ---------------- dispatch ----------------
case "${1:-restart}" in
  stop)     stop_old ;;
  start)    start_new ;;
  restart)  stop_old; swap_jar; start_new ;;
  status)   show_status ;;
  *)
    echo "usage: bash $0 {stop|start|restart|status}"
    exit 1 ;;
esac
