<script setup lang="ts">
import { ref, computed, onMounted, watch, onBeforeUnmount } from "vue";
import { useRoute } from "vue-router";
import { ElMessage } from "element-plus";
import { dialogs as ElMessageBox } from "../dialogs";
import {
  api,
  post,
  state,
  staff,
  manages,
  fail,
  changed,
  openBorrow,
  openReport,
  date,
} from "../state";
import type { Row } from "../state";
import Icon from "../components/Icon.vue";
import ThemePopover from "../components/ThemePopover.vue";
import Badge from "../components/Badge.vue";
const route = useRoute(),
  rows = ref<Row[]>([]),
  search = ref(""),
  lab = ref(""),
  category = ref(""),
  available = ref(false),
  loading = ref(false),
  error = ref(false),
  drawer = ref(false),
  detail = ref<Row | null>(null),
  editing = ref(false),
  form = ref<Row>({}),
  saving = ref(false);
let timer: ReturnType<typeof setTimeout> | undefined;
const managedLabs = computed(() => state.labs.filter((l) => manages(l.id)));
async function load() {
  loading.value = true;
  try {
    rows.value = (
      await api.get("/equipment", {
        params: {
          search: search.value,
          labId: lab.value,
          categoryId: category.value,
          availableOnly: available.value,
        },
      })
    ).data;
    error.value = false;
  } catch (e) {
    error.value = true;
    fail(e);
  } finally {
    loading.value = false;
  }
}
async function show(id: string) {
  drawer.value = true;
  try {
    detail.value = (await api.get("/equipment/" + id)).data;
  } catch (e) {
    drawer.value = false;
    fail(e);
  }
}
function edit(e: Row | null = null) {
  form.value = e
    ? { ...e }
    : {
        assetNo: "",
        name: "",
        model: "",
        categoryId: state.categories[0]?.id || "",
        labId: managedLabs.value[0]?.id || "",
        purchasedOn: "",
        description: "",
      };
  editing.value = true;
}
async function save() {
  saving.value = true;
  try {
    if (form.value.id) await api.put("/equipment/" + form.value.id, form.value);
    else await post("/equipment", form.value);
    editing.value = false;
    ElMessage.success("设备档案已保存");
    changed();
    if (detail.value?.id === form.value.id) await show(form.value.id);
  } catch (e) {
    fail(e);
  } finally {
    saving.value = false;
  }
}
async function toggle(e: Row) {
  try {
    const result = await ElMessageBox.prompt(
      e.enabled
        ? "停用将使尚未发放的已批准预约失效。请输入停用原因。"
        : "请输入启用原因，其他故障限制仍会保留。",
      e.enabled ? "停用设备" : "启用设备",
      {
        inputValidator: (v) => !!v?.trim() || "请填写原因",
        confirmButtonText: "确认",
        cancelButtonText: "取消",
      },
    );
    await post("/equipment/" + e.id + "/toggle", {
      enabled: !e.enabled,
      reason: result.value,
    });
    changed();
    if (drawer.value) await show(e.id);
  } catch (err) {
    if (err !== "cancel" && err !== "close") fail(err);
  }
}
watch([search, lab, category, available], () => {
  clearTimeout(timer);
  timer = setTimeout(load, 250);
});
watch(() => state.revision, load);
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
onBeforeUnmount(() => clearTimeout(timer));
</script>
<template>
  <div class="page">
    <div class="page-heading">
      <div>
        <h1>设备中心</h1>
      </div>
      <el-button v-if="staff()" type="primary" @click="edit()"
        ><Icon name="plus" />新增设备</el-button
      >
    </div>
    <div class="toolbar">
      <el-input
        v-model="search"
        clearable
        placeholder="搜索名称、型号或资产编号"
        class="search-input"
        ><template #prefix
          ><Icon name="search" :size="18" /></template></el-input
      ><el-select
        v-model="lab"
        placeholder="全部实验室"
        clearable
        class="filter-select"
        ><el-option
          v-for="l in state.labs"
          :key="l.id"
          :label="l.name"
          :value="l.id" /></el-select
      ><el-select
        v-model="category"
        placeholder="全部类别"
        clearable
        class="filter-select"
        ><el-option
          v-for="c in state.categories"
          :key="c.id"
          :label="c.name"
          :value="c.id" /></el-select
      ><el-checkbox v-model="available">当前可借</el-checkbox
      ><span class="result-count">{{ rows.length }} 台设备</span>
    </div>
    <div v-if="error" class="inline-error">
      加载失败 <button class="text-button" @click="load">重试</button>
    </div>
    <div v-loading="loading" class="inventory-grid">
      <article v-for="e in rows" :key="e.id" class="equipment-card">
        <div class="equipment-art" @click="show(e.id)">
          <span class="equipment-glyph"
            ><Icon
              :name="
                e.categoryName === '影像设备'
                  ? 'image'
                  : e.categoryName === '机械工具'
                    ? 'ticket'
                    : 'equipment'
              "
              :size="42" /></span
          ><span class="asset-number">{{ e.assetNo }}</span
          ><Badge :value="e.operationalStatus" />
        </div>
        <div class="equipment-body">
          <span class="category-label">{{ e.categoryName }}</span
          ><button class="equipment-title text-button" @click="show(e.id)">
            {{ e.name }}
          </button>
          <p class="equipment-model">{{ e.model }}</p>
          <div class="equipment-location">
            <Icon name="lab" :size="16" />{{ e.labName }}
          </div>
          <div class="equipment-availability">
            <i :class="e.available ? 'available' : 'occupied'" />{{
              e.available
                ? "当前可借用"
                : e.borrowed
                  ? "当前已借出，可查询后续时段"
                  : e.operationalStatus === "NORMAL"
                    ? "当前时段已预约"
                    : "暂不可借用"
            }}
          </div>
          <div class="equipment-actions">
            <el-button
              type="primary"
              plain
              :disabled="e.operationalStatus !== 'NORMAL'"
              @click="openBorrow(e)"
              >申请借用</el-button
            ><ThemePopover
              v-if="manages(e.labId)"
              :items="[
                { id: 'edit', label: '编辑档案', icon: 'settings' },
                {
                  id: 'toggle',
                  label: e.enabled ? '停用设备' : '启用设备',
                  icon: 'equipment',
                },
                { id: 'report', label: '故障报修', icon: 'ticket' },
              ]"
              @select="
                (id) =>
                  id === 'edit'
                    ? edit(e)
                    : id === 'toggle'
                      ? toggle(e)
                      : openReport(e)
              "
              ><template #trigger="{ toggle, open }"
                ><button
                  class="icon-button"
                  aria-label="设备操作"
                  :aria-expanded="open"
                  @click="toggle"
                >
                  <Icon name="more" /></button></template></ThemePopover
            ><el-button v-else text @click="show(e.id)">详情</el-button>
          </div>
        </div>
      </article>
    </div>
    <div v-if="!loading && !rows.length && !error" class="empty-state large">
      <Icon name="equipment" :size="38" />
      <h3>没有找到设备</h3>
      <p>试试其他搜索词，或调整实验室和类别筛选。</p>
    </div>
    <el-drawer v-model="drawer" title="设备详情" size="min(580px, 100%)"
      ><div v-if="detail" class="detail-body">
        <div class="detail-title">
          <span class="object-icon large"
            ><Icon name="equipment" :size="30"
          /></span>
          <div>
            <h2>{{ detail.name }}</h2>
            <p>{{ detail.assetNo }} · {{ detail.model }}</p>
          </div>
        </div>
        <div class="detail-badges">
          <Badge :value="detail.operationalStatus" /><span class="muted">{{
            detail.borrowed ? "当前已借出" : "当前在库"
          }}</span>
        </div>
        <dl class="detail-list">
          <dt>实验室</dt>
          <dd>{{ detail.labName }} · {{ detail.location }}</dd>
          <dt>类别</dt>
          <dd>{{ detail.categoryName }}</dd>
          <dt>购置日期</dt>
          <dd>{{ detail.purchasedOn || "—" }}</dd>
        </dl>
        <section class="detail-section">
          <h3>设备说明</h3>
          <p>{{ detail.description || "暂无补充说明" }}</p>
        </section>
        <section v-if="detail.blocks?.length" class="detail-section">
          <h3>当前禁借原因</h3>
          <div v-for="b in detail.blocks" :key="b.id" class="notice-line">
            {{ b.reason }}<small>{{ date(b.startsAt) }}</small>
          </div>
        </section>
        <section class="detail-section">
          <h3>已批准的预约时段</h3>
          <div
            v-for="(r, i) in detail.reservations"
            :key="i"
            class="notice-line"
          >
            {{ date(r.startsAt) }} — {{ date(r.endsAt) }}
          </div>
          <p v-if="!detail.reservations?.length" class="muted">暂无后续预约</p>
        </section>
        <div class="detail-actions">
          <el-button
            type="primary"
            :disabled="detail.operationalStatus !== 'NORMAL'"
            @click="openBorrow(detail)"
            >申请借用</el-button
          ><el-button @click="openReport(detail)">故障报修</el-button
          ><el-button v-if="manages(detail.labId)" @click="edit(detail)"
            >编辑档案</el-button
          >
        </div>
      </div></el-drawer
    ><el-dialog
      v-model="editing"
      :title="form.id ? '编辑设备' : '新增设备'"
      width="600px"
      ><form class="form-stack" @submit.prevent="save">
        <div class="form-pair">
          <label
            >资产编号<el-input v-model="form.assetNo" maxlength="60" /></label
          ><label
            >设备名称<el-input v-model="form.name" maxlength="120"
          /></label>
        </div>
        <label>型号<el-input v-model="form.model" maxlength="120" /></label>
        <div class="form-pair">
          <label
            >所属实验室<el-select v-model="form.labId" :disabled="!!form.id"
              ><el-option
                v-for="l in managedLabs"
                :key="l.id"
                :value="l.id"
                :label="l.name" /></el-select></label
          ><label
            >类别<el-select v-model="form.categoryId"
              ><el-option
                v-for="c in state.categories"
                :key="c.id"
                :value="c.id"
                :label="c.name" /></el-select
          ></label>
        </div>
        <label>购置日期<input type="date" v-model="form.purchasedOn" /></label
        ><label
          >设备说明<el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
        /></label>
        <div class="dialog-actions">
          <el-button @click="editing = false">取消</el-button
          ><el-button native-type="submit" type="primary" :loading="saving"
            >保存设备</el-button
          >
        </div>
      </form></el-dialog
    >
  </div>
</template>
