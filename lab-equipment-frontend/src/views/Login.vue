<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { login, fail } from "../state";
import Icon from "../components/Icon.vue";
const router = useRouter(),
  username = ref(""),
  password = ref(""),
  busy = ref(false);
async function submit() {
  busy.value = true;
  try {
    await login(username.value, password.value);
    await router.push("/chat");
  } catch (e) {
    fail(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <main class="login-page">
    <section class="login-intro">
      <div class="brand">
        <span class="brand-mark"><Icon name="lab" :size="28" /></span
        ><strong>博仪</strong>
      </div>
      <div class="intro-copy">
        <span class="eyebrow">实验室设备管理</span>
        <h1>借用、归还、维修，<br />每一步都有记录。</h1>
        <p>查找设备，处理借还与工单。<br />也可以直接告诉 AI，你想做什么。</p>
        <div class="intro-tiles">
          <div><Icon name="equipment" /><span>设备与预约</span></div>
          <div><Icon name="ticket" /><span>故障与维修</span></div>
          <div><Icon name="chat" /><span>AI 业务助手</span></div>
        </div>
      </div>
    </section>
    <section class="login-form-side">
      <form @submit.prevent="submit" class="login-form">
        <h2>欢迎回来</h2>
        <label
          >账号<el-input
            v-model="username"
            autocomplete="username"
            placeholder="请输入账号"
            size="large" /></label
        ><label
          >密码<el-input
            v-model="password"
            type="password"
            autocomplete="current-password"
            show-password
            placeholder="请输入密码"
            size="large" /></label
        ><el-button
          type="primary"
          native-type="submit"
          size="large"
          :loading="busy"
          :disabled="!username || !password"
          >登录</el-button
        >
        <p class="form-note">开通账户或重置密码，请联系实验室管理员。</p>
      </form>
    </section>
  </main>
</template>
