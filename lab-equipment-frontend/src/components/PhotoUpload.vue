<script setup lang="ts">
import { ref, onBeforeUnmount } from "vue";
import { api, fail } from "../state";
import type { Row } from "../state";
import Icon from "./Icon.vue";
const props = withDefaults(defineProps<{ modelValue: Row[]; max?: number }>(), {
  max: 6,
});
const emit = defineEmits(["update:modelValue"]);
const uploading = ref(false),
  input = ref<HTMLInputElement>();
const urls: string[] = [];
async function select(event: Event) {
  const files = Array.from((event.target as HTMLInputElement).files || []);
  if (files.length + props.modelValue.length > props.max) {
    fail(new Error("最多上传 " + props.max + " 张照片"));
    return;
  }
  uploading.value = true;
  try {
    for (const file of files) {
      if (!["image/jpeg", "image/png"].includes(file.type))
        throw new Error("请上传 JPG 或 PNG 图片");
      let upload: File = file;
      if (file.size > 1500000) {
        const image = await createImageBitmap(file);
        const scale = Math.min(1, 1600 / Math.max(image.width, image.height));
        const canvas = document.createElement("canvas");
        canvas.width = image.width * scale;
        canvas.height = image.height * scale;
        canvas
          .getContext("2d")!
          .drawImage(image, 0, 0, canvas.width, canvas.height);
        image.close();
        const blob = await new Promise<Blob>((resolve, reject) =>
          canvas.toBlob(
            (b) => (b ? resolve(b) : reject(new Error("图片压缩失败"))),
            "image/jpeg",
            0.85,
          ),
        );
        upload = new File([blob], file.name.replace(/\.[^.]+$/, ".jpg"), {
          type: "image/jpeg",
        });
      }
      const form = new FormData();
      form.append("file", upload);
      const { data } = await api.post("/files", form);
      const url = URL.createObjectURL(upload);
      urls.push(url);
      emit("update:modelValue", [...props.modelValue, { ...data, url }]);
    }
  } catch (e) {
    fail(e);
  } finally {
    uploading.value = false;
    if (input.value) input.value.value = "";
  }
}
onBeforeUnmount(() => urls.forEach(URL.revokeObjectURL));
</script>
<template>
  <div class="photo-upload">
    <div class="photo-thumb" v-for="photo in modelValue" :key="photo.id">
      <img :src="photo.url" :alt="photo.name" /><button
        type="button"
        class="thumb-remove"
        @click="
          emit(
            'update:modelValue',
            modelValue.filter((p) => p.id !== photo.id),
          )
        "
        aria-label="移除照片"
      >
        <Icon name="close" :size="14" />
      </button>
    </div>
    <button
      v-if="modelValue.length < max"
      type="button"
      class="upload-tile"
      :disabled="uploading"
      @click="input?.click()"
    >
      <Icon
        :name="uploading ? 'loading' : 'image'"
        :class="{ spin: uploading }"
      /><span>{{ uploading ? "上传中" : "上传照片" }}</span></button
    ><input
      ref="input"
      type="file"
      accept="image/jpeg,image/png"
      multiple
      hidden
      @change="select"
    />
  </div>
</template>
