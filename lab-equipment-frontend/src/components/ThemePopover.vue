<script setup lang="ts">
import { ref, nextTick, onBeforeUnmount, useId } from "vue";
import Icon from "./Icon.vue";
type Item = {
  id: string;
  label: string;
  icon?: string;
  divider?: boolean;
  danger?: boolean;
  dot?: boolean;
};
const props = withDefaults(
  defineProps<{
    items?: Item[];
    placement?: string;
    width?: number;
    label?: string;
  }>(),
  { placement: "bottom-end", width: 220, label: "操作菜单" },
);
const emit = defineEmits<{ select: [id: string] }>();
const id = useId(),
  open = ref(false),
  anchor = ref<HTMLElement>(),
  panel = ref<HTMLElement>(),
  position = ref({ left: "0px", top: "0px" });
let trigger: HTMLElement | undefined;
function place() {
  if (!anchor.value || !panel.value) return;
  const a = anchor.value.getBoundingClientRect(),
    p = panel.value.getBoundingClientRect();
  let x = props.placement.endsWith("start") ? a.left : a.right - p.width;
  let y = props.placement.startsWith("right")
    ? a.bottom - p.height
    : props.placement.startsWith("top")
      ? a.top - p.height - 10
      : a.bottom + 10;
  if (props.placement.startsWith("right")) x = a.right + 12;
  if (y < 8) y = a.bottom + 10;
  if (y + p.height > innerHeight - 8) y = Math.max(8, a.top - p.height - 10);
  position.value = {
    left: Math.max(8, Math.min(x, innerWidth - p.width - 8)) + "px",
    top: y + "px",
  };
}
function close(restore = false) {
  open.value = false;
  trigger?.setAttribute("aria-expanded", "false");
  document.removeEventListener("pointerdown", outside);
  document.removeEventListener("keydown", keys);
  window.removeEventListener("resize", place);
  window.removeEventListener("scroll", place, true);
  if (restore) trigger?.focus();
}
async function toggle() {
  if (open.value) {
    close();
    return;
  }
  window.dispatchEvent(new Event("close-theme-popovers"));
  trigger = anchor.value?.querySelector("button") || undefined;
  trigger?.setAttribute("aria-expanded", "true");
  trigger?.setAttribute("aria-controls", id);
  trigger?.setAttribute("aria-haspopup", props.items ? "menu" : "dialog");
  open.value = true;
  await nextTick();
  place();
  document.addEventListener("pointerdown", outside);
  document.addEventListener("keydown", keys);
  window.addEventListener("resize", place);
  window.addEventListener("scroll", place, true);
  panel.value?.querySelector<HTMLElement>("button,input,textarea")?.focus();
}
function outside(e: PointerEvent) {
  if (
    !anchor.value?.contains(e.target as Node) &&
    !panel.value?.contains(e.target as Node)
  )
    close();
}
function keys(e: KeyboardEvent) {
  if (e.key === "Escape") {
    e.preventDefault();
    e.stopPropagation();
    close(true);
    return;
  }
  if (!props.items) return;
  const buttons = [
    ...panel.value!.querySelectorAll<HTMLButtonElement>("button"),
  ];
  const i = buttons.indexOf(document.activeElement as HTMLButtonElement);
  if (["ArrowDown", "ArrowUp", "Home", "End"].includes(e.key)) {
    e.preventDefault();
    const n =
      e.key === "Home"
        ? 0
        : e.key === "End"
          ? buttons.length - 1
          : (i + (e.key === "ArrowDown" ? 1 : -1) + buttons.length) %
            buttons.length;
    buttons[n]?.focus();
  }
  if (e.key === "Tab") close(true);
}
function selected(key: string) {
  close(true);
  emit("select", key);
}
function dismiss() {
  close();
}
window.addEventListener("close-theme-popovers", dismiss);
onBeforeUnmount(() => {
  close();
  window.removeEventListener("close-theme-popovers", dismiss);
});
</script>
<template>
  <span ref="anchor" class="theme-popover-anchor"
    ><slot name="trigger" :toggle="toggle" :open="open"
  /></span>
  <Teleport to="body"
    ><Transition name="theme-pop"
      ><div
        v-if="open"
        :id="id"
        ref="panel"
        class="theme-popover"
        :class="{ 'theme-menu': !!items }"
        :style="{ ...position, width: width + 'px' }"
        :role="items ? 'menu' : 'dialog'"
        :aria-label="label"
      >
        <template v-if="items"
          ><button
            v-for="item in items"
            :key="item.id"
            type="button"
            role="menuitem"
            :class="{ divider: item.divider, danger: item.danger }"
            @click="selected(item.id)"
          >
            <Icon v-if="item.icon" :name="item.icon" :size="19" /><span>{{
              item.label
            }}</span>
          </button></template
        ><slot v-else /></div></Transition
  ></Teleport>
</template>
