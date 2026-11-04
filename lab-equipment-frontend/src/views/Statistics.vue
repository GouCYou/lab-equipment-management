<script setup lang="ts">
import { ref, computed, onMounted, watch } from "vue";
import { useRouter } from "vue-router";
import { api, state, fail, date, label } from "../state";
import type { Row } from "../state";
import Chart from "../components/Chart.vue";
import Icon from "../components/Icon.vue";
const router = useRouter(),
  data = ref<Row | null>(null),
  days = ref(30),
  lab = ref(""),
  loading = ref(false),
  error = ref(false);
const managedLabs = computed(() =>
  state.labs.filter(
    (l) =>
      state.user?.roles.includes("ADMIN") || state.user?.labIds.includes(l.id),
  ),
);
const colors = ["#7360c9", "#6c94cb", "#b1a0d8", "#d9ad7a", "#c98498"];
async function load() {
  loading.value = true;
  try {
    data.value = (
      await api.get("/statistics", {
        params: { days: days.value, labId: lab.value },
      })
    ).data;
    error.value = false;
  } catch (e) {
    error.value = true;
    fail(e);
  } finally {
    loading.value = false;
  }
}
const trend = computed(
  () =>
    ({
      color: colors,
      tooltip: { trigger: "axis" },
      grid: { top: 28, left: 45, right: 22, bottom: 34 },
      xAxis: {
        type: "category",
        boundaryGap: false,
        data: data.value?.trend?.map((r: Row) => r.date.slice(5)) || [],
        axisLine: { lineStyle: { color: "#dedbe9" } },
        axisLabel: { fontSize: 14, color: "#878193" },
      },
      yAxis: {
        type: "value",
        minInterval: 1,
        splitLine: { lineStyle: { color: "#f0edf5" } },
        axisLabel: { fontSize: 14, color: "#878193" },
      },
      series: [
        {
          type: "line",
          smooth: true,
          symbolSize: 6,
          data: data.value?.trend?.map((r: Row) => r.count) || [],
          areaStyle: { color: "#7360c912" },
          lineStyle: { width: 3 },
        },
      ],
    }) as any,
);
function pie(field: string) {
  return {
    color: colors,
    tooltip: { trigger: "item" },
    legend: {
      bottom: 0,
      icon: "circle",
      textStyle: { color: "#777183", fontSize: 14 },
    },
    series: [
      {
        type: "pie",
        radius: ["48%", "70%"],
        center: ["50%", "43%"],
        label: { show: false },
        data: Object.entries(data.value?.[field] || {}).map(
          ([name, value]) => ({ name: label(name), value }),
        ),
      },
    ],
  } as any;
}
const faults = computed(
  () =>
    ({
      color: ["#9c84bd"],
      tooltip: { trigger: "axis" },
      grid: { left: 115, right: 25, top: 20, bottom: 35 },
      xAxis: {
        type: "value",
        minInterval: 1,
        axisLabel: { fontSize: 14, color: "#81758d" },
        splitLine: { lineStyle: { color: "#f0edf5" } },
      },
      yAxis: {
        type: "category",
        data: Object.keys(data.value?.faultDistribution || {}),
        axisLabel: {
          fontSize: 14,
          color: "#81758d",
          width: 95,
          overflow: "truncate",
        },
      },
      series: [
        {
          type: "bar",
          barMaxWidth: 22,
          itemStyle: { borderRadius: [0, 5, 5, 0] },
          data: Object.values(data.value?.faultDistribution || {}),
        },
      ],
    }) as any,
);
function download() {
  if (!data.value) return;
  const quote = (v: unknown) =>
    '"' +
    String(v ?? "")
      .replace(/^[=+@-]/, "'$&")
      .replaceAll('"', '""') +
    '"';
  const rows = [
    ["设备", "资产编号", "可开放小时", "实际使用小时", "利用率%"],
    ...data.value.equipmentRates.map((r: Row) => [
      r.name,
      r.assetNo,
      r.openHours,
      r.usedHours,
      r.rate,
    ]),
  ];
  const blob = new Blob(
    ["\ufeff" + rows.map((row) => row.map(quote).join(",")).join("\r\n")],
    { type: "text/csv;charset=utf-8" },
  );
  const url = URL.createObjectURL(blob),
    a = document.createElement("a");
  a.href = url;
  a.download = "设备利用率.csv";
  a.click();
  URL.revokeObjectURL(url);
}
watch([days, lab], load);
onMounted(load);
</script>
<template>
  <div class="page" v-loading="loading">
    <div class="page-heading">
      <div>
        <h1>统计分析</h1>
      </div>
      <div class="heading-actions">
        <el-button
          @click="
            router.push({
              path: '/chat',
              query: {
                ask: '分析最近' + days + '天的设备利用率、借还和故障工单情况',
                lab: lab,
              },
            })
          "
          ><Icon name="spark" />让 AI 总结</el-button
        ><el-button type="primary" plain :disabled="!data" @click="download"
          ><Icon name="download" />导出明细</el-button
        >
      </div>
    </div>
    <div class="toolbar">
      <el-select v-model="days" class="filter-select"
        ><el-option :value="7" label="最近 7 天" /><el-option
          :value="30"
          label="最近 30 天" /><el-option
          :value="90"
          label="最近 90 天" /></el-select
      ><el-select
        v-model="lab"
        clearable
        placeholder="授权实验室"
        class="filter-select"
        ><el-option
          v-for="l in managedLabs"
          :key="l.id"
          :value="l.id"
          :label="l.name" /></el-select
      ><span class="result-count">统计截至 {{ date(data?.asOf) }}</span>
    </div>
    <div v-if="error" class="inline-error">
      统计加载失败 <button class="text-button" @click="load">重试</button>
    </div>
    <template v-if="data"
      ><div class="stats-grid">
        <div class="metric-card">
          <small>设备利用率</small
          ><strong>{{ data.openHours ? data.utilization + "%" : "—" }}</strong>
          <p>{{ data.usedHours }} / {{ data.openHours }} 设备小时</p>
        </div>
        <div class="metric-card">
          <small>实际借用次数</small
          ><strong>{{ data.loanCount }}<em>次</em></strong>
          <p>{{ data.equipmentCount }} 台设备纳入统计</p>
        </div>
        <div class="metric-card">
          <small>故障工单</small
          ><strong>{{ data.ticketCount }}<em>单</em></strong>
          <p>按所选周期内的创建时间统计</p>
        </div>
        <div class="metric-card">
          <small>已完成工单</small
          ><strong>{{ data.closedTickets }}<em>单</em></strong>
          <p>所选周期创建且当前已关闭</p>
        </div>
      </div>
      <div class="chart-grid">
        <section class="panel chart-panel wide">
          <h2>设备借用趋势</h2>
          <Chart :option="trend" />
        </section>
        <section class="panel chart-panel">
          <h2>设备类别分布</h2>
          <Chart :option="pie('category')" />
        </section>
        <section class="panel chart-panel">
          <h2>当前运行状态</h2>
          <Chart :option="pie('status')" />
        </section>
      </div>
      <div class="chart-grid">
        <section class="panel chart-panel">
          <h2>故障设备分布</h2>
          <Chart :option="faults" />
        </section>
        <section class="panel chart-panel">
          <h2>工单处理情况</h2>
          <Chart :option="pie('ticketStatus')" />
        </section>
      </div>
      <p class="definition">
        平均维修耗时 {{ data.averageRepairHours }} 小时（{{
          data.repairSamples
        }}
        个样本，受理至最近一次提交验收） · 当前借出
        {{ data.currentBorrowed }} 台 · 当前逾期 {{ data.currentOverdue }} 台
      </p>
      <section class="section">
        <div class="section-heading"><h2>设备利用率明细</h2></div>
        <div class="panel table-scroll">
          <table>
            <thead>
              <tr>
                <th>设备</th>
                <th>可开放时长</th>
                <th>实际使用时长</th>
                <th>利用率</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in data.equipmentRates" :key="r.id">
                <td>
                  <strong>{{ r.name }}</strong
                  ><small>{{ r.assetNo }}</small>
                </td>
                <td>{{ r.openHours }} 小时</td>
                <td>{{ r.usedHours }} 小时</td>
                <td>
                  <div class="rate-cell">
                    <span class="rate-track"
                      ><i
                        :style="{ width: Math.min(r.rate, 100) + '%' }" /></span
                    ><span>{{
                      r.openHours ? r.rate + "%" : "无开放时长"
                    }}</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="definition">
          <Icon name="chart" :size="16" />{{ data.definition }}
        </p>
      </section></template
    >
  </div>
</template>
