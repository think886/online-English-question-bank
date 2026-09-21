package org.example.englishquestionbank.service;

import org.example.englishquestionbank.entity.SysUser;

/**
 * 用户服务。
 *
 * <p><b>当前范围</b>：只做「创建用户」与「查询用户」。
 * <b>登录、鉴权、密码加密均未实现</b>，因此 {@code password_hash} 会保持为空。
 * 这些能力的方案（Session vs JWT、是否需要游客模式）尚未讨论。
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
}
