<script setup lang="ts">
import {
  ref,
  computed,
  watch,
  onMounted,
  onBeforeUnmount,
  nextTick,
} from "vue";
import { useRouter, useRoute } from "vue-router";
import { dialogs as ElMessageBox } from "../dialogs";
import MarkdownIt from "markdown-it";
import { api, state, staff, post, fail, date, changed } from "../state";
import type { Row } from "../state";
import Icon from "../components/Icon.vue";
import ThemePopover from "../components/ThemePopover.vue";
import Badge from "../components/Badge.vue";
import PhotoUpload from "../components/PhotoUpload.vue";
import Photos from "../components/Photos.vue";
import BusinessCard from "../components/BusinessCard.vue";
const toolLabels: Record<string, string> = {
  search_equipment: "查询设备",
  query_loans: "查询借还",
  query_tickets: "查询工单",
  get_statistics: "分析统计",
  search_knowledge: "检索资料",
  return_loan: "照片归还",
  create_reservation: "提交预约",
  get_ticket_context: "读取工单",
  list_laboratories: "查询实验室",
  report_fault: "提交报修",
};
function answerText(message: Row): string {
  const content = String(message.content || "");
  return message.mode === "demo"
    ? content.replace(
        /^演示模式 · 以下业务数据来自实际数据库，文字由演示规则生成。\s*/,
        "",
      )
    : content;
}
defineEmits(["toggle-nav", "toggle-rail"]);
const router = useRouter(),
  route = useRoute();
const history = ref<Row[]>([]),
  messages = ref<Row[]>([]),
  draft = ref(""),
  photos = ref<Row[]>([]),
  busy = ref(false),
  loading = ref(false),
  historyOpen = ref(window.innerWidth > 760),
  workOpen = ref(false),
  labId = ref(""),
  currentRun = ref<Row | null>(null),
  threadTitle = ref("新对话"),
  bottom = ref<HTMLElement>(),
  error = ref(""),
  ticketContext = ref<Row | null>(null);
let timer: ReturnType<typeof setTimeout> | undefined,
  generation = 0;
