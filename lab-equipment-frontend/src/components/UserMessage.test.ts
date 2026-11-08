import { describe, it, expect, vi } from "vitest";
import { mount } from "@vue/test-utils";
vi.mock("../state", () => ({ photoUrl: vi.fn() }));
import UserMessage from "./UserMessage.vue";
function setup() {
  return mount(UserMessage, {
    props: { content: "原消息", attachments: ["photo-1"] },
    global: { stubs: { Photos: true } },
  });
}
describe("消息编辑", () => {
  it("取消编辑保留原文，重新打开恢复原始内容", async () => {
    const w = setup();
    await w.get('[aria-label="编辑消息"]').trigger("click");
    await w.get("textarea").setValue("未保存内容");
    await w.get('button[type="button"]').trigger("click");
    expect(w.text()).toContain("原消息");
    expect(w.emitted("resend")).toBeUndefined();
    await w.get('[aria-label="编辑消息"]').trigger("click");
    expect((w.get("textarea").element as HTMLTextAreaElement).value).toBe(
      "原消息",
    );
    w.unmount();
  });
  it("完成并发送提交修改文字，保留图片", async () => {
    const w = setup();
    await w.get('[aria-label="编辑消息"]').trigger("click");
    await w.get("textarea").setValue(" 修改后的消息 ");
    await w.get("form").trigger("submit");
    expect(w.emitted("resend")).toEqual([["修改后的消息"]]);
    expect(w.props("attachments")).toEqual(["photo-1"]);
    w.unmount();
  });
  it("处理中不能提交编辑内容", async () => {
    const w = setup();
    await w.get('[aria-label="编辑消息"]').trigger("click");
    await w.setProps({ disabled: true });
    await w.get("form").trigger("submit");
    expect(w.emitted("resend")).toBeUndefined();
    w.unmount();
  });
});
