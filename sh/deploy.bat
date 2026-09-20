@echo off
REM ============================================================
REM  shadow-flow-biz (影流数据 Shadow Flow Data)  Windows one-click deploy
REM  v1.6.0：jar 名由 stock-kline-data-crawler 改为 shadow-flow-biz（见 server.env 的 JAR_NAME）
REM
REM  Usage:
REM    Double-click  = full publish (build -> upload -> restart)
REM    Or run in cmd:
REM        deploy.bat all       full publish
REM        deploy.bat build     mvn clean package only
REM        deploy.bat deploy    upload + restart (no build)
REM        deploy.bat restart   remote restart using current jar
REM        deploy.bat status    show remote process + log
REM
REM  Config (all in THIS folder, re-read on EVERY run):
REM    deploy.env   服务器连接信息：IP / 账号 / 口令 / 主机指纹
REM                 ^<-- 含密钥，已列入 .gitignore，不入库
REM    server.env   服务器运行参数：部署目录 / JDK / JVM / 启动参数
REM                 ^<-- 无密钥，入版本库；并会一起上传到服务器供 remote.sh 读取
REM    app.env      应用密钥：数据库口令 / 超管初始口令 / 行情令牌 / 钉钉机器人
REM                 ^<-- 含密钥，已列入 .gitignore，不入库；上传后由远端 remote.sh
REM                     注入 java 进程环境，application.yml 里的 ${...} 占位符据此取值
REM
REM  Files needed in THIS folder:  plink.exe  pscp.exe  remote.sh  (见 使用说明.md)
REM ============================================================
setlocal enabledelayedexpansion

set "HERE=%~dp0"

REM ==================== 1. 读取服务器环境文件 ====================
if not exist "%HERE%deploy.env" (
  echo.
  echo [ERROR] 缺少服务器连接文件: %HERE%deploy.env
  echo         请把 deploy.env.example 复制成 deploy.env，填入 IP / 账号 / 口令 / 主机指纹。
  goto DONE
)
if not exist "%HERE%server.env" (
  echo.
  echo [ERROR] 缺少服务器环境文件: %HERE%server.env
  goto DONE
)
call "%HERE%deploy.env"
call "%HERE%server.env"

set "MISSING="
if "%SSH%"=="" set "MISSING=%MISSING% SSH"
if "%PWD%"=="" set "MISSING=%MISSING% PWD"
if "%HOSTKEY%"=="" set "MISSING=%MISSING% HOSTKEY"
if "%REMOTE_DIR%"=="" set "MISSING=%MISSING% REMOTE_DIR"
if "%JAR_NAME%"=="" set "MISSING=%MISSING% JAR_NAME"
if not "%MISSING%"=="" (
  echo.
  echo [ERROR] 配置缺项:%MISSING%
  echo         deploy.env 需有 SSH / PWD / HOSTKEY；server.env 需有 REMOTE_DIR / JAR_NAME。
  goto DONE
)

REM ==================== 2. 本机构建环境 ====================
set "MVN=C:\workspace\java\apache-maven-3.8.1\bin\mvn.cmd"
set "BUILD_JAVA_HOME=C:\workspace\java\jdk8x64"
set "PROJECT_DIR=C:\workspace\code\stock\stock-kline-data-crawler"

REM ==================== 3. 派生变量（无需修改） ====================
set "PLINK=%HERE%plink.exe"
set "PSCP=%HERE%pscp.exe"
set "RSCRIPT=%HERE%remote.sh"
set "AUTH=-batch -hostkey %HOSTKEY% -pw %PWD% %SSH%"
REM v1.6.0 多模块拆分后，可执行 jar 落在业务模块 shadow-flow-biz/target 下（父工程 target 里没有 jar）
set "LOCAL_JAR=%PROJECT_DIR%\shadow-flow-biz\target\%JAR_NAME%.jar"
set "ACTION=%~1"
if "%ACTION%"=="" set "ACTION=all"

if not exist "%PLINK%" (
  echo [ERROR] 缺少 %PLINK%
  echo         请下载 PuTTY 命令行工具 plink.exe 与 pscp.exe，放到 %HERE%
  goto DONE
)

cd /d "%PROJECT_DIR%"

echo ============================================================
echo   %JAR_NAME%  Deploy  (%ACTION%)
echo   target: %SSH%  dir=%REMOTE_DIR%
echo ============================================================

