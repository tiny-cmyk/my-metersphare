<template>
  <div class="flex h-full flex-col p-[16px]">
    <MsNotRemind tip="testPlan.planTip" class="mb-[16px]" type="info" visited-key="testPlanTip" />
    <div class="relative flex-1 overflow-hidden">
      <MsTestPlanMinder ref="minderRef" :plan-id="props.planId" :status="props.status" @save="emit('refresh')" />
      <a-button type="primary" class="add-case-btn" @click="handleAddCase">
        <template #icon><icon-plus /></template>
        添加用例
      </a-button>
    </div>
  </div>
</template>

<script setup lang="ts">
  import MsTestPlanMinder from '@/components/business/ms-minders/testPlanMinder/index.vue';
  import MsNotRemind from '@/components/business/ms-not-remind/index.vue';

  const props = defineProps<{
    planId: string;
    status: string;
  }>();
  const emit = defineEmits<{
    (e: 'refresh'): void;
  }>();

  const minderRef = ref<InstanceType<typeof MsTestPlanMinder>>();

  function handleAddCase() {
    const { minder } = window as any;
    if (minder) {
      const root = minder.getRoot();
      const funcNode = root?.children?.[0];
      if (funcNode) {
        minder.select(funcNode, true);
        const testSetNode = funcNode.children?.[0];
        if (testSetNode) {
          minder.select(testSetNode, true);
          const caseCountNode = testSetNode.children?.[0];
          if (caseCountNode) {
            minder.select(caseCountNode, true);
          }
        }
      }
    }
    if (minderRef.value) {
      (minderRef.value as any).associateCase?.();
    }
  }
</script>

<style lang="less" scoped>
  .add-case-btn {
    position: absolute;
    top: 48px;
    left: 16px;
    z-index: 10;
  }
</style>
