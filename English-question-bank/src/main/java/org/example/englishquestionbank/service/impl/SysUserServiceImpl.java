package org.example.englishquestionbank.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.englishquestionbank.entity.SysUser;
import org.example.englishquestionbank.mapper.SysUserMapper;
import org.example.englishquestionbank.service.SysUserService;
import org.example.englishquestionbank.support.PasswordHasher;
import org.springframework.stereotype.Service;

/**
 * {@link SysUserService} 的实现。
 *
 * <p>本类只有单表操作，因此<strong>没有一处 {@code @Transactional}</strong> ——
 * 单条 INSERT / SELECT 本身就是原子的，加事务只是徒增开销。
 * 事务只在「一次操作写多张表」时才需要（见 {@code PracticeSessionServiceImpl} 等）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysUserServiceImpl implements SysUserService {

    /** 与数据库列 {@code username VARCHAR(64)} 对齐。 */
    private static final int MAX_USERNAME_LENGTH = 64;

    private final SysUserMapper sysUserMapper;
    private final PasswordHasher passwordHasher;

    @Override
    public SysUser register(String username, String nickname) {
        // ---- 1. 入参校验 ----
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        String name = username.trim();
        if (name.length() > MAX_USERNAME_LENGTH) {
            throw new IllegalArgumentException(
                    "用户名长度不能超过 " + MAX_USERNAME_LENGTH + "，实际 " + name.length());
        }

        // ---- 2. 构造实体 ----
        SysUser user = new SysUser();
        user.setUsername(name);
        user.setNickname(nickname == null || nickname.isBlank() ? name : nickname.trim());
        user.setRole("USER");
        user.setUserType(1);   // 1 = 注册用户
        user.setStatus(1);     // 1 = 正常
        // passwordHash 留空：本方法只负责「建账号」。
        // 密码由调用方通过 setPassword() 显式设置 —— 这样「新用户没有密码」是个
        // 明确的状态（登录时会被当成凭据错误），而不是被某个默认密码掩盖过去。
        // createdAt / updatedAt 由数据库 DEFAULT CURRENT_TIMESTAMP 填充，实体不设置

        // ---- 3. 插入 ----
        // 注意：不在这里 try-catch DuplicateKeyException。
        // 重名属于调用方能处理的业务冲突，让它冒泡到 Controller 层转成 HTTP 409，
        // 在 Service 层吞掉会丢失「到底是哪个唯一键冲突」的原始信息。
        sysUserMapper.insert(user);

        // ---- 4. 返回 ----
        // 实体已经配了 @TableId(type = IdType.AUTO)，MyBatis-Plus 会用
        // useGeneratedKeys 把自增主键回填到 user.id，不必再查一次数据库。
        log.debug("创建用户成功 id={} username={}", user.getId(), user.getUsername());
        return user;
    }

    @Override
    public SysUser findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        // username 有唯一索引，最多一行；用 selectOne 语义清晰。
        // 注意：若匹配到多行，MyBatis-Plus 会抛 TooManyResultsException —— 那说明唯一索引被破坏了，
        // 抛出来暴露问题比悄悄取第一行更好。
        return sysUserMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username.trim()));
    }

    @Override
    public SysUser findById(Long id) {
        if (id == null) {
            return null;
        }
        return sysUserMapper.selectById(id);
    }

    @Override
    public boolean existsByUsername(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        // 用 count 而不是 selectOne：只关心存在性，不必把整行查出来
        return sysUserMapper.selectCount(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username.trim())) > 0;
    }

    @Override
    public void setPassword(Long userId, String rawPassword) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }

        // 用 updateById + 实体：MyBatis-Plus 默认只更新非 null 字段，
        // 因此这里不会误改 username / role / status 等列。
        SysUser update = new SysUser();
        update.setId(userId);
        update.setPasswordHash(passwordHasher.hash(rawPassword));
        int rows = sysUserMapper.updateById(update);
        if (rows == 0) {
            // 影响 0 行说明 id 不存在。静默成功会让「给不存在的用户设密码」看起来正常，
            // 等到登录时才暴露，排查成本高得多。
            throw new IllegalArgumentException("用户不存在: userId=" + userId);
        }
        log.debug("已设置密码 userId={}（已 BCrypt 哈希，明文未落库）", userId);
    }
}
