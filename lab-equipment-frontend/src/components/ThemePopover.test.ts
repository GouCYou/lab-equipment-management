import { describe, it, expect, afterEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { h } from "vue";
import ThemePopover from "./ThemePopover.vue";
let wrapper: ReturnType<typeof mount> | undefined;
afterEach(() => {
  wrapper?.unmount();
  document.body.innerHTML = "";
});
function setup() {
  wrapper = mount(ThemePopover, {
    attachTo: document.body,
    props: {
      items: [
        { id: "profile", label: "个人设置" },
        { id: "logout", label: "退出登录" },
      ],
    },
    slots: {
      trigger: ({ toggle }: { toggle: () => void }) =>
        h("button", { onClick: toggle }, "账户菜单"),
    },
  });
  return wrapper;
}
describe("主题弹出菜单", () => {
  it("选择菜单项后关闭，并返回动作标识", async () => {
    const w = setup();
    await w.get("button").trigger("click");
    await flushPromises();
    const item = document.querySelector<HTMLButtonElement>("[role=menuitem]")!;
    item.click();
    await flushPromises();
    expect(w.emitted("select")?.[0]).toEqual(["profile"]);
    expect(document.querySelector("[role=menu]")).toBeNull();
  });
  it("Escape 关闭并把焦点交还触发按钮", async () => {
    const w = setup();
    const trigger = w.get("button").element as HTMLButtonElement;
    trigger.focus();
    await w.get("button").trigger("click");
    await flushPromises();
    document
      .querySelector("[role=menu]")!
      .dispatchEvent(
        new KeyboardEvent("keydown", { key: "Escape", bubbles: true }),
      );
    await flushPromises();
    expect(document.querySelector("[role=menu]")).toBeNull();
    expect(document.activeElement).toBe(trigger);
    expect(trigger.getAttribute("aria-expanded")).toBe("false");
  });
});
