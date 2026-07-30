<template>
  <div v-if="statusInfo" class="flex items-center">
    <div
      class="mr-[4px] h-[8px] w-[8px] rounded-full"
      :style="{
        backgroundColor: statusInfo.bgColor,
        border: `1px solid ${statusInfo.borderColor}`,
      }"
    ></div>
    {{ statusInfo.label }}
  </div>
  <span v-else class="text-[var(--color-text-2)]"> - </span>
</template>

<script setup lang="ts">
  export type AutomationStatusValue = 'automatable' | 'automated' | 'manual' | 'to_be_confirmed' | 'not_applicable';

  const props = defineProps<{
    status?: string;
  }>();

  const statusMap: Record<string, { label: string; bgColor: string; borderColor: string }> = {
    automatable: {
      label: '可自动化',
      bgColor: 'rgb(var(--link-2))',
      borderColor: 'rgb(var(--link-5))',
    },
    automated: {
      label: '已自动化',
      bgColor: 'rgb(var(--success-2))',
      borderColor: 'rgb(var(--success-6))',
    },
    manual: {
      label: '需手工',
      bgColor: 'rgb(var(--warning-2))',
      borderColor: 'rgb(var(--warning-6))',
    },
    to_be_confirmed: {
      label: '待确认',
      bgColor: 'var(--color-text-n8)',
      borderColor: 'var(--color-text-brand)',
    },
    not_applicable: {
      label: '不涉及',
      bgColor: 'var(--color-fill-4)',
      borderColor: 'var(--color-border-3)',
    },
  };

  const statusInfo = computed(() => (props.status ? statusMap[props.status] : undefined));
</script>

<style lang="less" scoped></style>
