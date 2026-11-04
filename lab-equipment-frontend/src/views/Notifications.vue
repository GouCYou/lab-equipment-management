<script setup lang="ts">
import { ref, onMounted, watch } from "vue";
import { useRouter } from "vue-router";
import { api, post, state, fail, date, changed } from "../state";
import type { Row } from "../state";
import Icon from "../components/Icon.vue";
const router = useRouter(),
  rows = ref<Row[]>([]),
  loading = ref(false);
async function load() {
  loading.value = true;
  try {
    rows.value = (await api.get("/notifications")).data;
  } catch (e) {
    fail(e);
  } finally {
    loading.value = false;
  }
}
async function read(n: Row) {
  try {
    await post("/notifications/" + n.id + "/read");
    changed();
    if (n.businessType === "TICKET")
      await router.push("/tickets?id=" + n.businessId);
    else if (n.businessType === "LOAN")
      await router.push("/loans?id=" + n.businessId);
    else if (n.businessType === "RESERVATION")
      await router.push("/reservations");
  } catch (e) {
    fail(e);
  }
}
async function all() {
  try {
    await post("/notifications/read-all");
    changed();
  } catch (e) {
    fail(e);
  }
}
watch(() => state.revision, load);
onMounted(load);
</script>
<template>
  <div class="page narrow-page" v-loading="loading">
    <div class="page-heading">
      <div>
        <h1>消息中心</h1>
      </div>
      <el-button :disabled="!rows.some((r) => !r.readAt)" @click="all"
        >全部标记已读</el-button
      >
    </div>
    <div class="panel">
      <button
        v-for="n in rows"
        :key="n.id"
        class="notification-row"
        :class="{ unread: !n.readAt }"
        @click="read(n)"
      >
        <span class="notification-icon"
          ><Icon
            :name="
              n.businessType === 'TICKET'
                ? 'ticket'
                : n.businessType === 'LOAN'
                  ? 'return'
                  : 'calendar'
            "
        /></span>
        <div>
          <strong>{{ n.title }}</strong>
          <p>{{ n.content }}</p>
          <small>{{ date(n.createdAt) }}</small>
        </div>
        <i v-if="!n.readAt" class="unread-dot" />
      </button>
      <div v-if="!rows.length && !loading" class="empty-state large">
        <Icon name="bell" :size="36" />
        <h3>还没有消息</h3>
        <p>业务进度发生变化时，会在这里通知你。</p>
      </div>
    </div>
  </div>
</template>