if /i "%ACTION%"=="all"     goto FULL
if /i "%ACTION%"=="build"   goto BUILD
if /i "%ACTION%"=="deploy"  goto DEPLOY
if /i "%ACTION%"=="restart" goto RESTART
if /i "%ACTION%"=="status"  goto STATUS
echo [ERROR] unknown action: %ACTION%
goto DONE

:FULL
call :step_build || goto FAIL
call :step_upload
call :step_restart
goto DONE

:DEPLOY
call :step_upload
call :step_restart
goto DONE

:BUILD
call :step_build || goto FAIL
goto DONE

:RESTART
call :step_restart
goto DONE

:STATUS
call :step_remote status
goto DONE

:FAIL
echo.
echo ============ BUILD FAILED (see output above) ============

:DONE
echo.
echo ==================== DONE ====================
echo   remote log:  %REMOTE_DIR%/%LOG%
echo ============================================================
pause
goto :EOF

REM ---------- build: mvn clean package ----------
:step_build
echo.
echo [1/3] mvn clean package -DskipTests=true ...
if not exist "%MVN%" ( echo   [ERROR] mvn not found: %MVN% & exit /b 1 )
set "JAVA_HOME=%BUILD_JAVA_HOME%"
call "%MVN%" -f "%PROJECT_DIR%\pom.xml" clean package -DskipTests=true
if errorlevel 1 ( echo   [ERROR] build failed & exit /b 1 )
if not exist "%LOCAL_JAR%" ( echo   [ERROR] jar missing: %LOCAL_JAR% & exit /b 1 )
echo   [OK] built %LOCAL_JAR%
exit /b 0

REM ---------- upload: jar + remote.sh + server.env + app.env ----------
:step_upload
echo.
echo [2/3] uploading jar / remote.sh / server.env / app.env ...
"%PSCP%" %AUTH% "%LOCAL_JAR%" "%SSH%:%REMOTE_DIR%/%JAR_NAME%.jar.new"
if errorlevel 1 ( echo   [ERROR] jar upload failed & exit /b 1 )
echo   [OK] jar uploaded (as .jar.new)
"%PSCP%" %AUTH% "%RSCRIPT%" "%SSH%:%REMOTE_DIR%/remote.sh"
if errorlevel 1 ( echo   [ERROR] remote.sh upload failed & exit /b 1 )
"%PSCP%" %AUTH% "%HERE%server.env" "%SSH%:%REMOTE_DIR%/server.env"
if errorlevel 1 ( echo   [ERROR] server.env upload failed & exit /b 1 )
REM app.env 是密钥文件（不入库）：本机没有就只告警、不中断发布 ——
REM 服务器上那份保留真实配置，跳过上传不会动它。
if exist "%HERE%app.env" (
  "%PSCP%" %AUTH% "%HERE%app.env" "%SSH%:%REMOTE_DIR%/app.env"
  if errorlevel 1 ( echo   [ERROR] app.env upload failed & exit /b 1 )
  echo   [OK] remote.sh + server.env + app.env uploaded
) else (
  echo   [WARN] 本机缺少 %HERE%app.env，跳过密钥文件上传
  echo          服务器上原有的 app.env 保持不动；远端也没有时，密钥类环境变量不会被注入
  echo          [OK] remote.sh + server.env uploaded
)
exit /b 0

REM ---------- remote restart ----------
REM tr -d '\r' 先把脚本里的 CR 洗掉再喂给 bash：这样本机 remote.sh 被任何 Windows 工具
REM 改成 CRLF 都不影响发布（bash 读 CRLF 会直接语法错、连停旧进程都执行不到）。
:step_restart
echo.
echo [3/3] remote restart (stop old, swap jar, start new) ...
"%PLINK%" %AUTH% "cd %REMOTE_DIR% && tr -d '\r' < remote.sh | REMOTE_DIR=%REMOTE_DIR% bash -s -- restart"
if errorlevel 1 ( echo   [ERROR] remote restart step failed (see above) & exit /b 1 )
exit /b 0

:step_remote
"%PLINK%" %AUTH% "cd %REMOTE_DIR% && tr -d '\r' < remote.sh | REMOTE_DIR=%REMOTE_DIR% bash -s -- %~1"
exit /b 0
