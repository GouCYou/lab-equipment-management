<script setup lang="ts">
import { label, statusClass } from "../state";
defineProps<{ value: string }>();
</script>
<template>
  <span class="badge" :class="statusClass(value)"><i />{{ label(value) }}</span>
</template>