const technician = computed(
  () => !!state.user?.roles?.includes("TECHNICIAN") && !staff(),
);
const markdown = new MarkdownIt({ html: false, breaks: true, linkify: true });
markdown.renderer.rules.image = () => "<span>[图片]</span>";
const baseLink = markdown.renderer.rules.link_open;
markdown.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  tokens[idx].attrSet("target", "_blank");
  tokens[idx].attrSet("rel", "noopener noreferrer");
  return baseLink
    ? baseLink(tokens, idx, options, env, self)
    : self.renderToken(tokens, idx, options);
};
const prompts = computed(() =>
  staff()
    ? [
        {
          icon: "return",
          title: "看看归还情况",
          text: "帮我总结当前实验室的设备归还情况，哪些设备还没还？",
        },
        {
          icon: "ticket",
          title: "追踪故障工单",
          text: "总结当前故障工单的进度和负责人员，哪些还在处理中？",
        },
        {
          icon: "chart",
          title: "分析设备利用率",
          text: "分析最近30天的设备利用率和借用情况",
        },
      ]
    : technician.value
      ? [
          {
            icon: "ticket",
            title: "查看我的维修工单",
            text: "查看分派给我的故障工单，按进度总结待处理事项",
          },
          {
            icon: "book",
            title: "查询维修资料",
            text: "有哪些设备说明书和维修规范可以查阅？",
          },
          {
            icon: "equipment",
            title: "查找设备信息",
            text: "查看设备列表和型号信息",
          },
        ]
      : [
          {
            icon: "equipment",
            title: "找可以借的设备",
            text: "现在有什么设备可以借？",
          },
          {
            icon: "return",
            title: "归还我的设备",
            text: "查看我还没归还的设备，我想上传照片归还",
          },
          {
            icon: "ticket",
            title: "查看报修进度",
            text: "帮我看看我提交的故障工单处理到哪里了",
          },
        ],
);
async function histories() {
  history.value = (await api.get("/conversations")).data;
}
async function scroll() {
  await nextTick();
  bottom.value?.scrollIntoView({ behavior: "smooth", block: "end" });
}
async function load() {
  clearTimeout(timer);
  const g = ++generation;
  error.value = "";
  currentRun.value = null;
  ticketContext.value = null;
  const cid = String(route.query.id || "");
  if (route.query.lab) labId.value = String(route.query.lab);
  if (route.query.ticket)
    try {
      ticketContext.value = (
        await api.get("/tickets/" + route.query.ticket)
      ).data;
      draft.value =
        "请总结这张工单的故障现象、维修进度和建议检查步骤，并提供资料依据。";
    } catch (e) {
      fail(e);
    }
  if (!cid) {
    messages.value = [];
    busy.value = false;
    threadTitle.value = "新对话";
    if (route.query.ask) {
      draft.value = String(route.query.ask);
      await send(draft.value);
    }
    return;
  }
  loading.value = true;
  try {
    const { data } = await api.get("/conversations/" + cid);
    if (g !== generation) return;
    messages.value = data.messages;
    threadTitle.value = data.title;
    const active = data.runs.find((r: Row) =>
      ["QUEUED", "RUNNING"].includes(r.status),
    );
    const failed = data.runs.at(-1);
    if (!active && failed) currentRun.value = failed;
    if (active) {
      busy.value = true;
      void poll(active.id, g);
    } else {
      busy.value = false;
      if (failed && ["FAILED", "INTERRUPTED"].includes(failed.status)) {
        error.value = failed.error;
        currentRun.value = failed;
      }
    }
    await scroll();
  } catch (e) {
    if (g === generation) fail(e);
  } finally {
    if (g === generation) loading.value = false;
  }
}
async function poll(id: string, g: number) {
  try {
    const { data } = await api.get("/ai-runs/" + id);
    if (g !== generation) return;
    currentRun.value = data;
    if (["QUEUED", "RUNNING"].includes(data.status)) {
      timer = setTimeout(() => void poll(id, g), 1200);
      return;
    }
    busy.value = false;
    if (data.status !== "SUCCEEDED")
      error.value = data.error || "本次执行未完成";
    const detail = (await api.get("/conversations/" + route.query.id)).data;
    if (g !== generation) return;
    messages.value = detail.messages;
    threadTitle.value = detail.title;
    await histories();
    changed();
    await scroll();
  } catch (e) {
    if (g !== generation) return;
    busy.value = false;
    error.value = "查询执行状态失败，重新打开对话可继续查看结果";
    fail(e);
  }
}
async function send(text = draft.value) {
  if (busy.value || (!text.trim() && !photos.value.length)) return;
  busy.value = true;
  error.value = "";
  try {
    const context: Row = {};
    if (labId.value) context.labId = labId.value;
    if (ticketContext.value) context.ticketId = ticketContext.value.id;
    const data = await post("/chat", {
      conversationId: route.query.id || "",
      text: text.trim(),
      photoIds: photos.value.map((p) => p.id),
      context,
    });
    draft.value = "";
    photos.value = [];
    if (String(route.query.id || "") !== data.conversationId) {
      await router.replace({
        path: "/chat",
        query: { id: data.conversationId },
      });
    } else {
      const detail = (await api.get("/conversations/" + data.conversationId))
        .data;
      messages.value = detail.messages;
      const g = ++generation;
      void poll(data.runId, g);
    }
    await histories();
    await scroll();
  } catch (e) {
    busy.value = false;
    fail(e);
  }
}
async function remove(id: string) {
  try {
    await ElMessageBox.confirm(
      "删除后将移除这段对话及其执行记录。业务借还和工单记录会保留。",
      "删除对话",
      { confirmButtonText: "删除", cancelButtonText: "取消", type: "warning" },
    );
    await api.delete("/conversations/" + id);
    await histories();
    if (route.query.id === id) await router.push("/chat");
  } catch (e) {
    if (e !== "cancel" && e !== "close") fail(e);
  }
}
function fresh() {
  draft.value = "";
  photos.value = [];
  void router.push("/chat");
  if (!route.query.id) void load();
}
function key(event: KeyboardEvent) {
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    void send();
  }
}
watch(
  () => [route.query.id, route.query.ticket, route.query.ask],
  () => void load(),
);
onMounted(() => {
  void histories().catch(fail);
  void load();
  window.addEventListener("new-chat", fresh);
});
onBeforeUnmount(() => {
  generation++;
  clearTimeout(timer);
  window.removeEventListener("new-chat", fresh);
});
</script>
<template>
  <div
    class="chat-layout"
    :class="{ 'history-hidden': !historyOpen, 'work-open': workOpen }"
  >
    <aside class="history-panel" :class="{ visible: historyOpen }">
      <div class="history-top">
        <button @click="fresh"><Icon name="plus" />新对话</button
        ><button
          class="icon-button"
          aria-label="收起对话列表"
          @click="historyOpen = false"
        >
          <Icon name="close" :size="18" />
        </button>
      </div>
      <div class="history-list">
        <div v-if="!history.length" class="history-empty">
          你的对话会保存在这里
        </div>
        <div
          v-for="c in history"
          :key="c.id"
          class="history-row"
          :class="{ selected: route.query.id === c.id }"
        >
          <button @click="router.push({ path: '/chat', query: { id: c.id } })">
            {{ c.title }}</button
          ><button
            class="history-delete icon-button"
            aria-label="删除对话"
            @click="remove(c.id)"
          >
            <Icon name="delete" :size="15" />
          </button>
        </div>
      </div>
    </aside>
    <section class="conversation">
      <header class="chat-header">
        <div>
          <button
            class="icon-button mobile-toggle"
            aria-label="打开导航"
            @click="$emit('toggle-nav')"
          >
            <Icon name="menu" /></button
          ><button
            class="icon-button desktop-toggle"
            aria-label="切换导航"
            @click="$emit('toggle-rail')"
          >
            <Icon name="sidebar" /></button
          ><button
            class="icon-button"
            aria-label="切换对话列表"
            @click="historyOpen = !historyOpen"
          >
            <Icon name="clock" /></button
          ><span>{{ threadTitle }}</span
          ><span v-if="currentRun?.mode === 'demo'" class="demo-label"
            >演示模式</span
          >
        </div>
        <button
          class="icon-button"
          :class="{ active: workOpen }"
          aria-label="切换工作区"
          @click="workOpen = !workOpen"
        >
          <Icon name="filter" />
        </button>
      </header>
      <div class="conversation-scroll" v-loading="loading">
        <div v-if="!messages.length" class="welcome">
          <span class="welcome-mark"><Icon name="lab" :size="36" /></span>
          <h1>
            {{
              staff()
                ? "实验室的事，直接问我。"
                : technician
                  ? "今天有哪些维修任务？"
                  : "你好，今天需要什么设备？"
            }}
          </h1>
          <div class="prompt-grid">
            <button v-for="p in prompts" :key="p.title" @click="send(p.text)">
              <Icon :name="p.icon" :size="24" /><strong>{{ p.title }}</strong
              ><Icon name="arrow" :size="17" />
            </button>
          </div>
        </div>
        <div v-else class="messages">
          <div class="conversation-date">
            {{ date(messages[0]?.createdAt) }}
          </div>
          <article
            v-for="m in messages"
            :key="m.id"
            class="message"
            :class="m.role"
          >
            <div v-if="m.role === 'user'" class="user-bubble">
              <p v-if="m.content">{{ m.content }}</p>
              <Photos :ids="m.attachments" />
            </div>
            <template v-else
              ><div class="assistant-label">
                <span class="mini-mark"><Icon name="lab" :size="16" /></span
                >博仪<span v-if="m.mode === 'demo'"> · 演示模式</span>
              </div>
              <div class="markdown" v-html="markdown.render(answerText(m))" />
              <div v-if="m.cards?.length" class="business-cards">
                <BusinessCard
                  v-for="(card, index) in m.cards"
                  :key="index"
                  :card="card"
                />
              </div>
              <div v-if="m.citations?.length" class="citations">
                <span>参考资料</span>
                <details
                  v-for="citation in m.citations"
                  :key="citation.chunkId"
                >
                  <summary>
                    <Icon name="book" :size="16" />{{ citation.title }} · V{{
                      citation.versionNo
                    }}
                    · {{ citation.locator }}
                  </summary>
                  <p>{{ citation.excerpt }}</p>
                </details>
              </div></template
            >
          </article>
          <div v-if="busy" class="thinking">
            <Icon name="loading" class="spin" :size="17" /><span>{{
              currentRun?.events?.at(-1)?.toolName
                ? "正在" +
                  (toolLabels[currentRun.events.at(-1).toolName] ||
                    "处理请求") +
                  "…"
                : "正在查询与处理…"
            }}</span>
          </div>
          <div v-if="error" class="inline-error">
            <strong>本次未完成</strong>
            <p>{{ error }}</p>
            <button
              class="text-button"
              @click="
                draft =
                  messages.filter((m) => m.role === 'user').at(-1)?.content ||
                  ''
              "
            >
              重新填写
            </button>
          </div>
        </div>
        <div ref="bottom" />
      </div>
      <div class="composer-area">
        <div v-if="ticketContext" class="context-chip">
          <Icon name="ticket" :size="16" />{{ ticketContext.ticketNo }} ·
          {{ ticketContext.title
          }}<button
            class="icon-button"
            aria-label="清除工单上下文"
            @click="ticketContext = null"
          >
            <Icon name="close" :size="14" />
          </button>
        </div>
        <div class="composer">
          <div v-if="photos.length" class="composer-photos">
            <span v-for="p in photos" :key="p.id"
              ><img :src="p.url" :alt="p.name" /><button
                aria-label="移除图片"
                @click="photos = photos.filter((x) => x.id !== p.id)"
              >
                <Icon name="close" :size="14" /></button
            ></span>
          </div>
          <textarea
            v-model="draft"
            aria-label="给博仪发送消息"
            :disabled="busy"
            :placeholder="
              messages.length
                ? '继续说说你想做什么…'
                : technician
                  ? '描述故障，或查询待处理工单…'
                  : '问问有什么设备可借，或上传归还照片…'
            "
            @keydown="key"
            rows="1"
          />
          <div class="composer-toolbar">
            <ThemePopover
              placement="top-start"
              :width="340"
              label="添加设备图片"
              ><PhotoUpload v-model="photos" /><template
                #trigger="{ toggle, open }"
                ><button
                  class="icon-button"
                  aria-label="添加设备图片"
                  :disabled="busy"
                  :aria-expanded="open"
                  @click="toggle"
                >
                  <Icon name="attach" /></button></template></ThemePopover
            ><select v-model="labId" aria-label="当前实验室">
              <option value="">
                {{ staff() ? "授权实验室" : "全部实验室" }}
              </option>
              <option v-for="l in state.labs" :key="l.id" :value="l.id">
                {{ l.name }}
              </option></select
            ><button
              class="send-button"
              aria-label="发送消息"
              :disabled="busy || (!draft.trim() && !photos.length)"
              @click="send()"
            >
              <Icon :name="busy ? 'loading' : 'send'" :class="{ spin: busy }" />
            </button>
          </div>
        </div>
      </div>
    </section>
    <aside class="work-panel" :aria-hidden="!workOpen" :inert="!workOpen">
      <header>
        <h3>工作区</h3>
        <button
          class="icon-button"
          aria-label="关闭工作区"
          @click="workOpen = false"
        >
          <Icon name="close" :size="17" />
        </button>
      </header>
      <div class="work-section">
        <h4>本次执行</h4>
        <div v-if="currentRun" class="run-status">
          <Badge :value="currentRun.status" /><span>{{
            currentRun.mode === "demo" ? "演示模式" : "真实模型"
          }}</span>
        </div>
        <p v-else class="muted">发送消息后，这里会展示实际工具调用。</p>
        <div
          v-for="(event, index) in currentRun?.events || []"
          :key="index"
          class="tool-event"
        >
          <Icon name="check" :size="17" />
          <div>
            <strong>{{ toolLabels[event.toolName] || event.toolName }}</strong>
            <p v-if="event.summary && event.summary !== '已按当前账户权限执行'">
              {{ event.summary }}
            </p>
            <small>{{ event.durationMs }} 毫秒</small>
          </div>
        </div>
      </div>
      <div class="work-section">
        <h4>常用入口</h4>
        <router-link to="/equipment"
          ><Icon name="equipment" />设备中心<Icon
            name="arrow"
            :size="16" /></router-link
        ><router-link to="/loans"
          ><Icon name="return" />借还记录<Icon
            name="arrow"
            :size="16" /></router-link
        ><router-link to="/tickets"
          ><Icon name="ticket" />故障工单<Icon name="arrow" :size="16"
        /></router-link>
      </div>
    </aside>
  </div>
</template>
