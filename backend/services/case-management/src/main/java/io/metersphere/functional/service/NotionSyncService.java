package io.metersphere.functional.service;

import io.metersphere.functional.domain.*;
import io.metersphere.functional.dto.NotionCaseRow;
import io.metersphere.functional.dto.NotionChildBlock;
import io.metersphere.functional.mapper.*;
import io.metersphere.functional.request.FunctionalCaseModuleCreateRequest;
import io.metersphere.project.mapper.ExtBaseProjectVersionMapper;
import io.metersphere.project.service.ProjectTemplateService;
import io.metersphere.sdk.constants.ApplicationNumScope;
import io.metersphere.sdk.constants.CustomFieldType;
import io.metersphere.sdk.constants.ExecStatus;
import io.metersphere.sdk.constants.ModuleConstants;
import io.metersphere.sdk.constants.TemplateScene;
import io.metersphere.sdk.util.LogUtils;
import io.metersphere.system.domain.User;
import io.metersphere.system.domain.UserExample;
import io.metersphere.system.dto.sdk.TemplateCustomFieldDTO;
import io.metersphere.system.dto.sdk.TemplateDTO;
import io.metersphere.system.mapper.UserMapper;
import io.metersphere.system.uid.IDGenerator;
import io.metersphere.system.uid.NumGenerator;
import io.metersphere.system.utils.ServiceUtils;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Notion <-> MeterSphere 双向同步核心服务
 *
 * 同步方向一（Notion → MS）：
 *   从配置的产品页面（web4.0/app4.0/desktop4.0）出发，自动遍历子页面和数据库，
 *   将所有用例同步到对应的 MS 项目中，目录结构自动创建。
 *
 * 同步方向二（MS → Notion）：
 *   通过 AOP 切面监听 MS 用例的更新/删除，异步推送回 Notion。
 */
@Service
public class NotionSyncService {

    /**
     * 防止循环同步：Notion→MS 同步期间设为 true，AOP 切面检测到则跳过反向推送
     */
    private static final ThreadLocal<Boolean> SYNCING_FROM_NOTION = ThreadLocal.withInitial(() -> false);

    @Resource
    private NotionService notionService;
    @Resource
    private NotionMsCaseMappingMapper mappingMapper;
    @Resource
    private NotionSyncConfigMapper syncConfigMapper;
    @Resource
    private FunctionalCaseMapper functionalCaseMapper;
    @Resource
    private FunctionalCaseBlobMapper functionalCaseBlobMapper;
    @Autowired
    private FunctionalCaseCustomFieldMapper customFieldMapper;
    @Resource
    private FunctionalCaseModuleMapper moduleMapper;
    @Resource
    private FunctionalCaseModuleService functionalCaseModuleService;
    @Resource
    private ExtBaseProjectVersionMapper extBaseProjectVersionMapper;
    @Resource
    private ProjectTemplateService projectTemplateService;
    @Resource
    private ExtFunctionalCaseMapper extFunctionalCaseMapper;
    @Resource
    private SqlSessionFactory sqlSessionFactory;
    @Resource
    private UserMapper userMapper;

    /** 用户名 → userId 缓存，每次同步任务共用，避免重复查库 */
    private final Map<String, String> userNameCache = new java.util.concurrent.ConcurrentHashMap<>();

    // =================== 公共 API ===================

    public static boolean isSyncingFromNotion() {
        return Boolean.TRUE.equals(SYNCING_FROM_NOTION.get());
    }

    /**
     * 手动触发：同步所有已启用的 Notion 配置（等同于定时任务立即执行一次）
     */
    public void syncAll(String userId) {
        List<NotionSyncConfig> configs = syncConfigMapper.findAllEnabled();
        for (NotionSyncConfig config : configs) {
            try {
                syncProductPage(config.getNotionPageId(), config.getProjectId(), userId);
                LogUtils.info("[Notion同步] 手动同步完成：Notion页面={} → 项目={}",
                        config.getNotionPageId(), config.getProjectId());
            } catch (Exception e) {
                LogUtils.error("[Notion同步] 手动同步失败：Notion页面=" + config.getNotionPageId(), e);
            }
        }
    }

