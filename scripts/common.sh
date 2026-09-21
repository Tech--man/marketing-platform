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
