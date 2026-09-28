/**
 * 主题：与后台同源手法，语义色走 data-theme。C 端无 Element Plus，
 * 不碰 html.dark。localStorage 存显式选择；'system'/空 跟随系统。
 */
const KEY = "mkt.h5.theme";

function preferred() {
  const saved = localStorage.getItem(KEY);
  if (saved === "light" || saved === "dark") return saved;
  return typeof window !== "undefined" &&
    window.matchMedia &&
    window.matchMedia("(prefers-color-scheme: dark)").matches
    ? "dark"
    : "light";
}

export function apply(theme) {
  const el = document.documentElement;
  el.dataset.theme = theme;
  el.style.colorScheme = theme;
  return theme;
}

export function initTheme() {
  return apply(preferred());
}

export function getTheme() {
  return document.documentElement.dataset.theme === "dark" ? "dark" : "light";
}

export function toggleTheme() {
  const next = getTheme() === "dark" ? "light" : "dark";
  localStorage.setItem(KEY, next);
  return apply(next);
}
