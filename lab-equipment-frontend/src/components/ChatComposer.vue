<script setup lang="ts">
import { ref } from "vue";
import type { Row } from "../state";
import Icon from "./Icon.vue";
import ThemePopover from "./ThemePopover.vue";
import PhotoUpload from "./PhotoUpload.vue";
const text = defineModel<string>({ required: true });
const photos = defineModel<Row[]>("photos", { required: true });
defineProps<{ busy?: boolean }>();
const emit = defineEmits<{ send: [] }>();
const composer = ref();
function keydown(event: KeyboardEvent) {
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    emit("send");
  }
}
defineExpose({ focus: () => composer.value?.focus() });
</script>
<template>
  <div class="conversation-composer-area">
    <div v-if="photos.length" class="composer-files">
      <span v-for="p in photos" :key="p.id"
        ><img :src="p.url" :alt="p.name" /><span>{{ p.name }}</span
        ><button
          class="icon-button"
          :aria-label="'移除 ' + p.name"
          :disabled="busy"
          @click="photos = photos.filter((x) => x.id !== p.id)"
        >
          <Icon name="close" :size="14" /></button
      ></span>
    </div>
    <div
      class="conversation-composer"
      :class="{ 'is-multiline': text.includes('\n') || text.length > 80 }"
    >
      <ThemePopover placement="top-start" :width="340" label="添加设备图片"
        ><PhotoUpload v-model="photos" /><template #trigger="{ toggle, open }"
          ><button
            class="icon-button attach-button"
            aria-label="添加设备图片"
            title="添加设备图片"
            :disabled="busy"
            :aria-expanded="open"
            @click="toggle"
          >
            <Icon name="attach" :size="22" /></button></template
      ></ThemePopover>
      <el-input
        ref="composer"
        v-model="text"
        type="textarea"
        :autosize="{ minRows: 1, maxRows: 8 }"
        maxlength="10000"
        placeholder="继续聊聊你的想法"
        aria-label="给博仪发送消息"
        :disabled="busy"
        @keydown="keydown"
      />
      <div class="composer-actions">
        <el-button
          type="primary"
          circle
          :loading="busy"
          :disabled="busy || (!text.trim() && !photos.length)"
          aria-label="发送消息"
          @click="emit('send')"
          ><Icon v-if="!busy" name="send" :size="20"
        /></el-button>
      </div>
    </div>
  </div>
</template>
