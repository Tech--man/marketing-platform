package com.example.marketing.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.marketing.admin.dto.AdminUserView;
import com.example.marketing.admin.security.AdminPrincipal;
import com.example.marketing.admin.infrastructure.entity.AdminUserEntity;
import com.example.marketing.admin.infrastructure.mapper.AdminUserMapper;
import com.example.marketing.admin.security.LoginPolicy;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.api.PageQuery;
import com.example.marketing.common.api.PageResult;
import com.example.marketing.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 账号列表与启停。没有"新建账号"接口 —— ①②只交付账号体系的地基，
 * 开通账号目前走 SQL（DDL 里已有种子），③再做业务面。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final AdminUserMapper userMapper;
    private final AdminSessionService sessionService;
    private final com.example.marketing.admin.audit.AuditService auditService;

    public PageResult<AdminUserView> page(PageQuery query, String keyword) {
        LocalDateTime now = LocalDateTime.now();
        var wrapper = Wrappers.<AdminUserEntity>lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), AdminUserEntity::getUsername,
                        keyword == null ? "" : keyword.trim())
                .orderByAsc(AdminUserEntity::getId);
        Page<AdminUserEntity> page = userMapper.selectPage(new Page<>(query.getPage(), query.getSize()), wrapper);
        return PageResult.of(page.getTotal(), query.getPage(), query.getSize(),
                page.getRecords().stream()
                        .map(u -> AdminUserView.of(u, u.getLockUntil() != null && u.getLockUntil().isAfter(now)))
                        .toList());
    }

    public AdminUserView get(long id) {
        AdminUserEntity user = require(id);
        return AdminUserView.of(user, user.getLockUntil() != null && user.getLockUntil().isAfter(LocalDateTime.now()));
    }

    /**
     * 启停账号。停用必须同时抬 pwd_version 并作废全部会话：
     * 只改 status 列的话，已签发的 token 在剩余寿命内照样能过网关 —— 停用形同没停用。
     */
    public AdminUserView setStatus(AdminPrincipal actor, long id, String status) {
        AdminUserEntity user = require(id);
        boolean disable = LoginPolicy.STATUS_DISABLED.equals(status);
        if (!disable && !LoginPolicy.STATUS_ACTIVE.equals(status)) {
            throw BizException.of(ErrorCode.BAD_REQUEST,
                    "status 只接受 " + LoginPolicy.STATUS_ACTIVE + "/" + LoginPolicy.STATUS_DISABLED);
        }
        if (user.getId() == null) {
            throw new IllegalStateException("账号缺少主键: " + id);
        }
        userMapper.update(null, Wrappers.<AdminUserEntity>lambdaUpdate()
                .eq(AdminUserEntity::getId, user.getId())
                .set(AdminUserEntity::getStatus, status)
                .set(disable, AdminUserEntity::getPwdVersion, user.getPwdVersion() + 1)
                .set(!disable, AdminUserEntity::getFailCount, 0)
                .set(!disable, AdminUserEntity::getLockUntil, null));
        if (disable) {
            sessionService.revokeAll(user.getId(), "DISABLED");
        }
        auditService.record(com.example.marketing.admin.audit.AuditRecord.ofAction(
                actor.uid(), actor.username(), actor.role(),
                disable ? "user.disable" : "user.enable", "user", String.valueOf(user.getId()), ""));
        log.info("[admin] 账号状态变更 actor={}, username={}, status={}",
                actor.username(), user.getUsername(), status);
        return get(id);
    }

    private AdminUserEntity require(long id) {
        AdminUserEntity user = userMapper.selectById(id);
        if (user == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "账号不存在: " + id);
        }
        return user;
    }
}
