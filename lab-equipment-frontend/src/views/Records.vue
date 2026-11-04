<script setup lang="ts">
import { ref, computed, onMounted, watch } from "vue";
import { useRoute } from "vue-router";
import { ElMessage } from "element-plus";
import { dialogs as ElMessageBox } from "../dialogs";
import {
  api,
  post,
  state,
  manages,
  fail,
  date,
  label,
  changed,
  openReturn,
  openReport,
} from "../state";
import type { Row } from "../state";
import Icon from "../components/Icon.vue";
import Badge from "../components/Badge.vue";
import Photos from "../components/Photos.vue";
const props = defineProps<{ kind: "reservations" | "loans" }>();
const route = useRoute(),
  rows = ref<Row[]>([]),
  search = ref(""),
  status = ref(""),
  loading = ref(false),
  acting = ref(""),
  detail = ref<Row | null>(null),
  error = ref(false);
const reservations = computed(() => props.kind === "reservations");
const visible = computed(() =>
  rows.value.filter(
    (r) =>
      (!status.value ||
        (status.value === "OVERDUE"
          ? r.overdue
          : status.value === "UNCHECKED"
            ? r.returnedAt && !r.checkedAt
            : r.status === status.value)) &&
      [r.equipmentName, r.assetNo, r.userName, r.purpose].some((x) =>
        String(x || "").includes(search.value),
      ),
  ),
);
async function load() {
  loading.value = true;
  try {
    rows.value = (await api.get("/" + props.kind)).data;
    error.value = false;
  } catch (e) {
    error.value = true;
    fail(e);
  } finally {
    loading.value = false;
  }
}
async function action(r: Row, action: string) {
  acting.value = r.id;
  try {
    let body: Row = {};
    if (action === "reject") {
      const result = await ElMessageBox.prompt("请输入驳回原因", "驳回预约", {
        confirmButtonText: "确认驳回",
        cancelButtonText: "取消",
        inputValidator: (v) => !!v?.trim() || "请填写原因",
      });
      body.reason = result.value;
    } else if (action === "cancel")
      await ElMessageBox.confirm("确认取消这条预约？", "取消预约", {
        confirmButtonText: "确认取消",
        cancelButtonText: "保留预约",
      });
    await post("/" + props.kind + "/" + r.id + "/" + action, body);
    ElMessage.success(
      (
        {
          approve: "预约已批准",
          reject: "预约已驳回",
          cancel: "预约已取消",
          issue: "设备已发放",
          check: "归还检查已记录",
        } as Record<string, string>
      )[action],
    );
    changed();
  } catch (e) {
    if (e !== "cancel" && e !== "close") fail(e);
  } finally {
    acting.value = "";
  }
}
async function show(r: Row) {
  if (reservations.value) {
    detail.value = r;
    return;
  }
  try {
    detail.value = (await api.get("/loans/" + r.id)).data;
  } catch (e) {
    fail(e);
  }
}
watch(() => state.revision, load);
onMounted(async () => {
  await load();
  if (route.query.id) {
    const r = rows.value.find((r) => r.id === route.query.id);
    if (r) await show(r);
  }
});
</script>
<template>
  <div class="page" v-loading="loading">
    <div class="page-heading">
      <div>
        <h1>{{ reservations ? "预约管理" : "借还记录" }}</h1>
      </div>
      <router-link to="/equipment"
        ><el-button type="primary"
          ><Icon name="plus" />申请借用</el-button
        ></router-link
      >
    </div>
    <div class="toolbar">
      <el-input
        v-model="search"
        clearable
        placeholder="搜索设备、资产编号或借用人"
        class="search-input"
        ><template #prefix
          ><Icon name="search" :size="18" /></template></el-input
      ><el-select
        v-model="status"
        clearable
        placeholder="全部状态"
        class="filter-select"
        ><template v-if="reservations"
          ><el-option
            v-for="s in [
              'PENDING',
              'APPROVED',
              'FULFILLED',
              'REJECTED',
              'CANCELLED',
              'EXPIRED',
              'INVALIDATED',
            ]"
            :key="s"
            :value="s"
            :label="
              (
                {
                  PENDING: '待审核',
                  APPROVED: '已批准',
                  FULFILLED: '已发放',
                  REJECTED: '已驳回',
                  CANCELLED: '已取消',
                  EXPIRED: '已过期',
                  INVALIDATED: '已失效',
                } as Record<string, string>
              )[s]
            " /></template
        ><template v-else
          ><el-option value="BORROWED" label="借用中" /><el-option
            value="RETURNED"
            label="已归还" /><el-option
            value="OVERDUE"
            label="已逾期" /><el-option
            value="UNCHECKED"
            label="归还后待检查" /></template></el-select
      ><span class="result-count">{{ visible.length }} 条记录</span>
    </div>
    <div v-if="error" class="inline-error">
      记录加载失败 <button class="text-button" @click="load">重试</button>
    </div>
    <div v-else class="panel table-scroll">
      <table class="records-table">
        <thead>
          <tr>
            <th>设备与编号</th>
            <th>借用人</th>
            <th>{{ reservations ? "预约时段" : "借出 / 应归还" }}</th>
            <th>{{ reservations ? "借用用途" : "归还情况" }}</th>
            <th>状态</th>
            <th class="actions-column">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in visible" :key="r.id">
            <td>
              <button class="text-button row-title" @click="show(r)">
                {{ r.equipmentName }}</button
              ><small>{{ r.assetNo }} · {{ r.labName }}</small>
            </td>
            <td>{{ r.userName }}</td>
            <td>
              <span>{{ date(reservations ? r.startsAt : r.issuedAt) }}</span
              ><small>至 {{ date(reservations ? r.endsAt : r.dueAt) }}</small>
            </td>
            <td>
              <span v-if="reservations" class="truncate" :title="r.purpose">{{
                r.purpose
              }}</span
              ><template v-else
                ><span :class="{ 'danger-text': r.overdue }">{{
                  r.returnedAt
                    ? date(r.returnedAt)
                    : r.overdue
                      ? "已逾期"
                      : "尚未归还"
                }}</span
                ><small v-if="r.returnedAt">{{
                  r.checkedAt ? "已完成检查" : "待实验员检查"
                }}</small></template
              >
            </td>
            <td><Badge :value="r.status" /></td>
            <td>
              <div class="row-actions" v-loading="acting === r.id">
                <template v-if="reservations"
                  ><template
                    v-if="
                      r.status === 'PENDING' &&
                      manages(r.labId) &&
                      r.userId !== state.user?.id
                    "
                    ><el-button
                      size="small"
                      type="primary"
                      plain
                      @click="action(r, 'approve')"
                      >批准</el-button
                    ><el-button size="small" text @click="action(r, 'reject')"
                      >驳回</el-button
                    ></template
                  ><el-button
                    v-if="r.status === 'APPROVED' && manages(r.labId)"
                    size="small"
                    type="primary"
                    @click="action(r, 'issue')"
                    >发放设备</el-button
                  ><el-button
                    v-if="
                      ['PENDING', 'APPROVED'].includes(r.status) &&
                      r.userId === state.user?.id
                    "
                    size="small"
                    text
                    @click="action(r, 'cancel')"
                    >取消预约</el-button
                  ></template
                ><template v-else
                  ><el-button
                    v-if="!r.returnedAt && r.userId === state.user?.id"
                    size="small"
                    type="primary"
                    @click="openReturn(r)"
                    >照片归还</el-button
                  ><el-button
                    v-if="r.returnedAt && !r.checkedAt && manages(r.labId)"
                    size="small"
                    type="primary"
                    plain
                    @click="action(r, 'check')"
                    >检查完成</el-button
                  ></template
                ><el-button size="small" text @click="show(r)">详情</el-button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="!visible.length" class="empty-state large">
        <Icon :name="reservations ? 'calendar' : 'return'" :size="34" />
        <h3>暂无符合条件的记录</h3>
        <p>可前往设备中心申请借用，或调整筛选条件。</p>
      </div>
    </div>
    <el-drawer
      :model-value="!!detail"
      :title="reservations ? '预约详情' : '借还详情'"
      size="min(560px, 100%)"
      @close="detail = null"
      ><div v-if="detail" class="detail-body">
        <h2>{{ detail.equipmentName }}</h2>
        <p class="muted">{{ detail.assetNo }} · {{ detail.labName }}</p>
        <div class="detail-badges"><Badge :value="detail.status" /></div>
        <dl class="detail-list">
          <dt>单据编号</dt>
          <dd>{{ detail.reservationNo || detail.loanNo }}</dd>
          <dt>借用人</dt>
          <dd>{{ detail.userName }}</dd>
          <dt>开始时间</dt>
          <dd>{{ date(detail.startsAt || detail.issuedAt) }}</dd>
          <dt>归还期限</dt>
          <dd>{{ date(detail.endsAt || detail.dueAt) }}</dd>
          <dt v-if="detail.returnedAt">归还时间</dt>
          <dd v-if="detail.returnedAt">{{ date(detail.returnedAt) }}</dd>
        </dl>
        <section class="detail-section">
          <h3>{{ reservations ? "借用用途" : "归还备注" }}</h3>
          <p>{{ detail.purpose || detail.returnNote || "暂无备注" }}</p>
          <p v-if="detail.reason" class="danger-text">{{ detail.reason }}</p>
        </section>
        <section v-if="detail.returnPhotoIds?.length" class="detail-section">
          <h3>归还照片</h3>
          <Photos :ids="detail.returnPhotoIds" />
        </section>
        <section v-if="detail.events?.length" class="detail-section">
          <h3>操作记录</h3>
          <div
            v-for="event in detail.events"
            :key="event.id"
            class="timeline-item"
          >
            <span class="timeline-dot" />
            <div>
              <strong>{{ label(event.action) }}</strong>
              <p>{{ event.note }}</p>
              <small
                >{{ event.actorName || "系统" }} ·
                {{ date(event.createdAt) }}</small
              >
            </div>
          </div>
        </section>
        <div v-if="!reservations" class="detail-actions">
          <el-button
            v-if="!detail.returnedAt && detail.userId === state.user?.id"
            type="primary"
            @click="openReturn(detail)"
            >照片归还</el-button
          ><el-button
            @click="
              openReport({
                id: detail.equipmentId,
                equipmentId: detail.equipmentId,
                loanId: detail.id,
              })
            "
            >报告故障</el-button
          >
        </div>
      </div></el-drawer
    >
  </div>
</template>
