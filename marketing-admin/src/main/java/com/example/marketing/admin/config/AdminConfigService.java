package com.example.marketing.admin.config;

import com.example.marketing.admin.audit.AuditRecord;
import com.example.marketing.admin.audit.AuditService;
import com.example.marketing.admin.dto.ConfigEntryView;
import com.example.marketing.admin.dto.ConfigFormValueView;
import com.example.marketing.admin.dto.ConfigOrphanView;
import com.example.marketing.admin.dto.ConfigOverviewView;
import com.example.marketing.admin.dto.ConfigSetRequest;
import com.example.marketing.common.security.AdminPrincipal;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.config.ConfigDefinition;
import com.example.marketing.common.config.ConfigForm;
import com.example.marketing.common.config.ConfigKeys;
import com.example.marketing.common.config.ConfigMerge;
import com.example.marketing.common.config.ConfigValues;
import com.example.marketing.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置写路径：真值的唯一写入者，也是快照的唯一广播者。
 *
 * <p>三条纪律：① 只接受代码声明过的键（"未声明"就拒，而不是写一条没人消费的行）；
 * ② 顺序固定 INCR → 写行 → 发快照 → 发版本，后两步任一失败一律 41009 显式暴露并留审计；
 * ③ 恢复出厂 = 删行，不写回原值。</p>
 */
@Slf4j
@Service
public class AdminConfigService {

    private final AdminConfigStore store;
    private final ConfigSnapshotPublisher publisher;
    private final ConfigSchemaReader schemaReader;
    private final ConfigValues values;
    private final AuditService auditService;
    private final String ownForm;

    public AdminConfigService(AdminConfigStore store, ConfigSnapshotPublisher publisher,
                              ConfigSchemaReader schemaReader, ConfigValues values,
                              AuditService auditService,
                              @Value("${marketing.config.form:${DEPLOY_FORM:}}") String form) {
        this.store = store;
        this.publisher = publisher;
        this.schemaReader = schemaReader;
        this.values = values;
        this.auditService = auditService;
        this.ownForm = ConfigForm.resolve(form);
    }

    public ConfigOverviewView overview() {
        List<ConfigMerge.Row> rows = store.rows();
        Map<String, String> merged = ConfigMerge.merge(ownForm, rows);
        Set<String> ownOverridden = new HashSet<>();
        for (ConfigMerge.Row row : rows) {
            if (ConfigForm.resolve(row.form()).equals(ownForm)) {
                ownOverridden.add(row.cfgKey());
            }
        }
        Map<String, String> owners = schemaReader.serviceByKey();
        List<ConfigEntryView> entries = new ArrayList<>();
        Set<String> declaredKeys = new HashSet<>();
        for (ConfigDefinition d : schemaReader.declared()) {
            declaredKeys.add(d.key());
            String effective = merged.get(d.key());
            String source = effective == null ? "DEFAULT"
                    : ownOverridden.contains(d.key()) ? "FORM" : "GLOBAL";
            entries.add(new ConfigEntryView(d.key(), owners.getOrDefault(d.key(), ""),
                    d.type().name(), d.min(), d.max(), d.defaultValue(), d.description(),
                    effective == null ? d.defaultValue() : effective, source,
                    rowsOf(store.detail(), d.key())));
        }
        List<ConfigOrphanView> orphans = new ArrayList<>();
        for (AdminConfigStore.Row r : store.detail()) {
            if (!declaredKeys.contains(r.cfgKey())) {
                orphans.add(new ConfigOrphanView(r.form(), r.cfgKey(), r.value(), r.version(), r.updatedBy()));
            }
        }
        return new ConfigOverviewView(ownForm, values.appliedVersion(), entries, orphans,
                schemaReader.unreported(), values.degradedKeys());
    }

