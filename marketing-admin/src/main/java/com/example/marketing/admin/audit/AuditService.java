package com.example.marketing.admin.audit;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.marketing.admin.infrastructure.entity.AdminAuditLogEntity;
import com.example.marketing.admin.infrastructure.mapper.AdminAuditLogMapper;
import com.example.marketing.common.api.PageQuery;
import com.example.marketing.common.api.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 审计落库与查询。
 *
 * <p>写失败只 warn 不抛：能走到这里的都是"动作已经做完了"的时刻，
 * 让一条 INSERT 的失败把一次成功的重预热变成 500，等于用可观测性换正确性。
 * 代价是审计可能缺行 —— 所以缺行本身要能在日志里查到（warn 里带全量字段）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService implements AuditSink {

    private final AdminAuditLogMapper auditMapper;

    @Override
    public void record(AuditRecord record) {
        try {
            auditMapper.insert(toEntity(record));
        } catch (RuntimeException e) {
            log.warn("[audit] 落库失败，动作结果不受影响 actor={}, action={}, resource={}#{}, "
                            + "code={}, msg={}, ip={}, cause={}",
                    record.actorName(), record.action(), record.resourceType(), record.resourceId(),
                    record.resultCode(), record.errorMsg(), record.ip(), e.toString());
        }
    }

    public PageResult<AdminAuditLogEntity> query(PageQuery query, Long actorId, String action,
                                                 String resourceType, String resourceId) {
        var wrapper = Wrappers.<AdminAuditLogEntity>lambdaQuery()
                .eq(actorId != null, AdminAuditLogEntity::getActorId, actorId)
                .eq(hasText(action), AdminAuditLogEntity::getAction, action)
                .eq(hasText(resourceType), AdminAuditLogEntity::getResourceType, resourceType)
                .eq(hasText(resourceId), AdminAuditLogEntity::getResourceId, resourceId)
                .orderByDesc(AdminAuditLogEntity::getId);
        Page<AdminAuditLogEntity> page = auditMapper.selectPage(new Page<>(query.getPage(), query.getSize()), wrapper);
        return PageResult.of(page.getTotal(), query.getPage(), query.getSize(), page.getRecords());
    }

    private static AdminAuditLogEntity toEntity(AuditRecord record) {
        AdminAuditLogEntity entity = new AdminAuditLogEntity();
        entity.setActorId(record.actorId());
        entity.setActorName(cut(record.actorName(), 64));
        entity.setRole(cut(record.role(), 32));
        entity.setAction(cut(record.action(), 64));
        entity.setResourceType(cut(record.resourceType(), 64));
        entity.setResourceId(cut(record.resourceId(), 128));
        entity.setMethod(cut(record.method(), 16));
        entity.setPath(cut(record.path(), 255));
        entity.setRequestSummary(cut(record.requestSummary(), 512));
        entity.setResultCode(record.resultCode());
        entity.setErrorMsg(cut(record.errorMsg(), 512));
        entity.setIp(cut(record.ip(), 64));
        entity.setCostMs(record.costMs());
        return entity;
    }

    /** 列宽截断在这里做，而不是指望 DB 报错：VARCHAR 溢出会让整条 INSERT 失败，审计反而全丢 */
    private static String cut(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
