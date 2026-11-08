<script setup lang="ts">
import { ref, onMounted, watch } from "vue";
import { useRouter } from "vue-router";
import { api, state, staff, fail, date, openReport } from "../state";
import type { Row } from "../state";
import Icon from "../components/Icon.vue";
import Badge from "../components/Badge.vue";
const router = useRouter(),
  data = ref<Row>({}),
  loading = ref(true),
  error = ref(false);
async function load() {
  loading.value = true;
  try {
    data.value = (await api.get("/dashboard")).data;
    error.value = false;
  } catch (e) {
    error.value = true;
    fail(e);
  } finally {
    loading.value = false;
  }
}
onMounted(load);
watch(() => state.revision, load);
</script>
<template>
  <div class="page" v-loading="loading">
    <div class="page-heading">
      <div>
        <h1>{{ state.user?.name }}，你好</h1>
      </div>
      <el-button type="primary" @click="router.push('/chat')"
        ><Icon name="chat" /><span>问问 AI</span></el-button
      >
    </div>
    <div v-if="error" class="inline-error">
      工作台加载失败 <button class="text-button" @click="load">重试</button>
    </div>
    <template v-else
      ><div class="stats-grid">
        <button class="stat-card" @click="router.push('/equipment')">
          <span class="stat-icon violet"
            ><Icon name="equipment" :size="25" /></span
          ><span
            ><small>可借设备 / 全部设备</small
            ><strong
              >{{ data.availableCount || 0
              }}<em>/ {{ data.equipmentCount || 0 }}</em></strong
            ></span
          ></button
        ><button class="stat-card" @click="router.push('/loans')">
          <span class="stat-icon blue"><Icon name="return" :size="25" /></span
          ><span
            ><small>{{ staff() ? "当前借出" : "我的借用" }}</small
            ><strong>{{ data.activeLoans || 0 }}<em>台</em></strong></span
          ></button
        ><button class="stat-card" @click="router.push('/reservations')">
          <span class="stat-icon amber"
            ><Icon name="calendar" :size="25" /></span
          ><span
            ><small>待审核预约</small
            ><strong
              >{{ data.pendingReservations || 0 }}<em>条</em></strong
            ></span
          ></button
        ><button class="stat-card" @click="router.push('/tickets')">
          <span class="stat-icon rose"><Icon name="ticket" :size="25" /></span
          ><span
            ><small>处理中工单</small
            ><strong>{{ data.openTickets || 0 }}<em>单</em></strong></span
          >
        </button>
      </div>
      <div v-if="data.overdueCount" class="overdue-strip">
        <Icon name="clock" /><span
          >有 {{ data.overdueCount }} 台设备已逾期，请及时处理归还。</span
        ><button class="text-button" @click="router.push('/loans')">
          查看记录 <Icon name="arrow" :size="16" />
        </button>
      </div>
      <section class="section">
        <div class="section-heading"><h2>常用操作</h2></div>
        <div class="quick-grid">
          <button @click="router.push('/equipment')">
            <span class="quick-icon violet"
              ><Icon name="equipment" :size="27"
            /></span>
            <div>
              <strong>查找与借用设备</strong>
              <p>查看设备状态和可预约时间</p>
            </div>
            <Icon name="arrow" /></button
          ><button @click="router.push('/loans')">
            <span class="quick-icon blue"
              ><Icon name="return" :size="27"
            /></span>
            <div>
              <strong>上传照片归还</strong>
              <p>核对设备与配件后完成归还</p>
            </div>
            <Icon name="arrow" /></button
          ><button @click="openReport()">
            <span class="quick-icon rose"
              ><Icon name="ticket" :size="27"
            /></span>
            <div>
              <strong>提交故障报修</strong>
              <p>记录现象，追踪维修进度</p>
            </div>
            <Icon name="arrow" />
          </button>
        </div>
      </section>
      <section class="section">
        <div class="section-heading">
          <h2>最近借还</h2>
          <router-link to="/loans"
            >全部记录 <Icon name="arrow" :size="16"
          /></router-link>
        </div>
        <div class="panel table-scroll">
          <table>
            <thead>
              <tr>
                <th>设备</th>
                <th>借用人</th>
                <th>归还期限</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="l in data.recentLoans" :key="l.id">
                <td>
                  <strong>{{ l.equipmentName }}</strong
                  ><small>{{ l.assetNo }}</small>
                </td>
                <td>{{ l.userName }}</td>
                <td>{{ date(l.dueAt) }}</td>
                <td><Badge :value="l.status" /></td>
              </tr>
            </tbody>
          </table>
          <div v-if="!data.recentLoans?.length" class="empty-state">
            <Icon name="return" :size="32" />
            <h3>还没有借还记录</h3>
            <p>发放或归还设备后，会在这里留下记录。</p>
          </div>
        </div>
      </section>
      <section class="section">
        <div class="section-heading">
          <h2>最近工单</h2>
          <router-link to="/tickets"
            >全部工单 <Icon name="arrow" :size="16"
          /></router-link>
        </div>
        <div class="panel">
          <button
            v-for="t in data.recentTickets"
            :key="t.id"
            class="ticket-summary"
            @click="router.push('/tickets?id=' + t.id)"
          >
            <span class="object-icon"><Icon name="ticket" /></span>
            <div>
              <strong>{{ t.title }}</strong>
              <p>{{ t.equipmentName }} · {{ t.assigneeName || "待分派" }}</p>
            </div>
            <Badge :value="t.status" /><Icon name="arrow" :size="17" />
          </button>
          <div v-if="!data.recentTickets?.length" class="empty-state">
            <Icon name="ticket" :size="32" />
            <h3>暂无故障工单</h3>
          </div>
        </div>
      </section></template
    >
  </div>
</template>
