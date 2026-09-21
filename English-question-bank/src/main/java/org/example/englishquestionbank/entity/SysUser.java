package org.example.englishquestionbank.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体，对应表 {@code sys_user}。
 *
 * <p>当前阶段只建实体，登录鉴权尚未实现。{@code passwordHash} 预留给 BCrypt 哈希，
 * 游客账号可为空。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 登录名，有唯一索引。 */
    private String username;

    /** BCrypt 哈希；游客或第三方登录时可为空。 */
    private String passwordHash;

    private String nickname;

    private String email;

    /** USER=普通用户 / ADMIN=题库管理员。 */
    private String role;

    /** 1=注册用户 2=游客。 */
    private Integer userType;

    /** 1=正常 0=禁用。 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
