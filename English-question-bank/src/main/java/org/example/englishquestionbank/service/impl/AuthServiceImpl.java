package org.example.englishquestionbank.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.englishquestionbank.dto.LoginResult;
import org.example.englishquestionbank.entity.SysUser;
import org.example.englishquestionbank.entity.UserToken;
import org.example.englishquestionbank.exception.UnauthenticatedException;
import org.example.englishquestionbank.mapper.UserTokenMapper;
import org.example.englishquestionbank.service.AuthService;
import org.example.englishquestionbank.service.SysUserService;
import org.example.englishquestionbank.support.PasswordHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * {@link AuthService} 的实现。
 *
 * <p><b>事务</b>：只有 {@link #login} 需要 —— 它要「校验用户」+「写一条令牌」，
 * 是跨两次数据库访问的一个逻辑单元。其余方法都是单表单条操作。
 *
 * <p>⚠️ <b>自调用陷阱</b>：{@link #revokeAll} 带 {@code @Transactional}，
 * 不能被本类内部用 {@code this.revokeAll()} 调用（不走代理，事务失效）。
 * 目前没有内部调用，将来若要在 {@code login} 里「登录时踢掉旧设备」，
 * 必须改成注入自身代理或把逻辑内联。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /** 令牌有效期。7 天是「开发期够用、又不至于永不过期」的折中。 */
    private static final Duration TOKEN_TTL = Duration.ofDays(7);

    /**
     * 令牌随机字节数。32 字节 = 256 位，暴力枚举不可行。
     *
     * <p>不要为了「短一点好看」而调小：令牌是系统里唯一凭它就能冒充用户的东西。
     */
    private static final int TOKEN_BYTES = 32;

    /** 日志里只展示令牌的前若干位，避免把可用凭据写进日志文件。 */
    private static final int TOKEN_LOG_PREFIX = 6;

    /**
     * {@code last_used_at} 的写入节流窗口。
     *
     * <p>不节流的话，每个 API 请求都会多一次 UPDATE —— 而鉴权是「读」动作，
     * 让读放大成写是得不偿失的。5 分钟的精度对「排查异常登录」完全够用。
     */
    private static final Duration LAST_USED_THROTTLE = Duration.ofMinutes(5);

    /**
     * 统一的凭据错误消息。
     *
     * <p><b>为什么「用户名不存在」和「密码错误」共用一句话</b>：
     * 分开提示等于把「这个用户名是否已注册」免费告诉攻击者，
     * 可用来批量枚举有效账号。要在日志里看真实原因，服务端已经分别记了 debug 日志。
     */
    private static final String BAD_CREDENTIALS = "用户名或密码错误";

    private final SysUserService sysUserService;
    private final UserTokenMapper userTokenMapper;
    private final PasswordHasher passwordHasher;

    /**
     * 密码学安全的随机源。
     *
     * <p><b>不能用 {@code java.util.Random}</b>：它是线性同余发生器，
     * 观察到若干输出就能推算出后续序列 —— 用它生成令牌等于令牌可预测。
     */
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginResult login(String username, String password) {
        // ---- 1. 入参校验 ----
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }

        // ---- 2. 查用户 ----
        SysUser user = sysUserService.findByUsername(username);

        // ---- 3. 校验密码 ----
        // 用户不存在 / 未设密码时，也走一次等量的 BCrypt 计算再失败：
        // 否则「用户名不存在」会立刻返回，响应时间明显短于「密码错」，
        // 攻击者据此可以枚举出哪些用户名真实存在（时序攻击）。
        if (user == null) {
            passwordHasher.burnTimeAndFail(password);
            log.debug("登录失败：用户名不存在 username={}", username);
            throw new UnauthenticatedException(BAD_CREDENTIALS);
        }
        if (!passwordHasher.matches(password, user.getPasswordHash())) {
            log.debug("登录失败：密码不匹配 userId={}", user.getId());
            throw new UnauthenticatedException(BAD_CREDENTIALS);
        }

        // ---- 4. 校验账号状态 ----
        // 刻意放在密码校验【之后】：若先查状态，攻击者无需正确密码就能
        // 通过「账号已被禁用」这条消息判断该用户名存在。
        // 放在之后则安全 —— 能走到这里的人本就已持有正确凭据，告诉他账号被禁用是应该的。
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            log.debug("登录失败：账号被禁用 userId={} status={}", user.getId(), user.getStatus());
            throw new UnauthenticatedException("账号已被禁用，请联系管理员");
        }

        // ---- 5. 签发令牌 ----
        String rawToken = generateToken();
        LocalDateTime expiresAt = LocalDateTime.now().plus(TOKEN_TTL);

        UserToken token = new UserToken();
        token.setUserId(user.getId());
        // 只存哈希。rawToken 在本次响应返回给前端后，服务端再也拿不到它。
        token.setTokenHash(passwordHasher.sha256Hex(rawToken));
        token.setExpiresAt(expiresAt);
        // revokedAt / lastUsedAt 留空：一个表示仍有效，一个表示还没用过
        userTokenMapper.insert(token);

        log.debug("登录成功 userId={} tokenId={} token={}… 有效期至 {}",
                user.getId(), token.getId(), rawToken.substring(0, TOKEN_LOG_PREFIX), expiresAt);
        return new LoginResult(rawToken, expiresAt, user);
    }

    @Override
    public Long resolveUserId(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        // 单表条件查询 → 简单操作 → 构造器（项目规范）。
        // 三个条件一次查完，而不是「先查出来再在 Java 里判断」——
        // 后者会把一条无效令牌也读进内存，且多一次无谓的字段比较。
        UserToken token = userTokenMapper.selectOne(
                Wrappers.<UserToken>lambdaQuery()
                        .eq(UserToken::getTokenHash, passwordHasher.sha256Hex(rawToken))
                        .isNull(UserToken::getRevokedAt)
                        .gt(UserToken::getExpiresAt, now)
                        .last("LIMIT 1"));
        if (token == null) {
            return null;
        }

        // 节流更新 last_used_at（见 LAST_USED_THROTTLE 的说明）
        if (token.getLastUsedAt() == null
                || token.getLastUsedAt().isBefore(now.minus(LAST_USED_THROTTLE))) {
            UserToken touch = new UserToken();
            touch.setId(token.getId());
            touch.setLastUsedAt(now);
            // updateById 默认只更新非 null 字段，因此不会覆盖 token_hash 等列
            userTokenMapper.updateById(touch);
        }

        return token.getUserId();
    }

    @Override
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        // 单表条件更新 → 构造器。
        // 用 update 而不是「先查再改」：多一个来回，且并发下没有额外好处。
        int revoked = userTokenMapper.update(null,
                Wrappers.<UserToken>lambdaUpdate()
                        .set(UserToken::getRevokedAt, LocalDateTime.now())
                        .eq(UserToken::getTokenHash, passwordHasher.sha256Hex(rawToken))
                        .isNull(UserToken::getRevokedAt));
        log.debug("登出：撤销令牌 {} 条", revoked);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int revokeAll(Long userId) {
        if (userId == null) {
            return 0;
        }
        int revoked = userTokenMapper.update(null,
                Wrappers.<UserToken>lambdaUpdate()
                        .set(UserToken::getRevokedAt, LocalDateTime.now())
                        .eq(UserToken::getUserId, userId)
                        .isNull(UserToken::getRevokedAt));
        log.debug("撤销用户全部令牌 userId={} 共 {} 条", userId, revoked);
        return revoked;
    }

    /**
     * 生成明文令牌：32 字节随机数做 URL 安全的 Base64（无填充），得到 43 个字符。
     *
     * <p>用 Base64 URL 变体而不是标准 Base64：令牌会出现在 {@code Authorization} 头里，
     * 标准 Base64 的 {@code +} {@code /} {@code =} 在 URL、Cookie、日志里都可能被转义或截断。
     */
    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
