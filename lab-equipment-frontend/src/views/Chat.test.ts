import { describe, it, expect, vi, afterEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
const mocks = vi.hoisted(() => ({
  post: vi.fn(() => new Promise(() => {})),
  get: vi.fn(async (path: string) => ({
    data:
      path === "/conversations"
        ? []
        : {
            title: "设备咨询",
            messages: [
              {
                id: "user-1",
                role: "user",
                content: "旧问题",
                attachments: [],
                createdAt: "2026-11-08T01:00:00Z",
              },
              {
                id: "assistant-1",
                role: "assistant",
                content: "旧的模型回复",
                cards: [],
                citations: [],
              },
            ],
            runs: [{ id: "run-1", status: "FAILED", error: "failed" }],
          },
  })),
}));
vi.mock("../state", () => ({
  api: { get: mocks.get },
  post: mocks.post,
  state: { user: { roles: ["STUDENT"] }, labs: [] },
  staff: () => false,
  fail: vi.fn(),
  date: () => "09:00",
  changed: vi.fn(),
}));
vi.mock("vue-router", () => ({
  useRoute: () => ({ query: { id: "existing" } }),
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
}));
import Chat from "./Chat.vue";
HTMLElement.prototype.scrollIntoView = vi.fn();
let wrapper: ReturnType<typeof mount>;
afterEach(() => wrapper?.unmount());
function setup() {
  return mount(Chat, {
    global: {
      directives: { loading: {} },
      stubs: {
        Badge: true,
        RouterLink: true,
        Icon: true,
        ThemePopover: true,
        UserMessage: true,
        BusinessCard: true,
        CopyMessage: true,
        ChatComposer: true,
        ElAlert: { template: '<div class="alert">请求未完成</div>' },
      },
    },
  });
}
describe("提交时的即时消息状态", () => {
  it("编辑发送立即替换旧问题并清除后续回复及失败状态，无需等待接口", async () => {
    wrapper = setup();
    await flushPromises();
    expect(wrapper.text()).toContain("旧的模型回复");
    expect(wrapper.find(".alert").exists()).toBe(true);
    wrapper
      .findComponent({ name: "UserMessage" })
      .vm.$emit("resend", "修改后的问题");
    await flushPromises();
    expect(wrapper.text()).not.toContain("旧的模型回复");
    expect(wrapper.find(".alert").exists()).toBe(false);
    expect(
      wrapper.findComponent({ name: "UserMessage" }).props("content"),
    ).toBe("修改后的问题");
    expect(wrapper.findComponent({ name: "ChatComposer" }).props("busy")).toBe(
      true,
    );
  });
  it("普通发送立即清空输入并显示刚发送的内容", async () => {
    wrapper = setup();
    await flushPromises();
    const composer = wrapper.findComponent({ name: "ChatComposer" });
    composer.vm.$emit("update:modelValue", "新的设备问题");
    await flushPromises();
    composer.vm.$emit("send");
    await flushPromises();
    expect(composer.props("modelValue")).toBe("");
    expect(
      wrapper
        .findAllComponents({ name: "UserMessage" })
        .at(-1)
        ?.props("content"),
    ).toBe("新的设备问题");
    expect(wrapper.find(".alert").exists()).toBe(false);
  });
});
