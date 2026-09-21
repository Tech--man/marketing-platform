#!/usr/bin/env bash
# ============================================================
# 脚本公共前置（被 start-*/deploy-* source，不单独执行）
# ============================================================

# ---- 构建工具链锁定：把 Maven 钉在 JDK 17 -------------------
# Homebrew 默认 JDK 已滚到 26，而 Spring Boot 3.2.5 管理的 Lombok 1.18.x 在其上
# 无法运行注解处理——表现为满屏 "cannot find symbol: log / setXxx"，且只在触发
# 重编译的模块上出现（增量编译时会假装成功）。
# 已在外部显式设置 JAVA_HOME 时尊重外部选择。
if [ -z "${JAVA_HOME:-}" ] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
  if _jdk17="$(/usr/libexec/java_home -v 17 2>/dev/null)"; then
    export JAVA_HOME="$_jdk17"
  else
    echo "!! 未找到 JDK 17，构建可能因 Lombok 与 JDK 26 不兼容而失败" >&2
  fi
  unset _jdk17
fi

# ---- 健康等待：轮询 /actuator/health 直到 UP ----------------
# wait_healthy <服务名> <端口> [超时秒]
wait_healthy() {
  local name=$1 port=$2 deadline=${3:-60}
  for _ in $(seq 1 "$deadline"); do
    if curl -fs --max-time 3 "http://127.0.0.1:$port/actuator/health" 2>/dev/null | grep -q '"UP"'; then
      echo "==> $name 健康检查通过 (:$port)"
      return 0
    fi
    sleep 1
  done
  echo "!! $name ${deadline}s 内未就绪，请查看 logs/$name.log" >&2
  return 1
}

# ---- 端口归属自检 --------------------------------------------
# 宿主机上若已有同名服务常驻默认端口（典型：Homebrew 的 redis-server 占着
# 127.0.0.1:6379），它的精确地址绑定会优先于 Docker 对 *:port 的发布，本机进程
# 会静默连到那个"外人"实例——三套环境瞬间退化成共用一个中间件，且现象只在数据
# 对不上时才暴露。启动前先挡住。
assert_port_not_shadowed() { # <宿主机端口>
  local port=$1 pids pid comm
  command -v lsof >/dev/null 2>&1 || return 0
  pids="$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null | sort -u || true)"
  [ -z "$pids" ] && return 0
  for pid in $pids; do
    comm="$(ps -o comm= -p "$pid" 2>/dev/null || true)"
    case "$comm" in
      *redis-server* | *mysqld* | *mariadb*)
        echo "!! 宿主机进程 ${comm:-未知} (pid $pid) 正在监听 :$port" >&2
        echo "   它会遮蔽容器发布的同一端口，业务进程会连到它而不是本环境的中间件。" >&2
        echo "   处理：停掉该进程，或把本形态的宿主机端口改成其它端口" >&2
        return 1
        ;;
    esac
  done
  return 0
}
