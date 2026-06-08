<template>
  <MsDrawer
    v-model:visible="showBugDrawer"
    :mask="true"
    :title="bugId ? t('bugManagement.editBug') : t('caseManagement.featureCase.createDefect')"
    :ok-text="t('common.create')"
    :ok-loading="drawerLoading"
    :width="1200"
    :mask-closable="true"
    unmount-on-close
    :show-continue="true"
    no-content-padding
    :save-continue-text="t('case.saveContinueText')"
    @continue="handleDrawerConfirm(true)"
    @confirm="handleDrawerConfirm"
    @cancel="handleDrawerCancel"
  >
    <template #tbutton>
      <div class="flex items-center gap-[16px] font-normal">
        <a-select
          v-model="selectedProjectId"
          class="w-[200px]"
          :options="projectOptions"
          allow-search
          placeholder="选择项目"
          @change="onProjectChange"
        >
          <template #prefix>
            <span class="text-[var(--color-text-brand)]">项目</span>
          </template>
        </a-select>
        <a-select
          v-model="bugTemplateId"
          class="w-[240px]"
          :options="templateOption"
          allow-search
          :placeholder="t('bugManagement.edit.defaultSystemTemplate')"
        >
          <template #prefix>
            <span class="text-[var(--color-text-brand)]">{{ t('system.orgTemplate.defectTemplates') }}</span>
          </template>
        </a-select>
        <a-checkbox v-model="syncToLinear">同步到 Linear</a-checkbox>
      </div>
    </template>
    <div class="h-[calc(100vh-122px)] w-full p-[16px]">
      <BugDetail
        ref="bugDetailRef"
        v-model:template-id="bugTemplateId"
        is-drawer
        :bug-id="bugId"
        :case-type="props.caseType"
        :fill-config="props.fillConfig"
        :project-id="selectedProjectId"
        file-name-max-width="300px"
        @save-params="saveParams"
      />
    </div>
  </MsDrawer>
</template>

