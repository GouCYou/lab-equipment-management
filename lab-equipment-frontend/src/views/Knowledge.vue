<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from "vue";
import { ElMessage } from "element-plus";
import {
  api,
  post,
  state,
  staff,
  manages,
  fail,
  date,
  changed,
  label,
} from "../state";
import type { Row } from "../state";
import Icon from "../components/Icon.vue";
import Badge from "../components/Badge.vue";
const rows = ref<Row[]>([]),
  detail = ref<Row | null>(null),
  dialog = ref(false),
  form = ref<Row>({}),
  busy = ref(false),
  loading = ref(false),
  search = ref(""),
  results = ref<Row[]>([]),
  searching = ref(false),
  fileName = ref(""),
  uploading = ref(false),
  input = ref<HTMLInputElement>();
let timer: ReturnType<typeof setTimeout> | undefined;
async function load() {
  loading.value = true;
  try {
    rows.value = (await api.get("/knowledge")).data;
    clearTimeout(timer);
    if (rows.value.some((r) => r.indexStatus === "INDEXING"))
      timer = setTimeout(async () => {
        await load();
        if (detail.value) await show(detail.value.id);
      }, 1500);
  } catch (e) {
    fail(e);
  } finally {
    loading.value = false;
  }
}
async function show(id: string) {
  try {
    detail.value = (await api.get("/knowledge/" + id)).data;
  } catch (e) {
    fail(e);
  }
}
function edit(d: Row | null = null) {
  form.value = {
    ...(d ? { ...d, id: d.id } : {}),
    title: d?.title || "",
    model: d?.model || "",
    category: d?.category || "",
    labId: d?.labId || state.user?.labIds?.[0] || "",
    visibility: d?.visibility || "PUBLIC",
    text: "",
    fileId: "",
  };
  fileName.value = "";
  dialog.value = true;
}
async function upload(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file) return;
  uploading.value = true;
  try {
    const body = new FormData();
    body.append("file", file);
    const { data } = await api.post("/files", body);
    form.value.fileId = data.id;
    fileName.value = data.name;
    if (!form.value.title) form.value.title = file.name.replace(/\.[^.]+$/, "");
  } catch (e) {
    fail(e);
  } finally {
    uploading.value = false;
    if (input.value) input.value.value = "";
  }
}
async function save() {
  busy.value = true;
  try {
    const data = await post(
      form.value.id
        ? "/knowledge/" + form.value.id + "/versions"
        : "/knowledge",
      form.value,
    );
    dialog.value = false;
    await show(data.id);
    changed();
    ElMessage.success("资料版本已保存，请建立索引后发布");
  } catch (e) {
    fail(e);
  } finally {
    busy.value = false;
  }
}
async function action(action: string, v: Row | null = null) {
  if (!detail.value) return;
  busy.value = true;
  try {
    detail.value = await post(
      "/knowledge/" + detail.value.id + "/" + action,
      action === "toggle"
        ? { enabled: !detail.value.enabled }
        : { versionId: v!.id },
    );
    changed();
    ElMessage.success(
      action === "index"
        ? "索引任务已提交"
        : action === "publish"
          ? "资料版本已发布"
          : "资料状态已更新",
    );
    await load();
  } catch (e) {
    fail(e);
  } finally {
    busy.value = false;
  }
}
async function query() {
  if (!search.value.trim()) return;
  searching.value = true;
  try {
    results.value = await post("/knowledge/search", { query: search.value });
    if (!results.value.length) ElMessage.info("未找到适用的已发布资料");
  } catch (e) {
    fail(e);
  } finally {
    searching.value = false;
  }
}
watch(() => state.revision, load);
onMounted(load);
onBeforeUnmount(() => clearTimeout(timer));
</script>
<template>
  <div class="page">
    <div class="page-heading">
      <div>
        <h1>维修知识库</h1>
      </div>
      <el-button v-if="staff()" type="primary" @click="edit()"
        ><Icon name="plus" />添加资料</el-button
      >
    </div>
    <form class="knowledge-search panel" @submit.prevent="query">
      <span class="search-symbol"><Icon name="book" :size="25" /></span
      ><el-input
        v-model="search"
        placeholder="搜索维修规范、设备说明或故障现象"
        clearable
      /><el-button native-type="submit" type="primary" :loading="searching"
        >检索资料</el-button
      >
    </form>
    <section v-if="results.length" class="section">
      <div class="section-heading">
        <h2>检索结果</h2>
        <button class="text-button" @click="results = []">收起</button>
      </div>
      <div class="search-results">
        <article
          v-for="r in results"
          :key="r.chunkId"
          class="panel search-result"
        >
          <div>
            <Icon name="book" /><strong>{{ r.title }}</strong
            ><span>V{{ r.versionNo }} · {{ r.locator }}</span>
          </div>
          <p>{{ r.excerpt }}</p>
        </article>
      </div>
    </section>
    <section class="section" v-loading="loading">
      <div class="section-heading">
        <h2>资料列表</h2>
        <span class="muted">{{ rows.length }} 份资料</span>
      </div>
      <div class="knowledge-grid">
        <article v-for="d in rows" :key="d.id" class="panel document-card">
          <div class="document-card-top">
            <span class="document-icon"><Icon name="book" :size="28" /></span
            ><Badge :value="d.indexStatus || 'UNINDEXED'" />
          </div>
          <button class="text-button row-title" @click="show(d.id)">
            {{ d.title }}
          </button>
          <p>{{ d.model || "通用资料" }} · {{ d.category || "未分类" }}</p>
          <div class="document-meta">
            <span>{{
              d.enabled ? (d.publishedVersionId ? "已发布" : "草稿") : "已停用"
            }}</span
            ><span>{{ d.chunkCount || 0 }} 个片段</span>
          </div>
          <div class="card-actions">
            <el-button size="small" text @click="show(d.id)"
              >{{ staff() ? "查看与管理" : "查看资料"
              }}<Icon name="arrow" :size="16" /></el-button
            ><span class="muted">{{ date(d.createdAt, false) }}</span>
          </div>
        </article>
      </div>
      <div v-if="!rows.length && !loading" class="empty-state large">
        <Icon name="book" :size="38" />
        <h3>暂无可用资料</h3>
        <p>
          {{
            staff()
              ? "添加说明书或维修规范，建立索引并审核发布。"
              : "资料发布后，即可在这里检索和查看。"
          }}
        </p>
      </div>
    </section>
    <el-drawer
      :model-value="!!detail"
      title="资料详情与版本"
      size="min(720px, 100%)"
      @close="detail = null"
      ><div v-if="detail" class="detail-body">
        <h2>{{ detail.title }}</h2>
        <p class="muted">
          {{ detail.model || "通用资料" }} · {{ detail.category || "未分类" }} ·
          {{
            detail.visibility === "MAINTENANCE"
              ? "维修人员资料"
              : label(detail.visibility)
          }}
        </p>
        <div class="detail-actions" v-if="manages(detail.labId)">
          <el-button type="primary" @click="edit(detail)">添加新版本</el-button
          ><el-button :loading="busy" @click="action('toggle')">{{
            detail.enabled ? "停用资料" : "启用资料"
          }}</el-button>
        </div>
        <section
          v-for="v in detail.versions"
          :key="v.id"
          class="version-section"
        >
          <div class="section-heading">
            <h3>
              版本 {{ v.versionNo }}
              <span
                v-if="detail.publishedVersionId === v.id"
                class="published-mark"
                >当前发布版本</span
              >
            </h3>
            <Badge :value="v.status" />
          </div>
          <p class="muted">
            {{ date(v.createdAt) }} · {{ v.chunkCount }} 个片段
          </p>
          <div v-if="v.error" class="inline-error">{{ v.error }}</div>
          <details class="version-text">
            <summary>查看资料正文</summary>
            <pre>{{
              v.textContent || "文件尚未解析，建立索引后展示正文。"
            }}</pre>
          </details>
          <div v-if="manages(detail.labId)" class="detail-actions">
            <el-button
              :loading="busy"
              :disabled="v.status === 'INDEXING'"
              @click="action('index', v)"
              >{{ v.status === "READY" ? "重建索引" : "建立索引" }}</el-button
            ><el-button
              type="primary"
              plain
              :loading="busy"
              :disabled="
                v.status !== 'READY' || detail.publishedVersionId === v.id
              "
              @click="action('publish', v)"
              >审核发布</el-button
            >
          </div>
        </section>
      </div></el-drawer
    ><el-dialog
      v-model="dialog"
      :title="form.id ? '添加资料新版本' : '添加知识资料'"
      width="660px"
      ><form class="form-stack" @submit.prevent="save">
        <label>资料标题<el-input v-model="form.title" maxlength="160" /></label>
        <div class="form-pair">
          <label
            >适用型号<el-input
              v-model="form.model"
              placeholder="留空表示通用" /></label
          ><label
            >适用类别<el-input
              v-model="form.category"
              placeholder="例如：测量仪器"
          /></label>
        </div>
        <div class="form-pair">
          <label
            >所属实验室<el-select
              v-model="form.labId"
              clearable
              :disabled="!!form.id"
              ><el-option
                v-for="l in state.labs.filter((l) => manages(l.id))"
                :key="l.id"
                :value="l.id"
                :label="l.name" /></el-select></label
          ><label
            >可见范围<el-select v-model="form.visibility"
              ><el-option value="PUBLIC" label="公开资料" /><el-option
                value="LAB"
                label="实验室内部" /><el-option
                value="MAINTENANCE"
                label="维修人员资料" /></el-select
          ></label>
        </div>
        <label
          >上传文档
          <div class="file-input-line">
            <el-button :loading="uploading" @click="input?.click()"
              ><Icon name="attach" />选择文件</el-button
            ><span>{{
              fileName || "支持文本型 PDF、DOCX、TXT、Markdown"
            }}</span>
          </div>
          <input
            ref="input"
            type="file"
            accept=".pdf,.docx,.txt,.md"
            hidden
            @change="upload" /></label
        ><label
          >或直接输入正文<el-input
            v-model="form.text"
            type="textarea"
            :rows="7"
            placeholder="设备使用说明、检查步骤、维修规范等"
        /></label>
        <p class="form-note">
          新版本完成索引并审核后对用户可见。扫描件请先转成可识别文字。
        </p>
        <div class="dialog-actions">
          <el-button @click="dialog = false">取消</el-button
          ><el-button
            type="primary"
            native-type="submit"
            :loading="busy"
            :disabled="uploading"
            >保存版本</el-button
          >
        </div>
      </form></el-dialog
    >
  </div>
</template>
