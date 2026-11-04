<script setup lang="ts">
import { ref, watch } from "vue";
import { useRouter } from "vue-router";
import { state, api, openBorrow, openReturn, date, manages } from "../state";
import type { Row } from "../state";
import Icon from "./Icon.vue";
import Badge from "./Badge.vue";
const props = defineProps<{ card: Row }>();
const data = ref<Row>(props.card.data);
const router = useRouter();
watch(
  () => props.card.data,
  (v) => (data.value = v),
  { deep: true },
);
watch(
  () => state.revision,
  async () => {
    try {
      const type = props.card.type;
      if (type === "loan")
        data.value = (await api.get("/loans/" + data.value.id)).data;
      else if (type === "ticket")
        data.value = (await api.get("/tickets/" + data.value.id)).data;
      else if (type === "equipment")
        data.value = (await api.get("/equipment/" + data.value.id)).data;
    } catch {}
  },
);
</script>
<template>
  <article class="business-card">
    <template v-if="card.type === 'equipment'"
      ><div class="business-card-top">
        <span class="object-icon"><Icon name="equipment" :size="23" /></span
        ><Badge :value="data.operationalStatus" />
      </div>
      <strong>{{ data.name }}</strong>
      <p>{{ data.assetNo }} · {{ data.model }}</p>
      <span class="card-meta"
        >{{ data.labName }}<span v-if="data.borrowed"> · 当前已借出</span></span
      >
      <div class="card-actions">
        <el-button
          size="small"
          type="primary"
          plain
          :disabled="data.operationalStatus !== 'NORMAL'"
          @click="openBorrow(data)"
          >申请借用</el-button
        ><el-button
          size="small"
          text
          @click="router.push('/equipment?id=' + data.id)"
          >查看设备<Icon name="arrow" :size="15"
        /></el-button></div></template
    ><template v-else-if="card.type === 'loan'"
      ><div class="business-card-top">
        <span class="object-icon"><Icon name="return" /></span
        ><Badge :value="data.status" />
      </div>
      <strong>{{ data.equipmentName }}</strong>
      <p>{{ data.assetNo }} · {{ data.userName }}</p>
      <span class="card-meta"
        >{{
          data.returnedAt
            ? "归还于 " + date(data.returnedAt)
            : "应归还 " + date(data.dueAt)
        }}<b v-if="data.overdue" class="danger-text"> · 已逾期</b></span
      >
      <div class="card-actions">
        <el-button
          v-if="!data.returnedAt && data.userId === state.user?.id"
          size="small"
          type="primary"
          @click="openReturn(data)"
          >上传照片归还</el-button
        ><el-button
          size="small"
          text
          @click="router.push('/loans?id=' + data.id)"
          >查看记录<Icon name="arrow" :size="15"
        /></el-button></div></template
    ><template v-else-if="card.type === 'ticket'"
      ><div class="business-card-top">
        <span class="object-icon"><Icon name="ticket" /></span
        ><Badge :value="data.status" />
      </div>
      <strong>{{ data.title }}</strong>
      <p>{{ data.equipmentName }} · {{ data.assetNo }}</p>
      <span class="card-meta">{{
        data.assigneeName
          ? "维修人员：" + data.assigneeName
          : "尚未分派维修人员"
      }}</span>
      <div class="card-actions">
        <el-button
          size="small"
          type="primary"
          plain
          @click="router.push('/tickets?id=' + data.id)"
          >{{ manages(data.labId) ? "查看与处理" : "查看进度" }}</el-button
        >
      </div></template
    ><template v-else-if="card.type === 'statistics'"
      ><div class="business-card-top">
        <span class="object-icon"><Icon name="chart" /></span
        ><span class="card-meta">近 {{ data.days }} 天</span>
      </div>
      <strong>设备利用率 {{ data.utilization }}%</strong>
      <p>借用 {{ data.loanCount }} 次 · 故障 {{ data.ticketCount }} 单</p>
      <span class="card-meta"
        >实际使用 {{ data.usedHours }} / 可开放
        {{ data.openHours }} 设备小时</span
      >
      <div class="card-actions">
        <el-button size="small" text @click="router.push('/statistics')"
          >查看统计分析<Icon name="arrow" :size="15"
        /></el-button></div></template
    ><template v-else-if="card.type === 'reservation'"
      ><div class="business-card-top">
        <span class="object-icon"><Icon name="calendar" /></span
        ><Badge :value="data.status" />
      </div>
      <strong>{{ data.equipmentName }}</strong>
      <p>{{ date(data.startsAt) }} — {{ date(data.endsAt) }}</p>
      <div class="card-actions">
        <el-button size="small" text @click="router.push('/reservations')"
          >查看预约<Icon name="arrow" :size="15"
        /></el-button></div
    ></template>
  </article>
</template>