    /**
     * 手动触发：只同步指定 MS 模块（及其子模块）下的用例所对应的 Notion 数据库。
     * 适用于"在某个目录下点击同步"场景，避免全量同步耗时。
     *
     * 实现逻辑：
     *   1. 递归收集 moduleId 下所有子模块 ID
     *   2. 查找这些模块下有 Notion 映射记录的用例，得到涉及的 notion_db_id 集合
     *   3. 对每个 notion_db_id，通过已有映射重建 module prefix（取所有用例路径的最长公共前缀）
     *   4. 只调用 syncDatabase() 同步这些 DB，不触碰其他 Notion DB
     */
    public void syncByModule(String moduleId, String projectId, String userId) {
        // 1. 递归收集所有子模块 ID
        Set<String> allModuleIds = new HashSet<>();
        allModuleIds.add(moduleId);
        collectSubModuleIds(moduleId, projectId, allModuleIds);

        // 2. 查找这些模块下的用例 ID
        List<String> caseIds = new ArrayList<>();
        for (String mid : allModuleIds) {
            FunctionalCaseExample ex = new FunctionalCaseExample();
            ex.createCriteria().andProjectIdEqualTo(projectId).andModuleIdEqualTo(mid).andDeletedEqualTo(false);
            functionalCaseMapper.selectByExample(ex).forEach(fc -> caseIds.add(fc.getId()));
        }
        if (caseIds.isEmpty()) {
            LogUtils.info("[Notion同步] 模块 {} 下无用例，跳过", moduleId);
            return;
        }

        // 3. 找到涉及的 notion_db_id
        Set<String> dbIds = new LinkedHashSet<>();
        for (String caseId : caseIds) {
            NotionMsCaseMapping m = mappingMapper.findByMsCaseId(caseId);
            if (m != null && StringUtils.isNotBlank(m.getNotionDbId())) {
                dbIds.add(m.getNotionDbId());
            }
        }
        if (dbIds.isEmpty()) {
            LogUtils.info("[Notion同步] 模块 {} 下无 Notion 映射记录，跳过", moduleId);
            return;
        }

        // 4. 获取模板
        TemplateDTO tpl = projectTemplateService.getDefaultTemplateDTO(projectId, TemplateScene.FUNCTIONAL.name());
        String templateId = tpl.getId();
        Map<String, TemplateCustomFieldDTO> customFieldsMap = getCustomFieldsMap(templateId, projectId);

        SYNCING_FROM_NOTION.set(true);
        try {
            for (String dbId : dbIds) {
                // 用该 DB 在项目中所有映射来计算 module prefix（取所有用例路径的 LCA）
                List<NotionMsCaseMapping> allDbMappings = mappingMapper.findByProjectAndDb(projectId, dbId);
                String modulePrefix = computeModulePrefixForDb(allDbMappings, projectId);
                LogUtils.info("[Notion同步] 模块触发同步: moduleId={} dbId={} prefix={}", moduleId, dbId, modulePrefix);
                try {
                    syncDatabase(dbId, projectId, templateId, modulePrefix, userId, customFieldsMap);
                } catch (Exception e) {
                    LogUtils.error("[Notion同步] 模块同步失败: db=" + dbId, e);
                }
            }
        } finally {
            SYNCING_FROM_NOTION.remove();
        }
    }

    /** 递归收集 parentId 下所有子模块 ID */
    private void collectSubModuleIds(String parentId, String projectId, Set<String> result) {
        FunctionalCaseModuleExample ex = new FunctionalCaseModuleExample();
        ex.createCriteria().andProjectIdEqualTo(projectId).andParentIdEqualTo(parentId);
        for (FunctionalCaseModule child : moduleMapper.selectByExample(ex)) {
            if (result.add(child.getId())) {
                collectSubModuleIds(child.getId(), projectId, result);
            }
        }
    }

