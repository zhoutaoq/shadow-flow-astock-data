#!/usr/bin/env bash
# 本机 Maven 调用封装。三个坑缺一不可，详见 .workbuddy/memory 的环境坑记录：
#  1. 绕开 mvn / mvn.cmd 启动器（Git Bash 下路径转换坏掉，必报找不到主类），
#     直接用 java 调 classworlds launcher；
#  2. -Dmaven.repo.local 指到真实仓库（global settings 里那份 localRepository 是别人的目录）；
#  3. -o -Dmaven.legacyLocalRepo=true 忽略内网 Nexus 镜像与 _remote.repositories 来源不匹配。
set -e

MAVEN_HOME="D:/java/env/apache-maven-3.8.8"
JAVA_BIN="${JAVA_BIN:-D:/java/env/jdk1.8.0_281/bin/java}"
# 路径必须 Windows 风格：Git Bash 下 /d/... 交给 JVM 会解析失败
PROJ="D:/workspace/gitee/stock-kline-data-crawler"

"$JAVA_BIN" \
  -classpath "$MAVEN_HOME/boot/plexus-classworlds-2.6.0.jar" \
  -Dclassworlds.conf="$MAVEN_HOME/bin/m2.conf" \
  -Dmaven.home="$MAVEN_HOME" \
  -Dmaven.multiModuleProjectDirectory="$PROJ" \
  -Dmaven.repo.local=D:/java/env/repository \
  org.codehaus.plexus.classworlds.launcher.Launcher \
  -o -Dmaven.legacyLocalRepo=true "$@"
