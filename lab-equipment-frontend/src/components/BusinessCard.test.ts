import { beforeEach, describe, it, expect, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createRouter, createMemoryHistory } from "vue-router";
import ElementPlus from "element-plus";
import BusinessCard from "./BusinessCard.vue";
import { state, actions } from "../state";
vi.mock("axios", async () => {
  const actual = await vi.importActual<any>("axios");
  return { default: actual.default };
});
function card(userId: string, status = "BORROWED") {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/", component: { template: "<div/>" } }],
  });
  return mount(BusinessCard, {
    props: {
      card: {
        type: "loan",
        data: {
          id: "loan-1",
          userId,
          status,
          returnedAt: status === "RETURNED" ? "2026-11-03T04:00:00Z" : null,
          equipmentName: "示波器",
          assetNo: "LAB-1",
          userName: "学生",
          dueAt: "2026-11-03T06:00:00Z",
        },
      },
    },
    global: { plugins: [router, ElementPlus] },
  });
}
beforeEach(() => {
  state.user = { id: "current-user", roles: ["STUDENT"] };
  actions.kind = "";
});
describe("AI loan cards", () => {
  it("offers photo return only for the current borrower", async () => {
    const wrapper = card("current-user");
    const button = wrapper
      .findAll("button")
      .find((b) => b.text().includes("上传照片归还"));
    expect(button).toBeTruthy();
    await button!.trigger("click");
    expect(actions.kind).toBe("return");
    expect(actions.item?.id).toBe("loan-1");
    wrapper.unmount();
  });
  it("does not offer returning someone else’s loan", () => {
    const wrapper = card("other-user");
    expect(wrapper.text()).not.toContain("上传照片归还");
    wrapper.unmount();
  });
  it("does not offer a second return for a completed loan", () => {
    const wrapper = card("current-user", "RETURNED");
    expect(wrapper.text()).not.toContain("上传照片归还");
    expect(wrapper.text()).toContain("已归还");
    wrapper.unmount();
  });
});
