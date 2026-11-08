<script setup lang="ts">
import { nextTick, ref } from "vue";
import Icon from "./Icon.vue";
import Photos from "./Photos.vue";
const props = defineProps<{
  content: string;
  attachments: string[];
  disabled?: boolean;
}>();
const emit = defineEmits<{ resend: [text: string] }>();
const editing = ref(false),
  text = ref(""),
  input = ref<HTMLTextAreaElement>();
async function edit() {
  text.value = props.content;
  editing.value = true;
  await nextTick();
  input.value?.focus();
  resize();
}
function resize() {
  if (input.value) {
    input.value.style.height = "auto";
    input.value.style.height = Math.min(320, input.value.scrollHeight) + "px";
  }
}
function submit() {
  if (props.disabled || (!text.value.trim() && !props.attachments.length))
    return;
  emit("resend", text.value.trim());
  editing.value = false;
}
</script>
<template>
  <div class="user-message" :class="{ editing }">
    <form v-if="editing" class="message-editor" @submit.prevent="submit">
      <textarea
        ref="input"
        v-model="text"
        aria-label="编辑消息内容"
        rows="2"
        maxlength="10000"
        @input="resize"
        @keydown.esc.prevent="editing = false"
      />
      <Photos v-if="attachments.length" :ids="attachments" />
      <div class="message-editor-actions">
        <button type="button" class="theme-secondary" @click="editing = false">
          取消</button
        ><button
          type="submit"
          class="theme-primary"
          :disabled="disabled || (!text.trim() && !attachments.length)"
        >
          完成并发送
        </button>
      </div>
    </form>
    <template v-else
      ><div class="user-bubble">
        <p v-if="content">{{ content }}</p>
        <Photos v-if="attachments.length" :ids="attachments" />
      </div>
      <button
        class="icon-button message-edit"
        aria-label="编辑消息"
        title="编辑消息"
        :disabled="disabled"
        @click="edit"
      >
        <Icon name="edit" :size="18" /></button
    ></template>
  </div>
</template>
