-- =============================================================================
--  增量变更 02 · user_word_mark 去重键修复
-- -----------------------------------------------------------------------------
--  原约束： UNIQUE KEY uk_mark_pos (user_id, question_id, char_start, char_end)
--  存在两个缺陷：
--
--  缺陷 1 · NULL 使去重失效
--      MySQL 唯一索引允许多行 NULL。用户在文章正文里划词时 question_id 为空，
--      约束形同虚设，同一个位置的同一个词可以重复写入。
--
--  缺陷 2 · 跨会话冲突（更严重）
--      唯一键里没有 session_id。用户把同一篇文章做第二遍、在同一位置标记同一个词时，
--      会触发唯一键冲突导致标记写不进去 —— 结果是第二次练习的结果页里那个词不显示，
--      现象是"我明明标了却没有"，且极难排查。
--
--  修复方式：用 VIRTUAL 生成列把可空列归一为 0，再纳入唯一键。
--      生成列由数据库自动计算，应用层不需要写任何额外逻辑，也不会破坏外键
--      （基础列仍然可空，fk_mark_session / fk_mark_question 照常生效）。
--
--  ⚠ 这里必须用 VIRTUAL，不能用 STORED。官方文档 15.1.20.8 明确规定：
--      "A foreign key constraint on the base column of a STORED generated column
--       cannot use CASCADE, SET NULL, or SET DEFAULT as ON UPDATE or ON DELETE
--       referential actions."
--    本表的 fk_mark_session 带 ON DELETE CASCADE，而 session_id 正是生成列的
--    基础列 —— 写成 STORED 会让这条 ALTER 直接失败。
--    VIRTUAL 不受该限制，且不物化、不占存储；
--    官方文档 15.1.20.9 确认："Secondary indexes that include virtual columns
--    may be defined as UNIQUE."
--
--  适用：已经在 Navicat 里按旧版 schema.sql 建好表的数据库。
--  幂等性：不可重复执行（重复 DROP INDEX / ADD COLUMN 都会报错）。
-- =============================================================================

USE english_question_bank;

ALTER TABLE user_word_mark
  -- 先摘掉有缺陷的旧唯一键
  DROP INDEX uk_mark_pos,

  -- session_id 与 question_id 都可能为 NULL，各配一个生成列归一为 0
  ADD COLUMN session_key BIGINT UNSIGNED
      GENERATED ALWAYS AS (IFNULL(session_id, 0)) VIRTUAL
      COMMENT '生成列：会话 id 的 NULL 归零版本，仅供唯一键使用',

  ADD COLUMN question_key BIGINT UNSIGNED
      GENERATED ALWAYS AS (IFNULL(question_id, 0)) VIRTUAL
      COMMENT '生成列：题目 id 的 NULL 归零版本，仅供唯一键使用',

  -- 新的去重键：
  --   user_id      谁标的
  --   session_key  哪一次练习（修缺陷 2）
  --   source_field 文章正文 / 题干 / 选项 / 待译原文（不同文本里同一偏移量是两回事）
  --   question_key 哪道题（修缺陷 1）
  --   char_start / char_end  文本中的位置
  ADD UNIQUE KEY uk_mark_pos
      (user_id, session_key, source_field, question_key, char_start, char_end);

-- ---------------------------------------------------------------------------
--  验证
-- ---------------------------------------------------------------------------
-- SHOW CREATE TABLE user_word_mark;
--
-- 应能看到两个 VIRTUAL GENERATED 生成列，以及 6 列的 uk_mark_pos。
-- ---------------------------------------------------------------------------
