package com.example.marketing.admin.audit;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.marketing.admin.infrastructure.entity.AdminAuditLogEntity;
import com.example.marketing.common.audit.AuditPayload;
import com.example.marketing.admin.infrastructure.mapper.AdminAuditLogMapper;
import com.example.marketing.common.api.PageQuery;
import com.example.marketing.common.api.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

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
        insert(toEntity(record), null);
    }

    /**
     * drain 落表入口：把业务侧投来的载荷写成一行。
     *
     * <p>这里做形状转换而不是让 drainer 自己拼 entity，是为了让 {@code cut()} 那套按列宽
     * 截断的口径只有一份 —— 两份不一致时，同一句摘要会一条存得下、一条把整行 INSERT 撑爆。</p>
     *
     * @param occurredAt <b>业务动作发生的时刻</b>，不是搬运时刻：admin 停十分钟再起来，
     *                   用搬运时刻会让整条审计时间线位移，而审计的唯一用处就是还原时间线
     */
    public void recordPayload(AuditPayload p, LocalDateTime occurredAt) {
        record(toRecord(p), occurredAt);
    }

    /**
     * drain 专用落表入口（H5，2026-09-29 架构审查）：同一套形状转换，但把落库成败
     * 交还调用方。HTTP 路径的 {@link #record(AuditRecord, LocalDateTime)} 仍是
     * "失败只 warn"——那里的动作已完成，不该让一条 INSERT 把它变 500；drain 路径
     * 的正确语义是"失败不 ACK、条目留在 PEL 等重试"，把失败咽掉等于静默销毁审计。
     */
    public boolean tryRecordPayload(AuditPayload p, LocalDateTime occurredAt) {
        return insert(toEntity(toRecord(p)), occurredAt);
    }

    private AuditRecord toRecord(AuditPayload p) {
        return new AuditRecord(p.actorId(), p.actorName(), p.role(), p.action(), p.resourceType(),
                p.resourceId(), p.method(), p.path(), p.requestSummary(), p.resultCode(),
                p.errorMsg(), p.ip(), p.costMs());
    }

    public void record(AuditRecord record, LocalDateTime occurredAt) {
        insert(toEntity(record), occurredAt);
    }

    private boolean insert(AdminAuditLogEntity entity, LocalDateTime occurredAt) {
        if (occurredAt != null) {
            entity.setCreateTime(occurredAt);
        }
        try {
            auditMapper.insert(entity);
            return true;
        } catch (RuntimeException e) {
            log.warn("[audit] 落库失败，动作结果不受影响 actor={}, action={}, resource={}#{}, "
                            + "code={}, msg={}, ip={}, cause={}",
                    entity.getActorName(), entity.getAction(), entity.getResourceType(),
                    entity.getResourceId(), entity.getResultCode(), entity.getErrorMsg(),
                    entity.getIp(), e.toString());
            return false;
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
