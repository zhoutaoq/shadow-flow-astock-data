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
#   app.env     应用密钥（不入库，**只存在于部署机器上**）—— 本脚本启动前把它逐行 export 成
#               进程环境变量，application.yml 里的 ${XXX:} 占位符直接就能解析。
#               v1.7.3 起 app.env 不再打进 jar：凭据属于机器，不属于构建产物，
#               否则「谁打包谁决定线上凭据」（Actions 用 Secret 打一份、本地用本机文件打一份，
#               互相覆盖，2026-10-20 GitHub 三方登录莫名变回未开通就是这么来的）。
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
# 两种写法都认：set "K=V"（Windows 风格，server.env 与 app.env 都是）与裸 K=V。
# REM / # / @echo off / 空行一律跳过；容忍 CRLF、BOM、行尾空白。
load_env_file() {
  local file="$1" line body
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"                       # 容忍 CRLF
    line="${line#$'\xEF\xBB\xBF'}"             # 容忍 BOM
    case "$line" in
      ''|'#'*|'REM '*|'rem '*|'@echo off'*|'@echo OFF'*) continue ;;
      'set "'*|'SET "'*)
        body="${line#set }"                    # -> "K=V"
        body="${body#SET }"
        body="${body%\"}"                      # -> "K=V
        body="${body#\"}"                      # -> K=V
        ;;
      *) body="$line" ;;                       # 裸 K=V
    esac
    case "$body" in
      *=*) ;;
      *) continue ;;
    esac
    _ek="${body%%=*}"
    _ev="${body#*=}"
    # 占位符（「在此填写…」）与空值一律不导出：否则 Spring 拿到的就是
    # 「在此填写数据库口令」这种废话值，而不是「未配置」——
    # jar 内那份由 AppEnv 解析时有同样的过滤，这里必须保持同一口径。
    case "$_ev" in
      ''|'在此填写'*) continue ;;
    esac
    export "${_ek}=${_ev}"
  done < "$file"
}

ENV_FILE="$DIR/server.env"
if [ ! -f "$ENV_FILE" ]; then
  echo "[ERROR] 缺少服务器环境文件 $ENV_FILE"
  echo "        该文件由本机 sh/deploy.bat 上传；请先在本机跑一次 deploy.bat deploy"
  exit 1
fi
load_env_file "$ENV_FILE"
echo "[env] 已载入 server.env"

# ---- 读取 app.env：应用密钥，只存在这台机器上，不进 jar ----
# 导出的变量会被 java 进程继承，application.yml 的 ${XXX:} 占位符直接解析。
# 同时也告诉 AppEnv 去哪里找同一个文件（它还会从文件读一遍，值同源，不冲突）。
APP_ENV_FILE="$DIR/app.env"
if [ -f "$APP_ENV_FILE" ]; then
  load_env_file "$APP_ENV_FILE"
  export APP_ENV_FILE
  echo "[env] 已载入 app.env（密钥来自本机文件，不随 jar 发布）"
else
  echo "[env] [WARN] 缺少 $APP_ENV_FILE"
  echo "        密钥文件应当单独放在服务器上（chmod 600），不随 jar 发布。"
  echo "        首次部署请把 app.env 放到该路径，否则服务会因缺密钥启动失败。"
fi

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
  # ⚠️ 外层这层重定向不能省：只重定向里面的 java，外面这层 bash 会一直持有调用方的
  #    stdout（也就是 ssh/plink 那条通道），服务在跑它就永不退出 —— ssh 会话关不掉，
  #    表现为发布命令卡住不动 / 几分钟后被链路掐断报 client_loop: Broken pipe (exit 255)。
  setsid bash -c "cd $DIR && $JAVA $OPTS -jar $P $ARGS >> $LOG 2>&1 < /dev/null" >> "$LOG" 2>&1 < /dev/null &
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
