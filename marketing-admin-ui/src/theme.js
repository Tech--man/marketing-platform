/**
 * 主题：把 data-theme（我们自己的语义色）与 html.dark（Element Plus 的 dark css-vars）
 * 一起切，二者始终同源。首屏在 main.js 里 initTheme() 先落地，避免暗色用户闪一下白。
 * localStorage 里存用户显式选择；没选过时跟随系统 prefers-color-scheme。
 */
const KEY = "mkt.ui.theme";

function preferred() {
  const saved = localStorage.getItem(KEY);
  if (saved === "light" || saved === "dark") return saved;
  return (
    (typeof window !== "undefined" &&
      window.matchMedia &&
      window.matchMedia("(prefers-color-scheme: dark)").matches)
      ? "dark"
      : "light"
  );
}

export function apply(theme) {
  const el = document.documentElement;
  el.dataset.theme = theme;
  el.classList.toggle("dark", theme === "dark");
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
