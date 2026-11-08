import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
let stop: (() => void) | undefined;
let change: ((event: { matches: boolean }) => void) | undefined;
let system = false;
beforeEach(() => {
  vi.resetModules();
  localStorage.clear();
  system = false;
  change = undefined;
  vi.stubGlobal(
    "matchMedia",
    vi.fn(() => ({
      matches: system,
      addEventListener: (_type: string, handler: typeof change) => {
        change = handler;
      },
      removeEventListener: vi.fn(),
    })),
  );
});
afterEach(() => {
  stop?.();
  stop = undefined;
  vi.unstubAllGlobals();
  document.documentElement.classList.remove("dark");
  delete document.documentElement.dataset.theme;
});
describe("外观与侧栏偏好", () => {
  it("手动选择深浅模式，重新加载模块后仍保留", async () => {
    let prefs = await import("./preferences");
    stop = prefs.initAppearance();
    prefs.toggleTheme();
    expect(document.documentElement.classList.contains("dark")).toBe(true);
    expect(localStorage.getItem("boyi.theme")).toBe("dark");
    stop();
    vi.resetModules();
    prefs = await import("./preferences");
    stop = prefs.initAppearance();
    expect(prefs.isDark.value).toBe(true);
    prefs.toggleTheme();
    expect(localStorage.getItem("boyi.theme")).toBe("light");
    expect(document.documentElement.dataset.theme).toBe("light");
  });
  it("导航栏和对话列表分别保存并恢复", async () => {
    const prefs = await import("./preferences");
    prefs.sidebarExpanded.value = true;
    prefs.historyVisible.value = false;
    vi.resetModules();
    const restored = await import("./preferences");
    expect(restored.sidebarExpanded.value).toBe(true);
    expect(restored.historyVisible.value).toBe(false);
    restored.sidebarExpanded.value = false;
    expect(localStorage.getItem("boyi.sidebar-expanded")).toBe("false");
  });
  it("未手动选择时跟随系统，手动选择后不会被系统变化覆盖", async () => {
    system = true;
    const prefs = await import("./preferences");
    stop = prefs.initAppearance();
    expect(prefs.isDark.value).toBe(true);
    expect(localStorage.getItem("boyi.theme")).toBeNull();
    change?.({ matches: false });
    expect(prefs.isDark.value).toBe(false);
    prefs.toggleTheme();
    change?.({ matches: false });
    expect(prefs.isDark.value).toBe(true);
    expect(prefs.theme.value).toBe("dark");
  });
  it("浏览器存储不可用时仍能切换", async () => {
    vi.stubGlobal("localStorage", {
      getItem() {
        throw new Error("blocked");
      },
      setItem() {
        throw new Error("blocked");
      },
    });
    const prefs = await import("./preferences");
    stop = prefs.initAppearance();
    expect(() => {
      prefs.toggleTheme();
      prefs.sidebarExpanded.value = true;
    }).not.toThrow();
    expect(prefs.isDark.value).toBe(true);
  });
});