    /**
     * 取该 DB 所有已映射用例的模块路径的最长公共前缀，即为 syncDatabase 时使用的 modulePrefix。
     * 例如：用例分布在 A/B/C/Teams 和 A/B/C/消费，LCA = A/B/C。
     */
    private String computeModulePrefixForDb(List<NotionMsCaseMapping> mappings, String projectId) {
        List<String> paths = new ArrayList<>();
        for (NotionMsCaseMapping m : mappings) {
            FunctionalCase fc = functionalCaseMapper.selectByPrimaryKey(m.getMsCaseId());
            if (fc != null && !fc.getDeleted()) {
                String path = buildModulePath(fc.getModuleId(), projectId);
                if (StringUtils.isNotBlank(path)) paths.add(path);
            }
        }
        return findLCA(paths);
    }

    /** 从叶子模块向上遍历，拼出完整路径，如 "AI原始用例数据/用户管理/登录" */
    private String buildModulePath(String moduleId, String projectId) {
        List<String> parts = new ArrayList<>();
        String current = moduleId;
        while (StringUtils.isNotBlank(current) && !"NONE".equals(current)) {
            FunctionalCaseModule module = moduleMapper.selectByPrimaryKey(current);
            if (module == null || !projectId.equals(module.getProjectId())) break;
            parts.add(0, module.getName());
            current = module.getParentId();
        }
        return String.join("/", parts);
    }

    /** 求字符串路径列表的最长公共前缀（按"/"分段） */
    private String findLCA(List<String> paths) {
        if (paths.isEmpty()) return "";
        String[] parts = paths.get(0).split("/");
        int commonLen = parts.length;
        for (String p : paths) {
            String[] pp = p.split("/");
            int len = 0;
            while (len < commonLen && len < pp.length && parts[len].equals(pp[len])) len++;
            commonLen = len;
        }
        return String.join("/", Arrays.copyOf(parts, commonLen));
    }

