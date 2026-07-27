<template>
  <div class="flex h-full flex-col p-[16px]">
    <div class="mb-[16px] flex items-center justify-between">
      <MsNotRemind tip="testPlan.planTip" type="info" visited-key="testPlanTip" />
      <a-button type="primary" @click="handleAddCase">
        <template #icon><icon-plus /></template>
        添加用例
      </a-button>
    </div>
    <div class="flex-1 overflow-hidden">
      <MsTestPlanMinder ref="minderRef" :plan-id="props.planId" :status="props.status" @save="emit('refresh')" />
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
    // 先选中功能用例节点，再触发关联
    const { minder } = window;
    if (minder) {
      const root = minder.getRoot();
      const funcNode = root?.children?.[0]; // 功能用例节点
      if (funcNode) {
        minder.select(funcNode, true);
        // 查找用例数节点并选中
        const testSetNode = funcNode.children?.[0]; // 基本功能点
        if (testSetNode) {
          minder.select(testSetNode, true);
          const caseCountNode = testSetNode.children?.[0]; // 用例数节点
          if (caseCountNode) {
            minder.select(caseCountNode, true);
          }
        }
      }
    }
    // 触发 MsTestPlanMinder 内部的 associateCase
    if (minderRef.value) {
      (minderRef.value as any).associateCase?.();
      // 如果 associateCase 没暴露，尝试直接打开
      if (!(minderRef.value as any).associateCase) {
        (minderRef.value as any).openCaseAssociateDrawer?.();
      }
    }
  }
</script>

<style lang="less" scoped></style>
