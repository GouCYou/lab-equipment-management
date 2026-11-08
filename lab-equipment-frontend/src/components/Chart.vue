<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from "vue";
import { isDark } from "../preferences";
import * as echarts from "echarts";
const props = defineProps<{ option: echarts.EChartsOption }>();
const root = ref<HTMLElement>();
let chart: echarts.ECharts | undefined, observer: ResizeObserver | undefined;
function readableOption(option: echarts.EChartsOption): echarts.EChartsOption {
  if (!isDark.value)
    return { ...option, textStyle: { fontSize: 14, ...option.textStyle } };
  const axis = (value: any): any =>
    Array.isArray(value)
      ? value.map(axis)
      : value
        ? {
            ...value,
            axisLabel: { ...value.axisLabel, color: "#b9aacb" },
            axisLine: {
              ...value.axisLine,
              lineStyle: { ...value.axisLine?.lineStyle, color: "#65536f" },
            },
            splitLine: {
              ...value.splitLine,
              lineStyle: { ...value.splitLine?.lineStyle, color: "#3b3047" },
            },
          }
        : value;
  const legend = (value: any): any =>
    Array.isArray(value)
      ? value.map(legend)
      : value
        ? { ...value, textStyle: { ...value.textStyle, color: "#b9aacb" } }
        : value;
  return {
    ...option,
    backgroundColor: "transparent",
    textStyle: { ...option.textStyle, fontSize: 14, color: "#d8cbe5" },
    xAxis: axis(option.xAxis),
    yAxis: axis(option.yAxis),
    legend: legend(option.legend),
    tooltip: {
      ...(option.tooltip as any),
      backgroundColor: "#282232",
      borderColor: "#51415e",
      textStyle: { color: "#e8dcf2" },
    },
  };
}
function render() {
  chart?.dispose();
  chart = echarts.init(root.value!, isDark.value ? "dark" : undefined);
  chart.setOption(readableOption(props.option));
}
onMounted(() => {
  render();
  observer = new ResizeObserver(() => chart?.resize());
  observer.observe(root.value!);
});
watch([() => props.option, isDark], render, { deep: true });
onBeforeUnmount(() => {
  observer?.disconnect();
  chart?.dispose();
});
</script>
<template><div ref="root" class="chart" /></template>
