<script setup lang="ts">
import { ref, computed, onMounted, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import {
  api,
  post,
  state,
  manages,
  staff,
  fail,
  date,
  changed,
  openReport,
  label,
} from "../state";
import type { Row } from "../state";
import Icon from "../components/Icon.vue";
import Badge from "../components/Badge.vue";
import Photos from "../components/Photos.vue";
const router = useRouter(),
  route = useRoute(),
  rows = ref<Row[]>([]),
  search = ref(""),
  status = ref(""),
  lab = ref(""),
  loading = ref(false),
  detail = ref<Row | null>(null),
  dialog = ref(""),
  form = ref<Row>({}),
  technicians = ref<Row[]>([]),
  busy = ref(false),
  error = ref(false);
const statuses = [
  "SUBMITTED",
  "VERIFIED",
  "ASSIGNED",
  "ACCEPTED",
  "REPAIRING",
  "PENDING_ACCEPTANCE",
  "CLOSED",
  "REJECTED",
  "CANCELLED",
];
const visible = computed(() =>
  rows.value.filter(
    (t) =>
      (!status.value || t.status === status.value) &&
      (!lab.value || t.labId === lab.value) &&
      [t.title, t.ticketNo, t.equipmentName, t.assetNo, t.assigneeName].some(
        (v) => String(v || "").includes(search.value),
      ),
  ),
);
const titles: Record<string, string> = {
  verify: "核实故障",
  reject: "驳回报修",
  assign: "分派维修",
  accept: "受理工单",
  "start-repair": "开始维修",
  "submit-acceptance": "提交维修验收",
  acceptance: "维修验收",
  note: "补充维修记录",
  cancel: "撤回报修",
  "return-assignment": "退回分派",
};
async function load() {
  loading.value = true;
  try {
    rows.value = (await api.get("/tickets")).data;
    error.value = false;
  } catch (e) {
    error.value = true;
    fail(e);
  } finally {
    loading.value = false;
  }
}
async function show(id: string) {
  try {
    detail.value = (await api.get("/tickets/" + id)).data;
  } catch (e) {
    fail(e);
  }
}
async function choose(action: string) {
  dialog.value = action;
  form.value = {
    affectsUse: true,
    assigneeId: "",
    note: "",
    resolution: "",
    laborMinutes: 0,
    costAmount: 0,
    passed: true,
  };
  if (action === "assign")
    try {
      technicians.value = (await api.get("/technicians")).data;
    } catch (e) {
      fail(e);
    }
}
async function submit() {
  if (!detail.value) return;
  busy.value = true;
  try {
    detail.value = await post(
      "/tickets/" + detail.value.id + "/" + dialog.value,
      form.value,
    );
    dialog.value = "";
    ElMessage.success("工单已更新");
    changed();
  } catch (e) {
    fail(e);
  } finally {
    busy.value = false;
  }
}
function eventLabel(value: string) {
  const [action, state] = value.split(" → ");
  return titles[action]
    ? titles[action] + (state ? " · " + label(state) : "")
    : value;
}
watch(
  () => state.revision,
  async () => {
    await load();
    if (detail.value) await show(detail.value.id);
  },
);
watch(
  () => route.query.id,
  (id) => {
    if (id) void show(String(id));
  },
);
onMounted(() => {
  void load();
  if (route.query.id) void show(String(route.query.id));
});
</script>
<template>
  <div class="page" v-loading="loading">
    <div class="page-heading">
      <div>
        <h1>故障工单</h1>
      </div>
      <el-button type="primary" @click="openReport()"
        ><Icon name="plus" />提交报修</el-button
      >
    </div>
    <div class="ticket-counts">
      <button @click="status = ''" :class="{ selected: !status }">
        <strong>{{ rows.length }}</strong
        ><span>全部工单</span></button
      ><button
        @click="status = 'SUBMITTED'"
        :class="{ selected: status === 'SUBMITTED' }"
      >
        <strong>{{
          rows.filter((t) => t.status === "SUBMITTED").length
        }}</strong
        ><span>待核实</span></button
      ><button
        @click="status = 'REPAIRING'"
        :class="{ selected: status === 'REPAIRING' }"
      >
        <strong>{{
          rows.filter((t) =>
            ["ASSIGNED", "ACCEPTED", "REPAIRING"].includes(t.status),
          ).length
        }}</strong
        ><span>待处理 / 维修中</span></button
      ><button
        @click="status = 'PENDING_ACCEPTANCE'"
        :class="{ selected: status === 'PENDING_ACCEPTANCE' }"
      >
        <strong>{{
          rows.filter((t) => t.status === "PENDING_ACCEPTANCE").length
        }}</strong
        ><span>待验收</span>
      </button>
    </div>
    <div class="toolbar">
      <el-input
        v-model="search"
        clearable
        placeholder="搜索标题、设备或工单编号"
        class="search-input"
        ><template #prefix
          ><Icon name="search" :size="18" /></template></el-input
      ><el-select
        v-model="status"
        clearable
        placeholder="全部状态"
        class="filter-select"
        ><el-option
          v-for="s in statuses"
          :key="s"
          :value="s"
          :label="label(s)" /></el-select
      ><el-select
        v-model="lab"
        clearable
        placeholder="全部实验室"
        class="filter-select"
        ><el-option
          v-for="l in state.labs"
          :key="l.id"
          :value="l.id"
          :label="l.name" /></el-select
      ><span class="result-count">{{ visible.length }} 张工单</span>
    </div>
    <div v-if="error" class="inline-error">
      工单加载失败 <button class="text-button" @click="load">重试</button>
    </div>
    <div v-else class="panel table-scroll">
      <table>
        <thead>
          <tr>
            <th>故障与设备</th>
            <th>工单编号</th>
            <th>维修人员</th>
            <th>状态</th>
            <th>提交时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in visible" :key="t.id">
            <td>
              <button class="text-button row-title" @click="show(t.id)">
                {{ t.title
                }}<span v-if="t.severity === 'URGENT'" class="urgent-dot"
                  >紧急</span
                ></button
              ><small>{{ t.equipmentName }} · {{ t.assetNo }}</small>
            </td>
            <td>{{ t.ticketNo }}</td>
            <td>{{ t.assigneeName || "待分派" }}</td>
            <td><Badge :value="t.status" /></td>
            <td>{{ date(t.createdAt) }}</td>
            <td>
              <el-button size="small" text @click="show(t.id)"
                >查看{{
                  manages(t.labId) || t.assigneeId === state.user?.id
                    ? "与处理"
                    : ""
                }}</el-button
              >
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="!visible.length" class="empty-state large">
        <Icon name="ticket" :size="34" />
        <h3>暂无符合条件的工单</h3>
        <p>设备出现异常时，可提交故障报修。</p>
      </div>
    </div>
    <el-drawer
      :model-value="!!detail"
      title="工单详情"
      size="min(660px, 100%)"
      @close="detail = null"
      ><div v-if="detail" class="detail-body">
        <div class="detail-title">
          <div>
            <span class="eyebrow">{{ detail.ticketNo }}</span>
            <h2>{{ detail.title }}</h2>
            <p>
              {{ detail.equipmentName }} · {{ detail.assetNo }} ·
              {{ detail.labName }}
            </p>
          </div>
        </div>
        <div class="detail-badges">
          <Badge :value="detail.status" /><span
            v-if="detail.affectsUse"
            class="danger-text"
            >影响设备使用</span
          >
        </div>
        <dl class="detail-list">
          <dt>报修人</dt>
          <dd>{{ detail.reporterName }}</dd>
          <dt>报修时间</dt>
          <dd>{{ date(detail.createdAt) }}</dd>
          <dt>维修人员</dt>
          <dd>{{ detail.assigneeName || "尚未分派" }}</dd>
          <dt>工时 / 费用</dt>
          <dd>{{ detail.laborMinutes }} 分钟 / ¥{{ detail.costAmount }}</dd>
        </dl>
        <section class="detail-section">
          <h3>故障描述</h3>
          <p class="pre-wrap">{{ detail.description }}</p>
          <Photos :ids="detail.photoIds" />
        </section>
        <section v-if="detail.resolution" class="detail-section">
          <h3>维修处理方案</h3>
          <p class="pre-wrap">{{ detail.resolution }}</p>
        </section>
        <div class="detail-actions">
          <template v-if="manages(detail.labId)"
            ><el-button
              v-if="detail.status === 'SUBMITTED'"
              type="primary"
              @click="choose('verify')"
              >核实故障</el-button
            ><el-button
              v-if="detail.status === 'SUBMITTED'"
              @click="choose('reject')"
              >驳回</el-button
            ><el-button
              v-if="
                ['VERIFIED', 'ASSIGNED', 'ACCEPTED', 'REPAIRING'].includes(
                  detail.status,
                )
              "
              type="primary"
              @click="choose('assign')"
              >{{ detail.assigneeId ? "重新分派" : "分派维修" }}</el-button
            ><el-button
              v-if="
                detail.status === 'PENDING_ACCEPTANCE' &&
                detail.assigneeId !== state.user?.id
              "
              type="primary"
              @click="choose('acceptance')"
              >维修验收</el-button
            ></template
          ><template v-if="detail.assigneeId === state.user?.id"
            ><el-button
              v-if="detail.status === 'ASSIGNED'"
              type="primary"
              @click="choose('accept')"
              >受理工单</el-button
            ><el-button
              v-if="['ASSIGNED', 'ACCEPTED'].includes(detail.status)"
              @click="choose('return-assignment')"
              >退回分派</el-button
            ><el-button
              v-if="detail.status === 'ACCEPTED'"
              type="primary"
              @click="choose('start-repair')"
              >开始维修</el-button
            ><template v-if="detail.status === 'REPAIRING'"
              ><el-button type="primary" @click="choose('submit-acceptance')"
                >提交验收</el-button
              ><el-button @click="choose('note')">补充记录</el-button></template
            ></template
          ><el-button
            v-if="
              detail.status === 'SUBMITTED' &&
              detail.reporterId === state.user?.id
            "
            @click="choose('cancel')"
            >撤回报修</el-button
          ><el-button @click="router.push('/chat?ticket=' + detail.id)"
            ><Icon name="spark" />AI 分析</el-button
          >
        </div>
        <section class="detail-section">
          <h3>处理时间线</h3>
          <div
            v-for="event in detail.events"
            :key="event.id"
            class="timeline-item"
          >
            <span class="timeline-dot" />
            <div>
              <strong>{{ eventLabel(event.action) }}</strong>
              <p class="pre-wrap">{{ event.note }}</p>
              <small
                >{{ event.actorName || "系统" }} ·
                {{ date(event.createdAt) }}</small
              >
            </div>
          </div>
        </section>
      </div></el-drawer
    ><el-dialog
      :model-value="!!dialog"
      :title="titles[dialog]"
      width="540px"
      @close="!busy && (dialog = '')"
      ><form class="form-stack" @submit.prevent="submit">
        <label v-if="dialog === 'verify'"
          >是否影响使用<el-radio-group v-model="form.affectsUse"
            ><el-radio :value="true">影响使用，暂停借出</el-radio
            ><el-radio :value="false">不影响使用</el-radio></el-radio-group
          ></label
        ><label v-if="dialog === 'assign'"
          >维修人员<el-select v-model="form.assigneeId"
            ><el-option
              v-for="t in technicians"
              :key="t.id"
              :value="t.id"
              :label="t.name" /></el-select></label
        ><template v-if="dialog === 'submit-acceptance'"
          ><label
            >检查与维修结论<el-input
              v-model="form.resolution"
              type="textarea"
              :rows="5"
              maxlength="4000"
              placeholder="记录检查步骤、故障原因、处理方法和验证结果"
          /></label>
          <div class="form-pair">
            <label
              >本次工时（分钟）<el-input-number
                v-model="form.laborMinutes"
                :min="0"
                :max="100000" /></label
            ><label
              >本次费用（元）<el-input-number
                v-model="form.costAmount"
                :min="0"
                :precision="2"
                :max="1000000"
            /></label></div></template
        ><label v-if="dialog === 'acceptance'"
          >验收结果<el-radio-group v-model="form.passed"
            ><el-radio :value="true">通过并关闭</el-radio
            ><el-radio :value="false"
              >不通过，退回维修</el-radio
            ></el-radio-group
          ></label
        ><label v-if="dialog !== 'submit-acceptance'"
          >{{
            ["acceptance", "reject", "return-assignment", "note"].includes(
              dialog,
            )
              ? "说明（必填）"
              : "备注"
          }}<el-input
            v-model="form.note"
            type="textarea"
            :rows="3"
            maxlength="2000"
        /></label>
        <p v-if="dialog === 'acceptance' && form.passed" class="form-note">
          验收通过只解除本工单造成的禁借限制，其他故障和手工停用仍会保留。
        </p>
        <div class="dialog-actions">
          <el-button :disabled="busy" @click="dialog = ''">取消</el-button
          ><el-button type="primary" native-type="submit" :loading="busy"
            >确认</el-button
          >
        </div>
      </form></el-dialog
    ><span hidden>{{ staff() }}</span>
  </div>
</template>
