<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from "vue";
import * as echarts from "echarts";
const props = defineProps<{ option: echarts.EChartsOption }>();
const root = ref<HTMLElement>();
let chart: echarts.ECharts | undefined, observer: ResizeObserver | undefined;
function readableOption(option: echarts.EChartsOption): echarts.EChartsOption {
  return { ...option, textStyle: { fontSize: 14, ...option.textStyle } };
}
onMounted(() => {
  chart = echarts.init(root.value!);
  chart.setOption(readableOption(props.option));
  observer = new ResizeObserver(() => chart?.resize());
  observer.observe(root.value!);
});
watch(
  () => props.option,
  (o) => chart?.setOption(readableOption(o), true),
  { deep: true },
);
onBeforeUnmount(() => {
  observer?.disconnect();
  chart?.dispose();
});
</script>
<template><div ref="root" class="chart" /></template>
