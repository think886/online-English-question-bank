-- =============================================================================
--  增量变更 01 · word 表补充词典字段
-- -----------------------------------------------------------------------------
--  背景：接入 ECDICT 词典数据后，需要补充考试大纲标签与词频字段，
--        同时把释义列从 VARCHAR(1024) 扩成 TEXT（高频多义词释义会超长）。
--
--  适用：已经在 Navicat 里按旧版 schema.sql 建好表的数据库。
--        全新安装直接用 schema.sql 即可，不需要跑这个文件。
--
--  幂等性：本脚本不可重复执行（ADD COLUMN 重复会报 1060）。
-- =============================================================================

USE english_question_bank;

-- ---------------------------------------------------------------------------
--  word 表
-- ---------------------------------------------------------------------------
ALTER TABLE word
  MODIFY COLUMN part_of_speech VARCHAR(32) NULL
         COMMENT 'n./v./adj.，从中文释义前缀提取',

  MODIFY COLUMN translation TEXT NULL
         COMMENT '中文释义，多个义项用 ; 分隔；NOT_FOUND 时为空',

  ADD COLUMN tag VARCHAR(64) NULL
         COMMENT '考试大纲标签，空格分隔：zk/gk/cet4/cet6/ky/toefl/ielts/gre'
         AFTER exchange,

  ADD COLUMN bnc INT NULL
         COMMENT 'BNC 语料库词频序号，越小越高频'
         AFTER tag,

  ADD COLUMN frq INT NULL
         COMMENT '当代语料库词频序号，越小越高频'
         AFTER bnc,

  ADD KEY idx_word_frq (frq);

-- ---------------------------------------------------------------------------
--  user_vocabulary 表：释义快照同步扩成 TEXT，避免从 word 表复制时被截断
-- ---------------------------------------------------------------------------
ALTER TABLE user_vocabulary
  MODIFY COLUMN translation TEXT NULL
         COMMENT '标记时抓到的释义快照，词典更新不影响用户';

-- ---------------------------------------------------------------------------
--  验证
-- ---------------------------------------------------------------------------
-- SHOW CREATE TABLE word;
-- SHOW CREATE TABLE user_vocabulary;
