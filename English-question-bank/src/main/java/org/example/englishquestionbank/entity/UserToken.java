package org.example.englishquestionbank.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录令牌实体，对应表 {@code user_token}。
 *
 * <p><b>⚠️ 这里存的是令牌的 SHA-256，不是令牌本身</b>（字段名 {@code tokenHash} 就是在强调这点）。
 * 令牌等价于密码：明文入库的话，一次拖库或备份泄漏就等于所有在线用户被冒充。
 *
 * <p><b>为什么不用 BCrypt 哈希令牌</b>：BCrypt 是<strong>故意慢</strong>的，
 * 用来抵御对低熵口令的暴力破解。而令牌是 32 字节（256 位）随机串，不存在被爆破的可能；
 * 且每个 API 请求都要校验令牌，用 BCrypt 会把每次请求都拖慢几十毫秒。
 * 高熵随机值的正确做法就是快速哈希。
 *
 * <p>表结构由 {@code db/schema.sql} 与 {@code db/alter-03-user-token.sql} 定义。
 */
@Data
@TableName("user_token")
public class UserToken {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 令牌属于哪个用户。 */
    private Long userId;

    /** 令牌的 SHA-256 十六进制（64 字符），绝不存明文。 */
    private String tokenHash;

    /** 过期时间。校验时与当前时间比较，不用数据库的 NOW()，便于测试注入。 */
    private LocalDateTime expiresAt;

    /** 登出/改密码时置为当前时间即失效；{@code null} 表示有效。 */
    private LocalDateTime revokedAt;

    private LocalDateTime createdAt;

    /**
     * 最后一次使用时间。
     *
     * <p>⚠️ 这个字段是<strong>节流更新</strong>的，不是每次请求都写
     * （见 {@code AuthServiceImpl#resolveUserId}）——
     * 否则一个「读」的鉴权动作会变成每个请求一次 UPDATE。
     */
    private LocalDateTime lastUsedAt;
}
