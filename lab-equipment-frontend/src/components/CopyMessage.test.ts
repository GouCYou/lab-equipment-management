import { afterEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import CopyMessage from "./CopyMessage.vue";
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
describe("回复复制", () => {
  it("复制完整正文并反馈成功", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal("isSecureContext", true);
    vi.stubGlobal("navigator", { clipboard: { writeText } });
    const w = mount(CopyMessage, { props: { content: "完整回复\n第二行" } });
    await w.get("button").trigger("click");
    expect(writeText).toHaveBeenCalledWith("完整回复\n第二行");
    expect(w.get("button").attributes("aria-label")).toBe("已复制");
    w.unmount();
  });
  it("局域网 HTTP 使用兼容复制并移除临时输入框", async () => {
    vi.stubGlobal("isSecureContext", false);
    const command = vi.fn().mockImplementation(() => {
      expect((document.activeElement as HTMLTextAreaElement).value).toBe(
        "设备回复",
      );
      return true;
    });
    Object.defineProperty(document, "execCommand", {
      value: command,
      configurable: true,
    });
    const w = mount(CopyMessage, { props: { content: "设备回复" } });
    await w.get("button").trigger("click");
    expect(command).toHaveBeenCalledWith("copy");
    expect(document.querySelector("textarea")).toBeNull();
    expect(w.get("button").attributes("aria-label")).toBe("已复制");
    w.unmount();
    delete (document as any).execCommand;
  });
});
