<script setup lang="ts">
import { isDark, toggleTheme } from "../preferences";
import Icon from "./Icon.vue";
defineProps<{ expanded?: boolean }>();
</script>
<template>
  <button
    class="nav-icon theme-toggle"
    :aria-label="isDark ? '切换浅色模式' : '切换深色模式'"
    :title="isDark ? '切换浅色模式' : '切换深色模式'"
    @click="toggleTheme"
  >
    <Icon :name="isDark ? 'sun' : 'moon'" :size="20" /><span v-if="expanded">{{
      isDark ? "浅色模式" : "深色模式"
    }}</span>
  </button>
</template>
