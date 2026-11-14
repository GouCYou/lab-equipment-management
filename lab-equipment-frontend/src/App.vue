<script setup lang="ts">
import { ref, onMounted, computed, watch, onBeforeUnmount } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  state,
  api,
  has,
  staff,
  metadata,
  clearSession,
  label,
  moduleViewed,
  refreshModuleUpdates,
} from "./state";
import { sidebarExpanded, isDark, toggleTheme } from "./preferences";
import ThemeToggle from "./components/ThemeToggle.vue";
import Icon from "./components/Icon.vue";
import ActionHost from "./components/ActionHost.vue";
import ThemePopover from "./components/ThemePopover.vue";
import ThemeConfirm from "./components/ThemeConfirm.vue";
const route = useRoute(),
  router = useRouter(),
  expanded = sidebarExpanded,
  mobile = ref(false);
const nav = computed(() => [
  { path: "/chat", name: "AI 助手", icon: "chat" },
  { path: "/dashboard", name: "工作台", icon: "dashboard" },
  { path: "/equipment", name: "设备中心", icon: "equipment" },
  { path: "/reservations", name: "预约管理", icon: "calendar" },
  { path: "/loans", name: "借还记录", icon: "return" },
  { path: "/tickets", name: "故障工单", icon: "ticket" },
  { path: "/knowledge", name: "维修知识库", icon: "book" },
  ...(staff()
    ? [{ path: "/statistics", name: "统计分析", icon: "chart" }]
    : []),
]);
let moduleTimer: ReturnType<typeof setInterval> | undefined;
onMounted(() => {
  moduleTimer = setInterval(() => {
    if (state.token && document.visibilityState === "visible")
      void refreshModuleUpdates().catch(() => {});
  }, 5000);
});
onBeforeUnmount(() => clearInterval(moduleTimer));
watch(
  () => route.path,
  (path) => {
    if (state.ready && state.user) void moduleViewed(path.split("/")[1]);
  },
);
function visit(path: string) {
  mobile.value = false;
  if (path === route.path) state.revision++;
  void moduleViewed(path.split("/")[1]);
}
function newChat() {
  void router.push("/chat");
  window.dispatchEvent(new Event("new-chat"));
  mobile.value = false;
}
function accountAction(id: string) {
  if (id === "theme") toggleTheme();
  else if (id === "logout") void logout();
  else void router.push(id === "admin" ? "/admin" : "/profile");
}
async function logout() {
  mobile.value = false;
  try {
    await api.post("/auth/logout");
  } catch {}
  clearSession();
  await router.push("/login");
}
onMounted(async () => {
  if (state.token)
    try {
      state.user = (await api.get("/auth/me")).data;
      await metadata();
      if (route.path === "/login") await router.replace("/chat");
    } catch {
      clearSession();
      await router.replace("/login");
    }
  state.ready = true;
  if (state.user) void moduleViewed(route.path.split("/")[1]);
});
</script>
<template>
  <div v-if="!state.ready" class="boot">
    <div class="brand-mark"><Icon name="lab" :size="28" /></div>
    <span>正在打开博仪…</span>
  </div>
  <template v-else-if="route.meta.public"
    ><router-view /><ThemeToggle class="public-theme-toggle"
  /></template>
  <div v-else class="app-shell" :class="{ expanded, mobile }">
    <aside class="rail">
      <button
        class="brand"
        aria-label="博仪首页"
        @click="
          router.push('/chat');
          mobile = false;
        "
      >
        <span class="brand-mark"><Icon name="lab" :size="26" /></span
        ><strong v-if="expanded">博仪</strong></button
      ><button class="new-chat" title="新对话" @click="newChat">
        <Icon name="plus" /><span v-if="expanded">新对话</span>
      </button>
      <nav
        :style="{
          '--nav-index': Math.max(
            0,
            nav.findIndex((n) => n.path === route.path),
          ),
          '--nav-opacity': nav.some((n) => n.path === route.path) ? 1 : 0,
        }"
      >
        <router-link
          v-for="n in nav"
          :key="n.path"
          :to="n.path"
          :title="n.name"
          @click="visit(n.path)"
          ><Icon :name="n.icon" /><span v-if="expanded">{{ n.name }}</span
          ><i
            v-if="state.moduleUpdates[n.path.slice(1)]?.unread"
            class="module-dot"
            aria-label="有更新"
        /></router-link>
      </nav>
      <div class="rail-bottom">
        <ThemePopover
          placement="right-end"
          :items="[
            { id: 'profile', label: '个人设置', icon: 'user' },
            {
              id: 'theme',
              label: isDark ? '浅色模式' : '深色模式',
              icon: isDark ? 'sun' : 'moon',
            },
            ...(has('ADMIN')
              ? [
                  {
                    id: 'admin',
                    label: '系统管理',
                    dot: state.moduleUpdates.admin?.unread,
                    icon: 'settings',
                  },
                ]
              : []),
            {
              id: 'logout',
              label: '退出登录',
              icon: 'logout',
              divider: true,
              danger: true,
            },
          ]"
          @select="accountAction"
        >
          <template #trigger="{ toggle, open }"
            ><button
              class="account-button"
              title="账户菜单"
              :aria-expanded="open"
              @click="toggle"
            >
              <span class="avatar"><Icon name="user" /></span
              ><span v-if="expanded" class="account-name"
                >{{
                  state.user?.roles?.map(label).join(" / ") || state.user?.name
                }}<small>{{ state.user?.username }}</small></span
              >
            </button></template
          >
        </ThemePopover>
      </div>
    </aside>
    <button
      v-if="mobile"
      class="mobile-veil"
      aria-label="关闭导航"
      @click="mobile = false"
    />
    <main class="main-shell">
      <header v-if="route.path !== '/chat'" class="topbar">
        <div>
          <button
            class="icon-button mobile-toggle"
            aria-label="打开导航"
            @click="mobile = true"
          >
            <Icon name="menu" /></button
          ><button
            class="icon-button desktop-toggle"
            aria-label="切换导航"
            @click="expanded = !expanded"
          >
            <Icon name="sidebar" /></button
          ><span class="breadcrumb">{{ route.meta.title }}</span>
        </div>
        <button class="top-user" @click="router.push('/profile')">
          <span class="avatar small"><Icon name="user" :size="17" /></span
          >{{ state.user?.name }}
        </button>
      </header>
      <router-view v-slot="{ Component }"
        ><Transition name="page" mode="out-in"
          ><component
            :is="Component"
            :key="route.path"
            @toggle-nav="mobile = !mobile"
            @toggle-rail="expanded = !expanded" /></Transition
      ></router-view>
    </main>
    <ActionHost /><ThemeConfirm />
  </div>
</template>
