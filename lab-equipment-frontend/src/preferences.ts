import { computed, ref, watch } from "vue";
export type Theme = "light" | "dark" | "system";
const keys = {
  theme: "boyi.theme",
  sidebar: "boyi.sidebar-expanded",
  history: "boyi.history-visible",
};
function read(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}
function write(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* Preferences remain usable when storage is unavailable. */
  }
}
const storedTheme = read(keys.theme);
export const theme = ref<Theme>(
  storedTheme === "light" || storedTheme === "dark" ? storedTheme : "system",
);
const media =
  typeof window.matchMedia === "function"
    ? window.matchMedia("(prefers-color-scheme: dark)")
    : undefined;
const systemDark = ref(media?.matches || false);
export const isDark = computed(
  () =>
    theme.value === "dark" || (theme.value === "system" && systemDark.value),
);
export const sidebarExpanded = ref(read(keys.sidebar) === "true");
const storedHistory = read(keys.history);
export const historyVisible = ref(
  storedHistory === null ? window.innerWidth > 760 : storedHistory === "true",
);
watch(theme, (value) => write(keys.theme, value), { flush: "sync" });
watch(sidebarExpanded, (value) => write(keys.sidebar, String(value)), {
  flush: "sync",
});
watch(historyVisible, (value) => write(keys.history, String(value)), {
  flush: "sync",
});
export function toggleTheme() {
  theme.value = isDark.value ? "light" : "dark";
}
export function initAppearance() {
  const apply = () => {
    document.documentElement.classList.toggle("dark", isDark.value);
    document.documentElement.dataset.theme = isDark.value ? "dark" : "light";
    document.documentElement.style.colorScheme = isDark.value
      ? "dark"
      : "light";
    document
      .querySelector('meta[name="theme-color"]')
      ?.setAttribute("content", isDark.value ? "#17151d" : "#6554c0");
  };
  const stop = watch(isDark, apply, { immediate: true, flush: "sync" });
  const changed = (event: MediaQueryListEvent) => {
    systemDark.value = event.matches;
  };
  media?.addEventListener?.("change", changed);
  const sync = (event: StorageEvent) => {
    if (event.key === keys.theme)
      theme.value =
        event.newValue === "light" || event.newValue === "dark"
          ? event.newValue
          : "system";
    if (event.key === keys.sidebar)
      sidebarExpanded.value = event.newValue === "true";
    if (event.key === keys.history)
      historyVisible.value = event.newValue !== "false";
  };
  window.addEventListener("storage", sync);
  return () => {
    stop();
    media?.removeEventListener?.("change", changed);
    window.removeEventListener("storage", sync);
  };
}
