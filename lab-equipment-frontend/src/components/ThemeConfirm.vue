<script setup lang="ts">
import { watch, nextTick, ref, useId, onBeforeUnmount } from "vue";
import { dialogState as dialog, finishDialog } from "../dialogs";
import Icon from "./Icon.vue";
const id = useId(),
  panel = ref<HTMLElement>(),
  input = ref<HTMLTextAreaElement | HTMLInputElement>();
let previous: HTMLElement | null = null,
  overflow = "";
function keys(e: KeyboardEvent) {
  if (e.key === "Escape") {
    e.preventDefault();
    finishDialog(false);
  }
  if (e.key === "Tab" && panel.value) {
    const controls = [
      ...panel.value.querySelectorAll<HTMLElement>("button,input,textarea"),
    ];
    const first = controls[0],
      last = controls.at(-1);
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last?.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first?.focus();
    }
  }
}
watch(
  () => dialog.open,
  async (open) => {
    if (open) {
      previous = document.activeElement as HTMLElement;
      overflow = document.body.style.overflow;
      document.body.style.overflow = "hidden";
      await nextTick();
      (dialog.prompt
        ? input.value
        : panel.value?.querySelector<HTMLElement>(".theme-primary")
      )?.focus();
      document.addEventListener("keydown", keys);
    } else {
      document.removeEventListener("keydown", keys);
      document.body.style.overflow = overflow;
      await nextTick();
      if (previous?.isConnected) previous.focus();
    }
  },
);
onBeforeUnmount(() => document.removeEventListener("keydown", keys));
</script>
<template>
  <Teleport to="body"
    ><Transition name="theme-modal"
      ><div
        v-if="dialog.open"
        class="theme-modal-veil"
        @click.self="finishDialog(false)"
      >
        <form
          ref="panel"
          class="theme-confirm"
          role="dialog"
          aria-modal="true"
          :aria-labelledby="id"
          @submit.prevent="finishDialog(true)"
        >
          <header>
            <h2 :id="id">{{ dialog.title }}</h2>
            <button
              type="button"
              class="icon-button"
              aria-label="关闭对话框"
              @click="finishDialog(false)"
            >
              <Icon name="close" />
            </button>
          </header>
          <p v-if="dialog.message">{{ dialog.message }}</p>
          <input
            v-if="dialog.prompt && dialog.singleLine"
            ref="input"
            v-model="dialog.value"
            type="text"
            :aria-label="dialog.inputLabel"
            maxlength="100"
          />
          <textarea
            v-else-if="dialog.prompt"
            ref="input"
            v-model="dialog.value"
            :aria-label="dialog.inputLabel"
            rows="3"
          />
          <p v-if="dialog.error" class="danger-text">{{ dialog.error }}</p>
          <footer>
            <button
              type="button"
              class="theme-secondary"
              @click="finishDialog(false)"
            >
              {{ dialog.cancelText }}</button
            ><button type="submit" class="theme-primary">
              {{ dialog.confirmText }}
            </button>
          </footer>
        </form>
      </div></Transition
    ></Teleport
  >
</template>
