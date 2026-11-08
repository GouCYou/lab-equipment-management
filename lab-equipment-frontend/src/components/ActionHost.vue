<script setup lang="ts">
import { requestId } from "../request-id";
import { ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { actions, state, api, post, fail, changed } from "../state";
import type { Row } from "../state";
import PhotoUpload from "./PhotoUpload.vue";
const busy = ref(false),
  photos = ref<Row[]>([]),
  form = ref<Row>({}),
  equipment = ref<Row[]>([]),
  key = ref("");
function local(d: Date) {
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 16);
}
watch(
  () => actions.kind,
  async (kind) => {
    if (!kind) return;
    photos.value = [];
    key.value = requestId();
    const start = new Date();
    start.setSeconds(0, 0);
    start.setMinutes(Math.ceil((start.getMinutes() + 5) / 30) * 30);
    if (start.getHours() < 8) start.setHours(8, 0);
    if (start.getHours() >= 21) {
      start.setDate(start.getDate() + 1);
      start.setHours(8, 0);
    }
    form.value = {
      startsAt: local(start),
      endsAt: local(new Date(start.getTime() + 3600000)),
      purpose: "",
      note: "",
      title: "",
      description: "",
      severity: "NORMAL",
      equipmentId: actions.item?.equipmentId || actions.item?.id || "",
      damaged: false,
      faultDescription: "",
    };
    if (kind === "report")
      try {
        equipment.value = (await api.get("/equipment")).data;
      } catch (e) {
        fail(e);
      }
  },
);
async function submit() {
  busy.value = true;
  try {
    if (actions.kind === "borrow") {
      await post(
        "/reservations",
        {
          equipmentId: actions.item!.id,
          purpose: form.value.purpose,
          startsAt: new Date(form.value.startsAt).toISOString(),
          endsAt: new Date(form.value.endsAt).toISOString(),
        },
        key.value,
      );
      ElMessage.success("预约申请已提交，等待实验员审核");
    } else if (actions.kind === "return") {
      await post(
        "/loans/" + actions.item!.id + "/return",
        {
          note: form.value.note,
          photoIds: photos.value.map((p) => p.id),
          damaged: form.value.damaged,
          faultDescription: form.value.faultDescription,
        },
        key.value,
      );
      ElMessage.success("设备已归还，照片已保存");
    } else {
      await post("/tickets", {
        equipmentId: form.value.equipmentId,
        title: form.value.title,
        description: form.value.description,
        severity: form.value.severity,
        photoIds: photos.value.map((p) => p.id),
        ...(actions.item?.loanId ? { loanId: actions.item.loanId } : {}),
      });
      ElMessage.success("报修已提交，等待核实");
    }
    actions.kind = "";
    changed();
  } catch (e) {
    fail(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <el-dialog
    :model-value="!!actions.kind"
    :title="
      actions.kind === 'borrow'
        ? '申请借用'
        : actions.kind === 'return'
          ? '照片归还'
          : '提交故障报修'
    "
    width="560px"
    :close-on-click-modal="!busy"
    :close-on-press-escape="!busy"
    @close="!busy && (actions.kind = '')"
    ><form @submit.prevent="submit" class="form-stack">
      <div v-if="actions.kind !== 'report'" class="selection-summary">
        <strong>{{ actions.item?.equipmentName || actions.item?.name }}</strong
        ><span>{{ actions.item?.assetNo }} · {{ actions.item?.model }}</span>
      </div>
      <template v-if="actions.kind === 'borrow'"
        ><div class="form-pair">
          <label
            >开始时间<input
              required
              type="datetime-local"
              v-model="form.startsAt" /></label
          ><label
            >结束时间<input
              required
              type="datetime-local"
              v-model="form.endsAt"
          /></label>
        </div>
        <label
          >借用用途<el-input
            v-model="form.purpose"
            type="textarea"
            :rows="3"
            maxlength="600"
            placeholder="例如：电路课程实验，测量交流信号"
        /></label>
        <p class="form-note">
          提交后由实验员审核，获批后到实验室领取。
        </p></template
      ><template v-else-if="actions.kind === 'return'"
        ><label>归还照片<PhotoUpload v-model="photos" /></label
        ><label
          >归还备注<el-input
            v-model="form.note"
            type="textarea"
            :rows="2"
            placeholder="设备放置位置、配件情况等" /></label
        ><el-checkbox v-model="form.damaged"
          >设备存在异常，同时提交报修</el-checkbox
        ><label v-if="form.damaged"
          >异常描述<el-input
            v-model="form.faultDescription"
            type="textarea"
            :rows="3"
        /></label>
        <p class="form-note">
          提交即完成归还。请确保实物已放回指定位置，照片能清楚展示设备及配件。
        </p></template
      ><template v-else
        ><label
          >故障设备<el-select v-model="form.equipmentId" filterable
            ><el-option
              v-for="e in equipment"
              :key="e.id"
              :value="e.id"
              :label="e.name + ' · ' + e.assetNo" /></el-select></label
        ><label
          >故障标题<el-input
            v-model="form.title"
            maxlength="160"
            placeholder="简要描述故障现象" /></label
        ><label
          >详细描述<el-input
            v-model="form.description"
            type="textarea"
            :rows="4"
            maxlength="4000"
            placeholder="发生时间、故障现象、已尝试的操作" /></label
        ><label
          >紧急程度<el-select v-model="form.severity"
            ><el-option value="NORMAL" label="普通" /><el-option
              value="URGENT"
              label="紧急" /></el-select></label
        ><label>现场照片<PhotoUpload v-model="photos" /></label
      ></template>
      <div class="dialog-actions">
        <el-button :disabled="busy" @click="actions.kind = ''">取消</el-button
        ><el-button
          native-type="submit"
          type="primary"
          :loading="busy"
          :disabled="actions.kind === 'return' && !photos.length"
          >{{
            actions.kind === "borrow"
              ? "提交申请"
              : actions.kind === "return"
                ? "确认归还"
                : "提交报修"
          }}</el-button
        >
      </div>
    </form></el-dialog
  ><span hidden>{{ state.revision }}</span>
</template>
