<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { api, state, fail, clearSession, label } from "../state";
import Icon from "../components/Icon.vue";
const router = useRouter(),
  name = ref(state.user?.name || ""),
  passwords = ref({ oldPassword: "", newPassword: "", confirmation: "" }),
  busy = ref(false);
async function save() {
  busy.value = true;
  try {
    state.user = (await api.put("/auth/profile", { name: name.value })).data;
    ElMessage.success("个人信息已更新");
  } catch (e) {
    fail(e);
  } finally {
    busy.value = false;
  }
}
async function password() {
  if (passwords.value.newPassword !== passwords.value.confirmation) {
    ElMessage.error("两次新密码不一致");
    return;
  }
  busy.value = true;
  try {
    await api.put("/auth/password", passwords.value);
    clearSession();
    ElMessage.success("密码已更新，请重新登录");
    await router.push("/login");
  } catch (e) {
    fail(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div class="page settings-page">
    <div class="settings-navigation">
      <span class="avatar large"><Icon name="user" :size="30" /></span>
      <h2>{{ state.user?.name }}</h2>
      <p>{{ state.user?.roles.map(label).join(" / ") }}</p>
      <span class="muted">{{ state.user?.username }}</span>
    </div>
    <div class="settings-content">
      <div class="page-heading"><h1>个人设置</h1></div>
      <form class="panel settings-card form-stack" @submit.prevent="save">
        <h2>账户信息</h2>
        <label
          >账号<el-input :model-value="state.user?.username" disabled /></label
        ><label>姓名<el-input v-model="name" maxlength="80" /></label>
        <div class="dialog-actions">
          <el-button native-type="submit" type="primary" :loading="busy"
            >保存信息</el-button
          >
        </div>
      </form>
      <form class="panel settings-card form-stack" @submit.prevent="password">
        <h2>修改密码</h2>
        <label
          >原密码<el-input
            v-model="passwords.oldPassword"
            type="password"
            show-password
            autocomplete="current-password"
        /></label>
        <div class="form-pair">
          <label
            >新密码<el-input
              v-model="passwords.newPassword"
              type="password"
              show-password
              autocomplete="new-password"
              placeholder="至少 6 位" /></label
          ><label
            >确认新密码<el-input
              v-model="passwords.confirmation"
              type="password"
              show-password
              autocomplete="new-password"
          /></label>
        </div>
        <p class="form-note">修改后所有旧会话将失效，需要重新登录。</p>
        <div class="dialog-actions">
          <el-button native-type="submit" type="primary" :loading="busy"
            >更新密码</el-button
          >
        </div>
      </form>
    </div>
  </div>
</template>
