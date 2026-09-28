package com.example.marketing.account.web;

import com.example.marketing.account.dto.ChangePasswordRequest;
import com.example.marketing.account.dto.ConsumerMeVO;
import com.example.marketing.account.dto.ConsumerSessionVO;
import com.example.marketing.account.dto.LoginRequest;
import com.example.marketing.account.dto.RefreshRequest;
import com.example.marketing.account.dto.RegisterRequest;
import com.example.marketing.account.dto.TokenPairVO;
import com.example.marketing.account.service.ConsumerAuthService;
import com.example.marketing.common.api.Result;
import com.example.marketing.common.security.ConsumerPrincipal;
import com.example.marketing.common.security.ConsumerRequestIdentity;
import com.example.marketing.common.web.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 消费者认证入口。路径统一在 {@code /api/auth/**} 下，其中 login/register/refresh
 * 三个是<b>公开</b>的（网关 permit-list 放行），其余必须带 access token。
 *
 * <p>公开三件套"公开"的是免 access token，不是免限流 —— 限速在 service 内部、
 * BCrypt 之前那一层，挪到网关反而会让网关长出路径特例。</p>
 *
 * <p>需要登录的端点统一走 {@code identity.require(request)}：它验的是签名，
 * 所以"经网关"与"直连业务端口"两条路得到的是同一个判定，不存在某条路少验一步。</p>
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Validated
public class ConsumerAuthController {

    private final ConsumerAuthService authService;
    private final ConsumerRequestIdentity identity;

    @PostMapping("/register")
    public Result<TokenPairVO> register(@RequestBody @Valid RegisterRequest request, HttpServletRequest http) {
        return Result.ok(authService.register(request.identifier(), request.password(),
                request.nickname(), ClientIp.of(http), http.getHeader("User-Agent")));
    }

    @PostMapping("/login")
    public Result<TokenPairVO> login(@RequestBody @Valid LoginRequest request, HttpServletRequest http) {
        return Result.ok(authService.login(request.identifier(), request.password(),
                ClientIp.of(http), http.getHeader("User-Agent")));
    }

    /** 刷新不带 access token（它多半正是过期了才要刷），凭证在请求体里；仍受 IP 限速之外的重放保护 */
    @PostMapping("/refresh")
    public Result<TokenPairVO> refresh(@RequestBody @Valid RefreshRequest request) {
        return Result.ok(authService.refresh(request.refreshToken()));
    }

    @GetMapping("/me")
    public Result<ConsumerMeVO> me(HttpServletRequest http) {
        ConsumerPrincipal principal = identity.require(http);
        return Result.ok(authService.me(principal.uid()));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest http) {
        ConsumerPrincipal principal = identity.require(http);
        authService.logout(principal.uid(), principal.jti(), principal.username());
        return Result.ok();
    }

    @PutMapping("/password")
    public Result<Void> changePassword(@RequestBody @Valid ChangePasswordRequest request, HttpServletRequest http) {
        ConsumerPrincipal principal = identity.require(http);
        authService.changePassword(principal.uid(), request.oldPassword(), request.newPassword(),
                principal.username());
        return Result.ok();
    }

    @GetMapping("/sessions")
    public Result<List<ConsumerSessionVO>> sessions(HttpServletRequest http) {
        ConsumerPrincipal principal = identity.require(http);
        return Result.ok(authService.sessions(principal.uid(), principal.jti()));
    }
}
