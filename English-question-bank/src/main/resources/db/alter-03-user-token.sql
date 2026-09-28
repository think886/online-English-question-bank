-- =============================================================================
--  增量变更 03 · 新增 user_token 表（登录令牌）
-- -----------------------------------------------------------------------------
--  背景：项目此前没有真正的鉴权 —— 身份靠请求头 X-Debug-User-Id 直接传 userId，
--        任何调用方都能自称是任意用户（见 docs/backlog.md B-07）。
--        本次引入「用户名 + 密码 → 换取令牌」的登录流程。
--
--  设计决策 1 · 用「不透明随机令牌」而不是 JWT
--      本项目的每个请求本来就要查库（会话、作答记录、划词标记都在 MySQL），
--      JWT 的「无状态、免查库」优势在这里没有意义；而它带来两个真实代价：
--        · 不可撤销 —— 登出、改密码、封号之后，已签发的 JWT 在过期前依然有效
--        · 要新增 jjwt 依赖（三个 jar），或引入整套 Spring Security
--      不透明令牌存库则天然可撤销，且零新依赖（只需 BCrypt 那一个 jar）。
--
--  设计决策 2 · 库里存的是令牌的 SHA-256，不是令牌本身
--      令牌等价于密码。若明文入库，一旦数据库被拖库或备份泄漏，
--      攻击者可以直接拿这些令牌冒充所有在线用户。
--      存 SHA-256 之后，拿到库也无法反推出可用令牌。
--      ⚠ 这里刻意不用 BCrypt 哈希令牌：BCrypt 是故意慢的（防暴力破解低熵口令），
--        而令牌是 256 位高熵随机串，不存在被爆破的可能；
--        每个请求都要校验令牌，用 BCrypt 会把每次请求都拖慢几十毫秒。
--        高熵随机值的正确做法就是快速哈希（SHA-256）。
--
--  适用：已经建好 11 张表的数据库。
--  幂等性：不可重复执行（重复 CREATE TABLE 会报错）。
-- =============================================================================

USE english_question_bank;

CREATE TABLE user_token (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id      BIGINT UNSIGNED NOT NULL                  COMMENT '令牌属于哪个用户',
  token_hash   CHAR(64)        NOT NULL                  COMMENT '令牌的 SHA-256 十六进制（64 字符），绝不存明文',
  expires_at   DATETIME        NOT NULL                  COMMENT '过期时间；服务端校验时以数据库时间为准',
  revoked_at   DATETIME        NULL                      COMMENT '登出/改密码时置为当前时间即失效；NULL 表示有效',
  created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_used_at DATETIME        NULL                      COMMENT '最后一次使用时间，便于排查异常登录',

  PRIMARY KEY (id),

  -- 校验令牌走这个唯一索引：一次等值查询，不会退化成扫描
  UNIQUE KEY uk_token_hash (token_hash),

  -- 「踢掉某用户的所有登录」用这个索引
  KEY idx_token_user (user_id),

  -- 与其余表一致：不带 ON DELETE CASCADE
  CONSTRAINT fk_token_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录令牌';

-- ---------------------------------------------------------------------------
--  验证
-- ---------------------------------------------------------------------------
-- SHOW CREATE TABLE user_token;
--   → 应看到 uk_token_hash、idx_token_user、fk_token_user
--
-- ⚠ 清理测试用户时记得连带删这张表：
--   DELETE FROM user_token WHERE user_id = ?;   -- 必须排在 DELETE FROM sys_user 之前
--   （fk_token_user 不带 CASCADE，先删 sys_user 会被子表挡住）
-- ---------------------------------------------------------------------------