    public ConfigEntryView set(AdminPrincipal actor, ConfigSetRequest request, String ip) {
        String key = trimmed(request.cfgKey());
        String form = requireForm(request.form());
        String value = trimmed(request.value());
        ConfigDefinition def = schemaReader.find(key).orElseThrow(() -> BizException.of(ErrorCode.BAD_REQUEST,
                "参数 " + key + " 未被任何在线服务声明，不能改（改了也没有人消费）"));
        if (!def.accepts(value)) {
            throw BizException.of(ErrorCode.BAD_REQUEST,
                    "参数 " + key + " 的值非法: " + value + "，类型 " + def.type()
                            + "，允许区间 [" + def.min() + ", " + def.max() + "]");
        }
        String before = store.findValue(key, form);
        long seq = publisher.nextSequence();
        if (!store.upsertCas(key, form, value, seq, actor.username(), trimmed(request.remark()),
                request.expectedVersion())) {
            throw BizException.of(ErrorCode.CONFIG_VERSION_CONFLICT,
                    "参数 " + key + " @ " + form + " 已被他人修改（version 冲突），请刷新后重试");
        }
        broadcastOrThrow(actor, "config.set", key, form, before, value, ip, seq);
        log.info("[admin] 配置写入 key={}, form={}, {} -> {}, seq={}, actor={}",
                key, form, before, value, seq, actor.username());
        return entryView(key, def);
    }

    public void delete(AdminPrincipal actor, String key, String form, String ip) {
        String k = trimmed(key);
        String f = requireForm(form);
        String before = store.findValue(k, f);
        if (store.delete(k, f) == 0) {
            throw BizException.of(ErrorCode.NOT_FOUND, "该参数在这个形态上没有覆盖（本来就是出厂值）");
        }
        long seq = publisher.nextSequence();
        broadcastOrThrow(actor, "config.delete", k, f, before, "", ip, seq);
        log.info("[admin] 配置恢复出厂 key={}, form={}, 原值={}, seq={}, actor={}",
                k, f, before, seq, actor.username());
    }

    /** 幂等：只按 DB 现状重发快照，用于修"已落库未广播"的那个窗口 */
    public long rebroadcast(AdminPrincipal actor, String ip) {
        long seq = publisher.nextSequence();
        try {
            publisher.publishAll(seq);
        } catch (BizException e) {
            audit(actor, "config.rebroadcast", "", "", "", "", seq, e.getCode(), e.getMessage(), ip);
            throw e;
        }
        audit(actor, "config.rebroadcast", "", "", "", "", seq, 0, "", ip);
        log.info("[admin] 配置重新广播 seq={}, actor={}", seq, actor.username());
        return seq;
    }

    /** 后两步失败必须显式暴露：静默不一致是本项目最贵的一类 bug，被拒的动作同样要留痕 */
    private void broadcastOrThrow(AdminPrincipal actor, String action, String key, String form,
                                  String before, String after, String ip, long seq) {
        try {
            publisher.publishAll(seq);
        } catch (BizException e) {
            audit(actor, action, key, form, before, after, seq, e.getCode(), e.getMessage(), ip);
            throw e;
        }
        audit(actor, action, key, form, before, after, seq, 0, "", ip);
    }

    private ConfigEntryView entryView(String key, ConfigDefinition d) {
        Map<String, String> merged = ConfigMerge.merge(ownForm, store.rows());
        String effective = merged.get(key);
        return new ConfigEntryView(d.key(), schemaReader.serviceByKey().getOrDefault(d.key(), ""),
                d.type().name(), d.min(), d.max(), d.defaultValue(), d.description(),
                effective == null ? d.defaultValue() : effective,
                effective == null ? "DEFAULT" : "FORM", rowsOf(store.detail(), d.key()));
    }

    private static List<ConfigFormValueView> rowsOf(List<AdminConfigStore.Row> detail, String key) {
        List<ConfigFormValueView> out = new ArrayList<>();
        for (AdminConfigStore.Row r : detail) {
            if (r.cfgKey().equals(key)) {
                out.add(new ConfigFormValueView(r.form(), r.value(), r.version(), r.updatedBy(), r.remark()));
            }
        }
        return out;
    }

    private static String trimmed(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /** 非法 form 必须拒而不是"退回 GLOBAL"：写进一个没人读的形态就是幽灵配置 */
    private static String requireForm(String raw) {
        String form = trimmed(raw).toUpperCase();
        if (!ConfigKeys.FORMS.contains(form)) {
            throw BizException.of(ErrorCode.BAD_REQUEST,
                    "form 必须是 " + ConfigKeys.FORMS + " 之一，收到: " + raw);
        }
        return form;
    }

    private void audit(AdminPrincipal actor, String action, String key, String form,
                       String before, String after, long seq, int code, String err, String ip) {
        auditService.record(new AuditRecord(actor.uid(), actor.username(), actor.role(), action,
                "admin_config", key + ":" + form, "PUT", "/api/admin/config",
                "from=" + before + ", to=" + after + ", form=" + form + ", seq=" + seq,
                code, err, ip, 0));
    }
}
