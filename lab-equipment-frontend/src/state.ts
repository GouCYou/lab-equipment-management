import { requestId } from "./request-id";
import { relativeDate } from "./time";
import { reactive } from "vue";
import axios from "axios";
import { ElMessage } from "element-plus";
export type Row = Record<string, any>;
export const state = reactive({
  token: sessionStorage.getItem("lab-token") || "",
  user: null as Row | null,
  ready: false,
  labs: [] as Row[],
  categories: [] as Row[],
  unread: 0,
  moduleUpdates: {} as Record<string, { fingerprint: string; unread: boolean }>,
  revision: 0,
  mode: "real",
});
export const api = axios.create({ baseURL: "/api", timeout: 30000 });
api.interceptors.request.use((c) => {
  if (state.token) c.headers.Authorization = "Bearer " + state.token;
  return c;
});
api.interceptors.response.use(
  (r) => r,
  (e) => {
    if (e.response?.status === 401 && state.user) {
      clearSession();
      window.location.assign("/login");
    }
    return Promise.reject(e);
  },
);
export function clearSession() {
  state.token = "";
  state.user = null;
  state.moduleUpdates = {};
  sessionStorage.removeItem("lab-token");
}
export async function login(username: string, password: string) {
  const { data } = await api.post("/auth/login", { username, password });
  state.token = data.token;
  state.user = data.user;
  sessionStorage.setItem("lab-token", data.token);
  await metadata();
}
export async function metadata() {
  const [labs, categories, messages, updates] = await Promise.all([
    api.get("/laboratories"),
    api.get("/categories"),
    api.get("/notifications"),
    api.get("/module-updates"),
  ]);
  state.moduleUpdates = updates.data;
  state.labs = labs.data;
  state.categories = categories.data;
  state.unread = messages.data.filter((m: Row) => !m.readAt).length;
}
export async function refreshModuleUpdates() {
  const token = state.token;
  if (!token) return;
  const { data } = await api.get("/module-updates");
  if (state.token === token) state.moduleUpdates = data;
}
export async function moduleViewed(module: string) {
  const token = state.token;
  if (!token) return;
  try {
    await refreshModuleUpdates();
    const value = state.moduleUpdates[module];
    if (!value || state.token !== token || !value.unread) return;
    await api.post("/module-updates/" + module + "/read", {
      fingerprint: value.fingerprint,
    });
    if (
      state.token === token &&
      state.moduleUpdates[module]?.fingerprint === value.fingerprint
    )
      value.unread = false;
  } catch {
    /* A failed check keeps the indicator pending for the next refresh. */
  }
}
export function has(role: string) {
  return !!state.user?.roles?.includes(role);
}
export function staff() {
  return has("ADMIN") || has("LAB_STAFF");
}
export function manages(labId: string) {
  return (
    has("ADMIN") || (has("LAB_STAFF") && state.user?.labIds?.includes(labId))
  );
}
export function fail(error: any) {
  ElMessage.error(
    error.response?.data?.message || error.message || "请求失败，请稍后重试",
  );
}
export function changed() {
  state.revision++;
  void metadata().catch(() => {});
}
export async function post(
  path: string,
  body: Row = {},
  key: string = requestId(),
) {
  return (await api.post(path, body, { headers: { "Idempotency-Key": key } }))
    .data;
}
export const labels: Record<string, string> = {
  LOGIN: "登录",
  CHANGE_PASSWORD: "修改密码",
  SAVE_USER: "保存账户",
  SAVE_LAB: "保存实验室",
  SAVE_EQUIPMENT: "保存设备档案",
  TOGGLE_EQUIPMENT: "启用或停用设备",
  SAVE_KNOWLEDGE: "保存资料版本",
  SAVE_AI_SETTINGS: "更新模型配置",
  KNOWLEDGE_index: "建立知识索引",
  KNOWLEDGE_publish: "审核发布资料",
  KNOWLEDGE_toggle: "启用或停用资料",
  TICKET_verify: "核实故障",
  TICKET_reject: "驳回报修",
  TICKET_cancel: "撤回报修",
  TICKET_assign: "分派维修",
  TICKET_accept: "受理工单",
  "TICKET_start-repair": "开始维修",
  TICKET_note: "补充维修记录",
  "TICKET_return-assignment": "退回分派",
  "TICKET_submit-acceptance": "提交维修验收",
  TICKET_acceptance: "处理维修验收",
  NORMAL: "正常",
  MAINTENANCE: "维修中",
  DISABLED: "已停用",
  PENDING: "待审核",
  APPROVED: "已批准",
  REJECTED: "已驳回",
  CANCELLED: "已取消",
  EXPIRED: "已过期",
  INVALIDATED: "已失效",
  FULFILLED: "已发放",
  BORROWED: "借用中",
  RETURNED: "已归还",
  SUBMITTED: "待核实",
  VERIFIED: "已核实",
  ASSIGNED: "待受理",
  ACCEPTED: "已受理",
  REPAIRING: "维修中",
  PENDING_ACCEPTANCE: "待验收",
  CLOSED: "已关闭",
  UNINDEXED: "待索引",
  INDEXING: "索引中",
  READY: "索引完成",
  FAILED: "失败",
  QUEUED: "排队中",
  RUNNING: "处理中",
  SUCCEEDED: "已完成",
  INTERRUPTED: "已中断",
  ADMIN: "管理员",
  STUDENT: "学生",
  LAB_STAFF: "实验员",
  TECHNICIAN: "维修人员",
  PUBLIC: "公开",
  LAB: "实验室内",
  MAINTENANCE_ONLY: "维修资料",
};
export function label(value: string) {
  return labels[value] || value;
}
export function date(value: unknown, full = true) {
  return relativeDate(value, full);
}
export function statusClass(s: string) {
  if (
    ["DISABLED", "REJECTED", "FAILED", "INVALIDATED", "INTERRUPTED"].includes(s)
  )
    return "danger";
  if (
    [
      "PENDING",
      "SUBMITTED",
      "ASSIGNED",
      "REPAIRING",
      "PENDING_ACCEPTANCE",
      "MAINTENANCE",
      "INDEXING",
      "RUNNING",
    ].includes(s)
  )
    return "warn";
  if (["EXPIRED", "CANCELLED", "UNINDEXED"].includes(s)) return "muted";
  return "blue";
}
export async function photoUrl(id: string) {
  const { data } = await api.get("/files/" + id, { responseType: "blob" });
  return URL.createObjectURL(data);
}
export const actions = reactive({
  kind: "" as "" | "borrow" | "return" | "report",
  item: null as Row | null,
});
export function openBorrow(item: Row) {
  actions.item = item;
  actions.kind = "borrow";
}
export function openReturn(item: Row) {
  actions.item = item;
  actions.kind = "return";
}
export function openReport(item: Row | null = null) {
  actions.item = item;
  actions.kind = "report";
}
