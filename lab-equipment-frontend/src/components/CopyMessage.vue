<script setup lang="ts">
import { onBeforeUnmount, ref } from "vue";
import Icon from "./Icon.vue";
const props = defineProps<{ content: string }>();
const copied = ref(false),
  failed = ref(false);
let timer: ReturnType<typeof setTimeout>;
async function copy() {
  try {
    if (navigator.clipboard?.writeText && window.isSecureContext)
      await navigator.clipboard.writeText(props.content);
    else {
      const previous = document.activeElement as HTMLElement | null;
      const field = document.createElement("textarea");
      field.value = props.content;
      field.style.cssText = "position:fixed;left:-9999px;top:0;opacity:0";
      document.body.appendChild(field);
      field.focus();
      field.select();
      try {
        if (!document.execCommand("copy")) throw new Error("copy");
      } finally {
        field.remove();
        previous?.focus();
      }
    }
    copied.value = true;
    failed.value = false;
  } catch {
    failed.value = true;
  }
  clearTimeout(timer);
  timer = setTimeout(() => {
    copied.value = false;
    failed.value = false;
  }, 2000);
}
onBeforeUnmount(() => clearTimeout(timer));
</script>
<template>
  <button
    class="icon-button message-copy"
    :aria-label="copied ? '已复制' : failed ? '复制失败，请重试' : '复制回复'"
    :title="copied ? '已复制' : failed ? '复制失败，请重试' : '复制回复'"
    @click="copy"
  >
    <Icon :name="copied ? 'check' : 'copy'" :size="18" /><span
      class="sr-only"
      role="status"
      >{{ copied ? "已复制" : failed ? "复制失败，请重试" : "" }}</span
    >
  </button>
</template>
