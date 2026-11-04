import { createRouter, createWebHistory } from "vue-router";
import { state, has, staff } from "./state";
export const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    {
      path: "/login",
      component: () => import("./views/Login.vue"),
      meta: { title: "登录", public: true },
    },
    { path: "/", redirect: "/chat" },
    {
      path: "/chat",
      component: () => import("./views/Chat.vue"),
      meta: { title: "AI 助手" },
    },
    {
      path: "/dashboard",
      component: () => import("./views/Dashboard.vue"),
      meta: { title: "工作台" },
    },
    {
      path: "/equipment",
      component: () => import("./views/Equipment.vue"),
      meta: { title: "设备中心" },
    },
    {
      path: "/reservations",
      component: () => import("./views/Records.vue"),
      props: { kind: "reservations" },
      meta: { title: "预约管理" },
    },
    {
      path: "/loans",
      component: () => import("./views/Records.vue"),
      props: { kind: "loans" },
      meta: { title: "借还记录" },
    },
    {
      path: "/tickets",
      component: () => import("./views/Tickets.vue"),
      meta: { title: "故障工单" },
    },
    {
      path: "/knowledge",
      component: () => import("./views/Knowledge.vue"),
      meta: { title: "维修知识库" },
    },
    {
      path: "/statistics",
      component: () => import("./views/Statistics.vue"),
      meta: { title: "统计分析", staff: true },
    },
    {
      path: "/notifications",
      component: () => import("./views/Notifications.vue"),
      meta: { title: "消息中心" },
    },
    {
      path: "/profile",
      component: () => import("./views/Profile.vue"),
      meta: { title: "个人设置" },
    },
    {
      path: "/admin",
      component: () => import("./views/Admin.vue"),
      meta: { title: "系统管理", admin: true },
    },
    { path: "/:pathMatch(.*)*", redirect: "/chat" },
  ],
});
router.beforeEach((to) => {
  if (!to.meta.public && !state.token) return "/login";
  if (state.user && to.meta.admin && !has("ADMIN")) return "/chat";
  if (state.user && to.meta.staff && !staff()) return "/chat";
});
