package org.example.englishquestionbank.service;

import org.example.englishquestionbank.entity.SysUser;

/**
 * 用户服务。
 *
 * <p><b>当前范围</b>：创建用户、查询用户、<b>设置密码</b>。
 * 登录与令牌签发在 {@link AuthService}（第 7 次修订拆出去，避免用户管理与会话管理混在一个类里）。
 * 注册接口（{@code POST /api/auth/register}）尚未提供，见 {@code docs/backlog.md}。
 *
 * <p><b>关于重名</b>：本服务<strong>不做「先查后插」</strong>，直接依赖数据库唯一索引
 * {@code uk_user_username} 兜底，重名时抛出 Spring 的 {@code DuplicateKeyException}。
 * 之所以不在这里转成自定义异常，是因为「转成 HTTP 409」属于 Controller 层的职责，
 * Service 层吞掉会丢失原始信息。
 */
public interface SysUserService {

    /**
     * 创建一个新用户。
     *
     * <p>执行步骤：校验用户名 → 构造实体（{@code role='USER'}、{@code userType=1}、{@code status=1}）
     * → 插入 → 由 MyBatis-Plus 把自增主键回填到返回对象。
     *
     * @param username 登录名，非空且长度不超过 64；会被 trim
     * @param nickname 昵称，空则回退为用户名
     * @return 创建成功的用户实体，{@code id} 已填充
     * @throws IllegalArgumentException 用户名为空或超长
     * @throws org.springframework.dao.DuplicateKeyException 用户名已存在
     */
    SysUser register(String username, String nickname);

    /**
     * 按登录名精确查询。
     *
     * @param username 登录名
     * @return 用户实体；不存在时返回 {@code null}
     */
    SysUser findByUsername(String username);

    /**
     * 按主键查询。
     *
     * @param id 用户 id
     * @return 用户实体；不存在时返回 {@code null}
     */
    SysUser findById(Long id);

    /**
     * 判断登录名是否已存在。
     *
     * @param username 登录名
     * @return 存在返回 {@code true}
     */
    boolean existsByUsername(String username);

    /**
     * 设置（或重置）某个用户的密码。
     *
     * <p>内部用 BCrypt 哈希后写入 {@code password_hash}，<b>明文不落库</b>。
     *
     * <p>本方法本身<b>不撤销该用户的既有登录令牌</b>。改密码应当顺带把别处的登录踢下线，
     * 但那需要同时操作 {@code user_token} 表，属于「一次写两张表」的跨表操作 ——
     * 调用方应显式组合：先 {@code setPassword}，再 {@code AuthService.revokeAll}，
     * 两个方法各自的事务语义清晰可见。目前尚无改密码接口，故暂无调用方。
     *
     * @param userId      用户 id
     * @param rawPassword 明文密码，非空
     * @throws IllegalArgumentException 用户 id 为空，或密码为空
     */
    void setPassword(Long userId, String rawPassword);
}