<script setup lang="ts">
  import { ref } from 'vue';
  import { Message } from '@arco-design/web-vue';

  import MsDrawer from '@/components/pure/ms-drawer/index.vue';
  import BugDetail from '@/views/bug-management/edit.vue';

  import { createLinearIssue, createOrUpdateBug, getTemplateOption } from '@/api/modules/bug-management';
  import { getProjectList } from '@/api/modules/project-management/project';
  import {
    batchAddBugToApiCase,
    batchAddBugToFunctionCase,
    batchAddBugToMinderCase,
    batchAddBugToScenarioCase,
  } from '@/api/modules/test-plan/testPlan';
  import { useI18n } from '@/hooks/useI18n';
  import { useAppStore } from '@/store';

  import { BugEditFormObject } from '@/models/bug-management';
  import { CaseLinkEnum } from '@/enums/caseEnum';

  const { t } = useI18n();
  const appStore = useAppStore();

  interface TemplateOption {
    label: string;
    value: string;
  }

  const props = defineProps<{
    bugId?: string;
    extraParams?: Record<string, any> | (() => Record<string, any>);
    isBatch?: boolean;
    caseType?: CaseLinkEnum; // 批量添加到的用例类型
    fillConfig?: {
      isQuickFillContent: boolean; // 是否快速填充内容
      detailId: string; // 功能用例为用例详情id， 注意：接口用例为最后执行报告id来获取执行详情展示断言，场景也为执行报告id查看报告详情
      name: string; // 用例明细名称
    };
    isMinderBatch?: boolean;
  }>();

  const emit = defineEmits<{
    (e: 'success'): void;
    (e: 'save', params: { request: BugEditFormObject; fileList: File[] }): void;
  }>();

  const showBugDrawer = defineModel<boolean>('visible', {
    required: true,
  });

  const bugDetailRef = ref<InstanceType<typeof BugDetail>>();
  const drawerLoading = ref(false);
  const isEdit = computed(() => props.bugId);

  const bugTemplateId = ref<string>('');
  const syncToLinear = ref<boolean>(false);

  interface ProjectOption {
    label: string;
    value: string;
  }
  const projectOptions = ref<ProjectOption[]>([]);
  const selectedProjectId = ref<string>(appStore.currentProjectId);

  const templateOption = ref<TemplateOption[]>([]);

  const loadProjects = async () => {
    try {
      const res = await getProjectList(appStore.currentOrgId);
      projectOptions.value = res.map((p) => ({ label: p.name, value: p.id }));
      if (!selectedProjectId.value && res.length > 0) {
        selectedProjectId.value = res[0].id;
      }
    } catch (error) {
      console.log(error);
    }
  };

  const onProjectChange = async () => {
    bugTemplateId.value = '';
    // eslint-disable-next-line no-use-before-define
    await getTemplateOptions();
  };

  const getTemplateOptions = async () => {
    try {
      drawerLoading.value = true;
      const res = await getTemplateOption(selectedProjectId.value || appStore.currentProjectId);
      templateOption.value = res.map((item) => {
        if (item.enableDefault && !isEdit.value) {
          // 选中默认模板
          bugTemplateId.value = item.id;
        }
        return {
          label: item.name,
          value: item.id,
        };
      });
    } catch (error) {
      // eslint-disable-next-line no-console
      console.log(error);
    } finally {
      drawerLoading.value = false;
    }
  };
  function handleDrawerCancel() {
    bugDetailRef.value?.resetForm();
    showBugDrawer.value = false;
    bugTemplateId.value = '';
    selectedProjectId.value = appStore.currentProjectId;
  }

  const batchAddApiMap: Record<string, (params: { request: BugEditFormObject; fileList: File[] }) => Promise<any>> = {
    [CaseLinkEnum.FUNCTIONAL]: batchAddBugToFunctionCase,
    [CaseLinkEnum.API]: batchAddBugToApiCase,
    [CaseLinkEnum.SCENARIO]: batchAddBugToScenarioCase,
  };

  async function saveParams(isContinue: boolean, params: { request: BugEditFormObject; fileList: File[] }) {
    try {
      drawerLoading.value = true;
      const { request, fileList } = params;
      const extraParam =
        props.extraParams && typeof props.extraParams === 'function' ? await props.extraParams() : props.extraParams;
      let createdBug: any = null;
      if (props.isBatch && props.caseType) {
        await batchAddApiMap[props.caseType]({ request: { ...request, ...extraParam }, fileList });
      } else if (props.isMinderBatch) {
        await batchAddBugToMinderCase({ request: { ...request, ...props.extraParams }, fileList });
      } else {
        createdBug = await createOrUpdateBug({ request: { ...request, ...extraParam }, fileList });
      }

      Message.success(props.bugId ? t('common.updateSuccess') : t('common.createSuccess'));

      // 同步到 Linear
      if (syncToLinear.value && !props.bugId && createdBug) {
        try {
          const bugData = createdBug.data || createdBug;
          const link = `${window.location.origin}/#/bug-management/index?id=${bugData.id}`;
          const result = await createLinearIssue({
            bugId: bugData.id,
            num: bugData.num,
            title: request.title || request.name || '',
            description: '',
            priority: request.severity || request.priority || '',
            link,
          });
          Message.success(`已同步到 Linear：${result.identifier} ${result.url}`);
        } catch (e) {
          Message.warning('缺陷已创建，但同步 Linear 失败，请手动提交');
        }
      }

      if (isContinue) {
        bugDetailRef.value?.resetForm();
      } else {
        handleDrawerCancel();
      }
      emit('success');
    } catch (error) {
      // eslint-disable-next-line no-console
      console.log(error);
    } finally {
      drawerLoading.value = false;
    }
  }

  const handleDrawerConfirm = async (isContinue = false) => {
    bugDetailRef.value?.saveHandler(isContinue);
  };

  const initDefaultFields = async () => {
    await loadProjects();
    await getTemplateOptions();
  };

  watch(
    () => showBugDrawer.value,
    (val) => {
      if (val) {
        initDefaultFields();
      }
    }
  );
</script>

<style scoped></style>
