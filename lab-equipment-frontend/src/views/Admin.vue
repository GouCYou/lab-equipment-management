<script setup lang="ts">
import { ref, onMounted, computed } from "vue";
import { ElMessage } from "element-plus";
import { api, post, state, fail, changed, date, label } from "../state";
import type { Row } from "../state";
import Icon from "../components/Icon.vue";
const roleDescriptions: Record<string, string> = {
  STUDENT: "本人预约、借还、报修和公开知识查询",
  LAB_STAFF: "负责实验室内的审批、发放、故障核实和验收",
  TECHNICIAN: "处理分配工单、记录维修过程和提交验收",
  ADMIN: "账户、范围、规则、模型配置和审计管理",
};
const tab = ref("users"),
  rows = ref<Row[]>([]),
  logs = ref<Row[]>([]),
  roles = ref<Row[]>([]),
  models = ref<Row>({}),
  loading = ref(false),
  busy = ref(false),
  dialog = ref(""),
  form = ref<Row>({}),
  categoryName = ref("");
const tabs = [
  { id: "users", title: "账户与角色", icon: "user" },
  { id: "labs", title: "实验室与字典", icon: "lab" },
  { id: "models", title: "模型接入", icon: "spark" },
  { id: "audit", title: "操作审计", icon: "shield" },
];
const roleOptions = ["STUDENT", "LAB_STAFF", "TECHNICIAN", "ADMIN"];
async function load() {
  loading.value = true;
  try {
    if (tab.value === "users") {
      rows.value = (await api.get("/admin/users")).data;
      roles.value = (await api.get("/admin/roles")).data;
    }
    if (tab.value === "models")
      models.value = (await api.get("/admin/ai-settings")).data;
    if (tab.value === "audit")
      logs.value = (await api.get("/admin/audit-logs")).data;
  } catch (e) {
    fail(e);
  } finally {
    loading.value = false;
  }
}
function choose(id: string) {
  tab.value = id;
  void load();
}
function edit(kind: string, row: Row | null = null) {
  dialog.value = kind;
  form.value = row
    ? {
        ...row,
        roles: [...(row.roles || [])],
        labIds: [...(row.labIds || [])],
        password: "",
        enabled: !!row.enabled,
      }
    : {
        username: "",
        name: "",
        password: "123456",
        roles: ["STUDENT"],
        labIds: [],
        enabled: true,
        location: "",
        openStart: 8,
        openEnd: 22,
      };
}
async function save() {
  busy.value = true;
  try {
    const url =
      "/admin/" +
      (dialog.value === "user" ? "users" : "laboratories") +
      (form.value.id ? "/" + form.value.id : "");
    if (form.value.id) await api.put(url, form.value);
    else await post(url, form.value);
    dialog.value = "";
    changed();
    await load();
    ElMessage.success("已保存");
  } catch (e) {
    fail(e);
  } finally {
    busy.value = false;
  }
}
async function saveModels() {
  busy.value = true;
  try {
    models.value = (await api.put("/admin/ai-settings", models.value)).data;
    ElMessage.success("模型设置已保存，下次请求生效");
  } catch (e) {
    fail(e);
  } finally {
    busy.value = false;
  }
}
async function testModel() {
  busy.value = true;
  try {
    const { data } = await api.post("/admin/ai-test", {}, { timeout: 65000 });
    ElMessage.success(data.message || "连接成功");
  } catch (e) {
    fail(e);
  } finally {
    busy.value = false;
  }
}
async function addCategory() {
  if (!categoryName.value.trim()) return;
  try {
    await post("/admin/categories", { name: categoryName.value });
    categoryName.value = "";
    changed();
    ElMessage.success("类别已添加");
  } catch (e) {
    fail(e);
  }
}
const selectedTitle = computed(
  () => tabs.find((t) => t.id === tab.value)?.title,
);
onMounted(load);
</script>
<template>
  <div class="admin-layout">
    <aside class="admin-navigation">
      <div class="admin-label">
        <Icon name="settings" /><strong>系统管理</strong>
      </div>
      <button
        v-for="t in tabs"
        :key="t.id"
        :class="{ selected: tab === t.id }"
        @click="choose(t.id)"
      >
        <Icon :name="t.icon" />{{ t.title }}</button
      ><router-link to="/chat" class="admin-back"
        ><Icon name="back" />返回 AI 助手</router-link
      >
    </aside>
    <div class="admin-content" v-loading="loading">
      <div class="page-heading">
        <div>
          <h1>{{ selectedTitle }}</h1>
        </div>
        <el-button v-if="tab === 'users'" type="primary" @click="edit('user')"
          ><Icon name="plus" />新增账户</el-button
        ><el-button v-if="tab === 'labs'" type="primary" @click="edit('lab')"
          ><Icon name="plus" />新增实验室</el-button
        >
      </div>
      <template v-if="tab === 'users'"
        ><div class="panel table-scroll">
          <table>
            <thead>
              <tr>
                <th>姓名 / 账号</th>
                <th>角色</th>
                <th>负责实验室</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="u in rows" :key="u.id">
                <td>
                  <strong>{{ u.name }}</strong
                  ><small>{{ u.username }}</small>
                </td>
                <td>{{ u.roles.map(label).join(" / ") }}</td>
                <td>
                  {{
                    state.labs
                      .filter((l) => u.labIds.includes(l.id))
                      .map((l) => l.name)
                      .join("、") || "—"
                  }}
                </td>
                <td>
                  <span class="badge" :class="u.enabled ? 'blue' : 'danger'">{{
                    u.enabled ? "已启用" : "已停用"
                  }}</span>
                </td>
                <td>
                  <el-button text size="small" @click="edit('user', u)"
                    >编辑账户</el-button
                  >
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <section class="section">
          <div class="section-heading"><h2>角色职责</h2></div>
          <div class="role-grid">
            <div v-for="r in roles" :key="r.code" class="panel role-card">
              <Icon name="shield" />
              <h3>{{ r.name }}</h3>
              <p>{{ roleDescriptions[r.code] }}</p>
            </div>
          </div>
        </section></template
      ><template v-if="tab === 'labs'"
        ><div class="panel table-scroll">
          <table>
            <thead>
              <tr>
                <th>实验室</th>
                <th>位置</th>
                <th>每日开放时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="l in state.labs" :key="l.id">
                <td>
                  <strong>{{ l.name }}</strong>
                </td>
                <td>{{ l.location }}</td>
                <td>
                  {{ String(l.openStart).padStart(2, "0") }}:00 —
                  {{ String(l.openEnd).padStart(2, "0") }}:00
                </td>
                <td>
                  <el-button text size="small" @click="edit('lab', l)"
                    >编辑</el-button
                  >
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <section class="panel settings-card section">
          <h2>设备类别</h2>
          <div class="category-chips">
            <span v-for="c in state.categories" :key="c.id">{{ c.name }}</span>
          </div>
          <form class="inline-form" @submit.prevent="addCategory">
            <el-input
              v-model="categoryName"
              maxlength="80"
              placeholder="输入新的设备类别"
            /><el-button native-type="submit" type="primary" plain
              >添加类别</el-button
            >
          </form>
        </section></template
      >
      <form
        v-if="tab === 'models'"
        @submit.prevent="saveModels"
        class="form-stack model-settings"
      >
        <section class="panel settings-card form-stack">
          <h2>对话模型</h2>
          <label
            >API 基础地址<el-input
              v-model="models.GENERATION_BASE_URL"
              placeholder="https://供应商地址/v1"
          /></label>
          <div class="form-pair">
            <label
              >模型名称<el-input
                v-model="models.GENERATION_MODEL"
                placeholder="供应商实际提供的模型标识" /></label
            ><label
              >API 密钥<el-input
                v-model="models.GENERATION_API_KEY"
                type="password"
                show-password
                autocomplete="off"
                :placeholder="
                  models.GENERATION_API_KEY_CONFIGURED
                    ? '已配置，留空保留原密钥'
                    : '请输入服务端密钥'
                "
            /></label>
          </div>
          <p class="form-note">
            支持工具调用即可办理借还。支持识图的模型可查看照片；普通文本模型也能凭照片记录办理归还。
          </p>
        </section>
        <div class="dialog-actions">
          <el-button :loading="busy" @click="testModel"
            >测试已保存的对话模型</el-button
          ><el-button type="primary" native-type="submit" :loading="busy"
            >保存模型设置</el-button
          >
        </div>
      </form>
      <div v-if="tab === 'audit'" class="panel table-scroll">
        <table>
          <thead>
            <tr>
              <th>时间</th>
              <th>操作人</th>
              <th>操作</th>
              <th>对象 / 说明</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in logs" :key="a.id">
              <td>{{ date(a.createdAt) }}</td>
              <td>{{ a.actorName || "系统" }}</td>
              <td>{{ label(a.action) }}</td>
              <td>
                <span class="truncate">{{ a.note || a.targetId }}</span>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="!logs.length" class="empty-state"><h3>暂无审计记录</h3></div>
      </div>
    </div>
    <el-dialog
      :model-value="!!dialog"
      :title="
        form.id
          ? '编辑' + (dialog === 'user' ? '账户' : '实验室')
          : '新增' + (dialog === 'user' ? '账户' : '实验室')
      "
      width="590px"
      @close="!busy && (dialog = '')"
      ><form class="form-stack" @submit.prevent="save">
        <template v-if="dialog === 'user'"
          ><div class="form-pair">
            <label
              >账号<el-input
                v-model="form.username"
                :disabled="!!form.id"
                maxlength="80" /></label
            ><label>姓名<el-input v-model="form.name" maxlength="80" /></label>
          </div>
          <label
            >{{ form.id ? "重置密码（留空保留原密码）" : "初始密码"
            }}<el-input
              v-model="form.password"
              type="password"
              show-password
              autocomplete="new-password"
              placeholder="至少 6 位" /></label
          ><label
            >角色<el-select v-model="form.roles" multiple
              ><el-option
                v-for="r in roleOptions"
                :key="r"
                :value="r"
                :label="label(r)" /></el-select></label
          ><label v-if="form.roles?.includes('LAB_STAFF')"
            >负责的实验室<el-select v-model="form.labIds" multiple
              ><el-option
                v-for="l in state.labs"
                :key="l.id"
                :value="l.id"
                :label="l.name" /></el-select></label
          ><el-switch
            v-model="form.enabled"
            active-text="账户已启用"
            inactive-text="账户已停用"
          />
          <p class="form-note">
            角色、密码或启用状态变更后，该账户的旧会话失效。
          </p></template
        ><template v-else
          ><label
            >实验室名称<el-input v-model="form.name" maxlength="100" /></label
          ><label
            >位置<el-input v-model="form.location" maxlength="160"
          /></label>
          <div class="form-pair">
            <label
              >开放时间（整点）<el-input-number
                v-model="form.openStart"
                :min="0"
                :max="23" /></label
            ><label
              >关闭时间（整点）<el-input-number
                v-model="form.openEnd"
                :min="1"
                :max="24"
            /></label>
          </div>
          <p class="form-note">
            开放时间调整保存历史生效区间，历史统计使用当时规则。
          </p></template
        >
        <div class="dialog-actions">
          <el-button :disabled="busy" @click="dialog = ''">取消</el-button
          ><el-button native-type="submit" type="primary" :loading="busy"
            >保存</el-button
          >
        </div>
      </form></el-dialog
    >
  </div>
</template>
