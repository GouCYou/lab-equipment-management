<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from "vue";
import { photoUrl } from "../state";
const props = defineProps<{ ids: string[] | null }>();
const images = ref<string[]>([]);
let generation = 0;
watch(
  () => props.ids,
  async (ids) => {
    const g = ++generation;
    images.value.forEach(URL.revokeObjectURL);
    images.value = [];
    for (const id of ids || [])
      try {
        const url = await photoUrl(id);
        if (g === generation) images.value.push(url);
        else URL.revokeObjectURL(url);
      } catch {}
  },
  { immediate: true, deep: true },
);
onBeforeUnmount(() => {
  generation++;
  images.value.forEach(URL.revokeObjectURL);
});
</script>
<template>
  <div class="photo-gallery">
    <el-image
      v-for="(url, index) in images"
      :key="url"
      :src="url"
      :preview-src-list="images"
      :initial-index="index"
      fit="cover"
      preview-teleported
    /><span v-if="ids?.length && !images.length" class="muted"
      >照片加载中或无权访问</span
    >
  </div>
</template>