    /**
     * 从 Notion 产品页面（如 web4.0）往下遍历所有子页面和数据库，
     * 将全部用例同步到指定的 MS 项目。
     *
     * 层级结构：
     *   产品页面（web4.0）
     *   ├── 子页面（AI原始用例数据）
     *   │   ├── 数据库（用户管理）→ 模块路径 = "AI原始用例数据/用户管理"
     *   │   └── 数据库（录音管理）→ 模块路径 = "AI原始用例数据/录音管理"
     *   ├── 子页面（正式用例库）
     *   │   ├── 数据库（登录模块）→ 模块路径 = "正式用例库/登录模块"
     *   │   └── ...
     *   └── 数据库（直接子数据库）→ 模块路径 = "数据库名称"
     *
     * 每行用例的"模块路径"字段若有值，则在上述路径后追加一级子目录。
     *
     * @param notionPageId 产品页面 ID（web4.0/app4.0/desktop4.0）
     * @param msProjectId  对应的 MS 项目 ID
     * @param userId       操作人
     */
    @Transactional(rollbackFor = Exception.class)
    public void syncProductPage(String notionPageId, String msProjectId, String userId) {
        // 保存同步配置（幂等）
        upsertSyncConfig(notionPageId, msProjectId);

        // 推导默认模板 ID
        TemplateDTO defaultTemplate = projectTemplateService.getDefaultTemplateDTO(
                msProjectId, TemplateScene.FUNCTIONAL.name());
        String templateId = defaultTemplate.getId();
        Map<String, TemplateCustomFieldDTO> customFieldsMap = getCustomFieldsMap(templateId, msProjectId);

        SYNCING_FROM_NOTION.set(true);
        try {
            // 遍历产品页面的直接子块（深度2：产品页→子页面→数据库）
            List<NotionChildBlock> level1 = notionService.getChildBlocks(notionPageId);
            for (NotionChildBlock block1 : level1) {
                if ("child_database".equals(block1.getType())) {
                    // 直接子数据库（不常见，但支持）
                    syncDatabase(block1.getId(), msProjectId, templateId, block1.getTitle(),
                            userId, customFieldsMap);

                } else if ("child_page".equals(block1.getType())) {
                    // 子页面（AI原始用例数据、正式用例库 等）
                    String parentName = block1.getTitle(); // e.g. "AI原始用例数据"
                    List<NotionChildBlock> level2 = notionService.getChildBlocks(block1.getId());
                    for (NotionChildBlock block2 : level2) {
                        if ("child_database".equals(block2.getType())) {
                            // 子页面下的数据库（用户管理、录音管理 等）
                            String modulePath = parentName + "/" + block2.getTitle();
                            syncDatabase(block2.getId(), msProjectId, templateId, modulePath,
                                    userId, customFieldsMap);

                        } else if ("child_page".equals(block2.getType())) {
                            // 再深一层（正式用例库下还有子目录）
                            String subParentName = parentName + "/" + block2.getTitle();
                            List<NotionChildBlock> level3 = notionService.getChildBlocks(block2.getId());
                            for (NotionChildBlock block3 : level3) {
                                if ("child_database".equals(block3.getType())) {
                                    String modulePath = subParentName + "/" + block3.getTitle();
                                    syncDatabase(block3.getId(), msProjectId, templateId, modulePath,
                                            userId, customFieldsMap);
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            SYNCING_FROM_NOTION.remove();
        }
    }

    /**
     * 同步单个 Notion 数据库中所有行到 MS
     *
     * @param dbId           Notion 数据库 ID
     * @param msProjectId    MS 项目 ID
     * @param templateId     用例模板 ID
     * @param modulePrefix   父级模块路径（如 "AI原始用例数据/用户管理"）
     * @param userId         操作人
     * @param customFieldsMap 模板自定义字段
     */
    private void syncDatabase(String dbId, String msProjectId, String templateId,
                               String modulePrefix, String userId,
                               Map<String, TemplateCustomFieldDTO> customFieldsMap) {
        List<NotionCaseRow> rows = notionService.queryDatabase(dbId);
        if (rows.isEmpty()) return;

        // 获取该数据库在 MS 中已有的映射
        Map<String, NotionMsCaseMapping> existingMappings =
                mappingMapper.findByProjectAndDb(msProjectId, dbId)
                        .stream()
                        .collect(Collectors.toMap(NotionMsCaseMapping::getNotionPageId, m -> m));

        SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH);
        try {
            FunctionalCaseMapper batchCaseMapper = sqlSession.getMapper(FunctionalCaseMapper.class);
            FunctionalCaseBlobMapper batchBlobMapper = sqlSession.getMapper(FunctionalCaseBlobMapper.class);
            FunctionalCaseCustomFieldMapper batchCfMapper = sqlSession.getMapper(FunctionalCaseCustomFieldMapper.class);

            for (NotionCaseRow row : rows) {
                if (StringUtils.isBlank(row.getName())) continue;

                // 计算最终模块路径：父路径 + 行内"模块路径"字段
                String fullModulePath = StringUtils.isBlank(row.getModulePath())
                        ? modulePrefix
                        : modulePrefix + "/" + row.getModulePath();

                // 确保 MS 中模块存在（不存在则自动创建）
                String moduleId = ensureModulePath(fullModulePath, msProjectId, userId);

                NotionMsCaseMapping existing = existingMappings.get(row.getPageId());

                if (row.isArchived()) {
                    if (existing != null) {
                        softDeleteMsCase(existing.getMsCaseId(), userId);
                        mappingMapper.deleteByNotionPageId(row.getPageId());
                    }
                    continue;
                }

                if (existing == null) {
                    // 新建：创建人优先取 Notion "创建人" 字段，找不到则 fallback 到 userId
                    String creatorId = resolveUserId(row.getCreatorName(), userId);
                    String caseId = createCase(row, msProjectId, moduleId, templateId,
                            creatorId, batchCaseMapper, batchBlobMapper, batchCfMapper, customFieldsMap);
                    NotionMsCaseMapping mapping = new NotionMsCaseMapping();
                    mapping.setNotionPageId(row.getPageId());
                    mapping.setMsCaseId(caseId);
                    mapping.setProjectId(msProjectId);
                    mapping.setNotionDbId(dbId);
                    mapping.setNotionLastEdited(row.getLastEditedTime());
                    mapping.setMsLastUpdated(System.currentTimeMillis());
                    mappingMapper.insert(mapping);

                } else if (!StringUtils.equals(row.getLastEditedTime(), existing.getNotionLastEdited())) {
                    // 有变更，更新：更新人优先取 Notion "创建人" 字段
                    String updatorId = resolveUserId(row.getCreatorName(), userId);
                    updateCase(existing.getMsCaseId(), row, moduleId, updatorId, customFieldsMap);
                    existing.setNotionLastEdited(row.getLastEditedTime());
                    existing.setMsLastUpdated(System.currentTimeMillis());
                    mappingMapper.updateSyncTime(existing);
                }
                // lastEditedTime 不变 → 跳过
            }
            sqlSession.flushStatements();
        } finally {
            SqlSessionUtils.closeSqlSession(sqlSession, sqlSessionFactory);
        }

        LogUtils.info("[Notion同步] 数据库 {} 模块路径={} 同步完成，共 {} 行", dbId, modulePrefix, rows.size());
    }

    // =================== MS → Notion（AOP 触发）===================

    @Async
    public void asyncPushCaseToNotion(String msCaseId) {
        NotionMsCaseMapping mapping = mappingMapper.findByMsCaseId(msCaseId);
        if (mapping == null) return;

        FunctionalCase msCase = functionalCaseMapper.selectByPrimaryKey(msCaseId);
        FunctionalCaseBlob blob = functionalCaseBlobMapper.selectByPrimaryKey(msCaseId);
        if (msCase == null) return;

        String priority = resolvePriority(msCaseId);
        notionService.updateNotionPage(mapping.getNotionPageId(), msCase, blob, priority);

        mapping.setMsLastUpdated(msCase.getUpdateTime());
        mappingMapper.updateSyncTime(mapping);
    }

    @Async
    public void asyncArchiveNotionCase(String msCaseId) {
        NotionMsCaseMapping mapping = mappingMapper.findByMsCaseId(msCaseId);
        if (mapping == null) return;
        notionService.archiveNotionPage(mapping.getNotionPageId());
        mappingMapper.deleteByMsCaseId(msCaseId);
    }

    // =================== 模块路径处理 ===================

    /**
     * 确保 MS 中存在指定路径的模块，不存在则逐级创建。
     *
     * @param path      模块路径，用 "/" 分隔，如 "AI原始用例数据/用户管理/登录"
     * @param projectId MS 项目 ID
     * @param userId    创建人
     * @return 叶子模块的 ID
     */
    private String ensureModulePath(String path, String projectId, String userId) {
        if (StringUtils.isBlank(path)) {
            return ModuleConstants.DEFAULT_NODE_ID;
        }

        String[] segments = path.split("/");
        String parentId = ModuleConstants.ROOT_NODE_PARENT_ID;

        for (String segment : segments) {
            segment = segment.trim();
            if (StringUtils.isBlank(segment)) continue;

            // 查找当前层级是否已存在同名模块
            FunctionalCaseModuleExample example = new FunctionalCaseModuleExample();
            example.createCriteria()
                    .andProjectIdEqualTo(projectId)
                    .andParentIdEqualTo(parentId)
                    .andNameEqualTo(segment);
            List<FunctionalCaseModule> existing = moduleMapper.selectByExample(example);

            if (!existing.isEmpty()) {
                parentId = existing.get(0).getId();
            } else {
                // 不存在则创建
                FunctionalCaseModuleCreateRequest req = new FunctionalCaseModuleCreateRequest();
                req.setName(segment);
                req.setParentId(parentId);
                req.setProjectId(projectId);
                parentId = functionalCaseModuleService.add(req, userId);
            }
        }

        return parentId;
    }

    // =================== 用例 CRUD ===================

    private String createCase(NotionCaseRow row, String projectId, String moduleId, String templateId,
                               String userId,
                               FunctionalCaseMapper caseMapper,
                               FunctionalCaseBlobMapper blobMapper,
                               FunctionalCaseCustomFieldMapper cfMapper,
                               Map<String, TemplateCustomFieldDTO> customFieldsMap) {
        String id = IDGenerator.nextStr();
        FunctionalCase fc = new FunctionalCase();
        fc.setId(id);
        fc.setNum(NumGenerator.nextNum(projectId, ApplicationNumScope.CASE_MANAGEMENT));
        fc.setModuleId(moduleId);
        fc.setProjectId(projectId);
        fc.setTemplateId(templateId);
        fc.setName(StringUtils.left(row.getName(), 255));
        fc.setReviewStatus("UN_REVIEWED");
        fc.setCaseEditType("STEP");
        fc.setPos(getNextPos(projectId));
        fc.setTags(row.getTags());
        fc.setVersionId(extBaseProjectVersionMapper.getDefaultVersion(projectId));
        fc.setRefId(id);
        fc.setLastExecuteResult(ExecStatus.PENDING.name());
        fc.setDeleted(false);
        fc.setAiCreate(true);
        fc.setPublicCase(false);
        fc.setLatest(true);
        fc.setCreateUser(userId);
        fc.setUpdateUser(userId);
        fc.setCreateTime(System.currentTimeMillis());
        fc.setUpdateTime(System.currentTimeMillis());
        caseMapper.insert(fc);

        FunctionalCaseBlob blob = new FunctionalCaseBlob();
        blob.setId(id);
        blob.setPrerequisite(StringUtils.defaultString(row.getPrerequisite()).getBytes(StandardCharsets.UTF_8));
        blob.setSteps(toStepJson(row.getSteps(), row.getExpectedResult()).getBytes(StandardCharsets.UTF_8));
        blob.setExpectedResult(new byte[0]);
        blob.setDescription(StringUtils.defaultString(row.getDescription()).getBytes(StandardCharsets.UTF_8));
        blobMapper.insert(blob);

        saveCustomFields(userId, id, row.getPriority(), customFieldsMap, cfMapper);
        return id;
    }

    private void updateCase(String caseId, NotionCaseRow row, String moduleId, String userId,
                             Map<String, TemplateCustomFieldDTO> customFieldsMap) {
        FunctionalCase fc = functionalCaseMapper.selectByPrimaryKey(caseId);
        if (fc == null) return;

        fc.setName(StringUtils.left(row.getName(), 255));
        fc.setModuleId(moduleId);
        // 不覆盖用户在 MS 手动标注的标签，Notion 同步只负责内容字段
        fc.setCreateUser(userId);
        fc.setUpdateUser(userId);
        fc.setUpdateTime(System.currentTimeMillis());
        functionalCaseMapper.updateByPrimaryKeySelective(fc);

        FunctionalCaseBlob blob = new FunctionalCaseBlob();
        blob.setId(caseId);
        blob.setPrerequisite(StringUtils.defaultString(row.getPrerequisite()).getBytes(StandardCharsets.UTF_8));
        blob.setSteps(toStepJson(row.getSteps(), row.getExpectedResult()).getBytes(StandardCharsets.UTF_8));
        blob.setExpectedResult(new byte[0]);
        blob.setDescription(StringUtils.defaultString(row.getDescription()).getBytes(StandardCharsets.UTF_8));
        functionalCaseBlobMapper.updateByPrimaryKeySelective(blob);

        if (StringUtils.isNotBlank(row.getPriority())) {
            updatePriorityField(caseId, row.getPriority(), customFieldsMap);
        }
    }

    private void softDeleteMsCase(String caseId, String userId) {
        FunctionalCase fc = functionalCaseMapper.selectByPrimaryKey(caseId);
        if (fc == null) return;
        fc.setDeleted(true);
        fc.setDeleteUser(userId);
        fc.setDeleteTime(System.currentTimeMillis());
        functionalCaseMapper.updateByPrimaryKeySelective(fc);
    }

    // =================== 辅助方法 ===================

    /**
     * 将 Notion 测试步骤文本（多行，每行一个步骤）和预期结果文本转换为 MS STEP 格式 JSON
     * 格式：[{"num":1,"desc":"步骤内容","result":"预期结果"}]
     *
     * 如果步骤文本包含多行，每行作为一个步骤；预期结果文本整体放到最后一步的 result，
     * 或者如果结果文本也是多行，则逐行对应。
     */
    private String toStepJson(String stepsText, String expectedResultText) {
        // If stepsText is already a MS-format step JSON array, use it directly
        if (StringUtils.isNotBlank(stepsText)) {
            String trimmed = stepsText.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.contains("\"desc\"")) {
                return trimmed;
            }
        }
        List<String> stepLines = splitLines(stepsText);
        List<String> resultLines = splitLines(expectedResultText);

        if (stepLines.isEmpty() && resultLines.isEmpty()) {
            return "[]";
        }

        // 确保两个列表长度一致（短的补空字符串）
        int size = Math.max(stepLines.size(), resultLines.size());
        while (stepLines.size() < size) stepLines.add("");
        while (resultLines.size() < size) resultLines.add("");

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"num\":").append(i + 1)
              .append(",\"desc\":").append(jsonString(stepLines.get(i)))
              .append(",\"result\":").append(jsonString(resultLines.get(i)))
              .append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private List<String> splitLines(String text) {
        if (StringUtils.isBlank(text)) return new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            // 去掉行首的序号（如 "1. "、"2. "）
            trimmed = trimmed.replaceAll("^\\d+\\.\\s*", "");
            if (StringUtils.isNotBlank(trimmed)) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    private String jsonString(String s) {
        if (s == null) return "\"\"";
        // 转义 JSON 特殊字符
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                       .replace("\n", "\\n").replace("\r", "\\r")
                       .replace("\t", "\\t") + "\"";
    }

    /**
     * 按用户名查找 MS 用户 ID；未找到时 fallback 到 defaultUserId
     * 结果缓存在 userNameCache 中，同一次同步任务内不重复查库
     */
    private String resolveUserId(String creatorName, String defaultUserId) {
        if (StringUtils.isBlank(creatorName)) return defaultUserId;
        return userNameCache.computeIfAbsent(creatorName, name -> {
            // 1. 精确匹配
            UserExample exact = new UserExample();
            exact.createCriteria().andNameEqualTo(name).andDeletedEqualTo(false);
            List<User> users = userMapper.selectByExample(exact);
            if (!users.isEmpty()) return users.get(0).getId();
            // 2. 模糊匹配：Notion 用简短名（如 "Sienna"、"容晓康"），MS 存全名（如 "Sienna(董俏丽)"、"Conner(容晓康)"）
            UserExample fuzzy = new UserExample();
            fuzzy.createCriteria().andNameLike("%" + name + "%").andDeletedEqualTo(false);
            List<User> fuzzyUsers = userMapper.selectByExample(fuzzy);
            return fuzzyUsers.size() == 1 ? fuzzyUsers.get(0).getId() : defaultUserId;
        });
    }

    private void upsertSyncConfig(String notionPageId, String msProjectId) {
        NotionSyncConfig existing = syncConfigMapper.findByPageAndProject(notionPageId, msProjectId);
        long now = System.currentTimeMillis();
        if (existing == null) {
            NotionSyncConfig config = new NotionSyncConfig();
            config.setId(IDGenerator.nextStr());
            config.setNotionPageId(notionPageId);
            config.setProjectId(msProjectId);
            config.setEnabled(true);
            config.setCreateTime(now);
            config.setUpdateTime(now);
            syncConfigMapper.insert(config);
        } else if (!Boolean.TRUE.equals(existing.getEnabled())) {
            existing.setEnabled(true);
            existing.setUpdateTime(now);
            syncConfigMapper.update(existing);
        }
    }

    private void saveCustomFields(String userId, String caseId, String priority,
                                   Map<String, TemplateCustomFieldDTO> customFieldsMap,
                                   FunctionalCaseCustomFieldMapper cfMapper) {
        customFieldsMap.forEach((fieldName, fieldDef) -> {
            FunctionalCaseCustomField cf = new FunctionalCaseCustomField();
            cf.setCaseId(caseId);
            cf.setFieldId(fieldDef.getFieldId());

            if (StringUtils.equalsIgnoreCase(fieldDef.getInternalFieldKey(), "functional_priority")) {
                cf.setValue(StringUtils.defaultIfBlank(priority, "P0"));
            } else if (StringUtils.equalsIgnoreCase(fieldDef.getType(), CustomFieldType.MEMBER.name())
                    && fieldDef.getDefaultValue() != null
                    && fieldDef.getDefaultValue().toString().contains("CREATE_USER")) {
                cf.setValue(userId);
            } else {
                cf.setValue(fieldDef.getDefaultValue() == null ? "" : fieldDef.getDefaultValue().toString());
            }
            cfMapper.insertSelective(cf);
        });
    }

    private void updatePriorityField(String caseId, String priority,
                                      Map<String, TemplateCustomFieldDTO> customFieldsMap) {
        customFieldsMap.values().stream()
                .filter(f -> StringUtils.equalsIgnoreCase(f.getInternalFieldKey(), "functional_priority"))
                .findFirst()
                .ifPresent(f -> {
                    FunctionalCaseCustomFieldExample example = new FunctionalCaseCustomFieldExample();
                    example.createCriteria()
                            .andCaseIdEqualTo(caseId)
                            .andFieldIdEqualTo(f.getFieldId());
                    FunctionalCaseCustomField cf = new FunctionalCaseCustomField();
                    cf.setValue(priority);
                    customFieldMapper.updateByExampleSelective(cf, example);
                });
    }

    private String resolvePriority(String caseId) {
        FunctionalCaseCustomFieldExample example = new FunctionalCaseCustomFieldExample();
        example.createCriteria().andCaseIdEqualTo(caseId);
        return customFieldMapper.selectByExample(example).stream()
                .filter(f -> f.getValue() != null && f.getValue().matches("P[0-3]"))
                .map(FunctionalCaseCustomField::getValue)
                .findFirst().orElse("");
    }

    private Map<String, TemplateCustomFieldDTO> getCustomFieldsMap(String templateId, String projectId) {
        TemplateDTO templateDTO = projectTemplateService.getTemplateDTOById(templateId, projectId, TemplateScene.FUNCTIONAL.name());
        List<TemplateCustomFieldDTO> fields = Optional.ofNullable(templateDTO.getCustomFields()).orElse(new ArrayList<>());
        return fields.stream().collect(Collectors.toMap(TemplateCustomFieldDTO::getFieldName, f -> f));
    }

    private Long getNextPos(String projectId) {
        Long pos = extFunctionalCaseMapper.getPos(projectId);
        return (pos == null ? 0 : pos) + ServiceUtils.POS_STEP;
    }
}
