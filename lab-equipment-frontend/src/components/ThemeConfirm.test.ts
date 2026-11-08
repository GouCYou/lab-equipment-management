import { describe, it, expect } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import ThemeConfirm from "./ThemeConfirm.vue";
import { dialogs } from "../dialogs";
describe("单行重命名", () => {
  it("使用单行输入并通过表单提交新标题", async () => {
    const w = mount(ThemeConfirm, { attachTo: document.body });
    const result = dialogs.prompt("", "重命名对话", {
      singleLine: true,
      inputLabel: "对话标题",
      inputValue: "旧标题",
    });
    await flushPromises();
    expect(document.querySelector("textarea")).toBeNull();
    const input = document.querySelector<HTMLInputElement>(
      'input[aria-label="对话标题"]',
    )!;
    expect(input.value).toBe("旧标题");
    input.value = "新标题";
    input.dispatchEvent(new Event("input", { bubbles: true }));
    document
      .querySelector("form")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    expect(await result).toEqual({ value: "新标题" });
    w.unmount();
  });
});
