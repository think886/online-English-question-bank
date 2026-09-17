-- =============================================================================
--  英语答题系统 · 数据库结构 (MySQL 8.0+ / InnoDB / utf8mb4)
-- -----------------------------------------------------------------------------
--  题型范围：READING(文章 + 选择题) / TRANSLATION(翻译题)，其他题型不涉及。
--  核心流程：做一篇阅读 -> 划词标记生词 -> 整体提交 -> LLM 评分翻译题
--            -> 结果页展示「标记过的单词及释义」+「我做过的题目」
--
--  ⚠ 本文件含 DROP TABLE，仅用于开发阶段反复重建，请勿在有数据的库上直接执行。
--  ⚠ 放在 resources/db/ 而非 resources/ 根目录，避免被 Spring Boot 自动初始化执行。
-- =============================================================================

-- 建库 + 选库：使本脚本可直接用一条命令导入，无需手动先建库。
-- 库名若要修改，请同步修改 application.yml 中的连接串。
CREATE DATABASE IF NOT EXISTS english_question_bank
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;
USE english_question_bank;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS translation_grading;
DROP TABLE IF EXISTS user_vocabulary;
DROP TABLE IF EXISTS user_word_mark;
DROP TABLE IF EXISTS answer_record;
DROP TABLE IF EXISTS session_question;
DROP TABLE IF EXISTS practice_session;
DROP TABLE IF EXISTS word;
DROP TABLE IF EXISTS question_option;
DROP TABLE IF EXISTS question;
DROP TABLE IF EXISTS passage;
DROP TABLE IF EXISTS sys_user;


-- =============================================================================
--  组 1 · 用户
-- =============================================================================

CREATE TABLE sys_user (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  username      VARCHAR(64)     NOT NULL                COMMENT '登录名',
  password_hash VARCHAR(100)    NULL                    COMMENT 'BCrypt 哈希；预留游客/第三方登录时可为空',
  nickname      VARCHAR(64)     NULL                    COMMENT '昵称',
  email         VARCHAR(128)    NULL,
  role          VARCHAR(16)     NOT NULL DEFAULT 'USER' COMMENT 'USER=普通用户 / ADMIN=题库管理员',
  user_type     TINYINT         NOT NULL DEFAULT 1      COMMENT '1=注册用户 2=游客',
  status        TINYINT         NOT NULL DEFAULT 1      COMMENT '1=正常 0=禁用',
  created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';


-- =============================================================================
--  组 2 · 题库（阅读文章 + 选择题 + 翻译题）
-- =============================================================================

CREATE TABLE passage (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  title       VARCHAR(255)    NULL                  COMMENT '文章标题',
  content     MEDIUMTEXT      NOT NULL              COMMENT '英文原文，用换行分段',
  translation MEDIUMTEXT      NULL                  COMMENT '全文参考译文（可选）',
  source      VARCHAR(255)    NULL                  COMMENT '出处，如 2023 考研英语一 Text 2',
  category    VARCHAR(64)     NULL                  COMMENT '题材：科普/经济/教育…',
  difficulty  TINYINT         NOT NULL DEFAULT 3    COMMENT '难度 1~5',
  word_count  INT             NOT NULL DEFAULT 0    COMMENT '词数',
  status      TINYINT         NOT NULL DEFAULT 1    COMMENT '1=启用 0=下架',
  created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_passage_difficulty (difficulty, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='阅读文章';


-- 阅读题与翻译题同表：共享 score/difficulty/analysis，
-- 用 question_type + 可空的 passage_id / source_text 区分，便于混合组卷与统一作答记录。
CREATE TABLE question (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  question_type    VARCHAR(16)     NOT NULL              COMMENT 'READING=阅读选择 / TRANSLATION=翻译',
  passage_id       BIGINT UNSIGNED NULL                  COMMENT '阅读题所属文章；翻译题为 NULL',
  stem             TEXT            NULL                  COMMENT '题干 / 作答要求',
  source_text      TEXT            NULL                  COMMENT '翻译题：待翻译的英文原文',
  reference_answer TEXT            NULL                  COMMENT '翻译题：参考译文',
  analysis         TEXT            NULL                  COMMENT '解析',
  difficulty       TINYINT         NOT NULL DEFAULT 3,
  score            INT             NOT NULL DEFAULT 1    COMMENT '满分',
  seq              INT             NOT NULL DEFAULT 0    COMMENT '文章内/章节内题号',
  status           TINYINT         NOT NULL DEFAULT 1,
  created_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_q_passage (passage_id, seq),
  KEY idx_q_type (question_type, status),
  CONSTRAINT fk_q_passage FOREIGN KEY (passage_id) REFERENCES passage(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='题目（阅读题 + 翻译题）';


CREATE TABLE question_option (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  question_id BIGINT UNSIGNED NOT NULL,
  option_key  CHAR(1)         NOT NULL              COMMENT 'A/B/C/D',
  content     TEXT            NOT NULL              COMMENT '选项内容',
  is_correct  TINYINT         NOT NULL DEFAULT 0    COMMENT '1=正确选项',
  seq         INT             NOT NULL DEFAULT 0    COMMENT '展示顺序',
  PRIMARY KEY (id),
  UNIQUE KEY uk_opt (question_id, option_key),
  CONSTRAINT fk_opt_q FOREIGN KEY (question_id) REFERENCES question(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='选择题选项（仅 READING 使用）';


-- =============================================================================
--  组 3 · 单词词典（本地词库为主，未命中时调外部 API 并回写）
-- =============================================================================

CREATE TABLE word (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  headword       VARCHAR(64)     NOT NULL              COMMENT '词条原形，小写，如 run',
  display_form   VARCHAR(64)     NULL                  COMMENT '展示形式，如 Run',
  phonetic_uk    VARCHAR(64)     NULL,
  phonetic_us    VARCHAR(64)     NULL,
  part_of_speech VARCHAR(16)     NULL                  COMMENT 'n./v./adj.',
  translation    VARCHAR(1024)   NULL                  COMMENT '中文释义，多义项用 ; 分隔；NOT_FOUND 时为空',
  exchange       VARCHAR(255)    NULL                  COMMENT '变形：过去式/复数等，便于反查原形',
  lookup_status  VARCHAR(16)     NOT NULL DEFAULT 'LOCAL'
                 COMMENT 'LOCAL=本地词库 / API=外部回写 / PENDING=待补全 / NOT_FOUND=查无结果（写入空行防止反复请求外部接口）',
  source         VARCHAR(64)     NULL                  COMMENT '数据来源标识，如 ecdict / youdao / manual',
  raw_json       JSON            NULL                  COMMENT '外部接口原始返回，便于以后扩展多义项结构',
  hit_count      INT             NOT NULL DEFAULT 0    COMMENT '被查询次数，用于热度排序 / 缓存淘汰',
  is_verified    TINYINT         NOT NULL DEFAULT 0    COMMENT '1=已人工校对',
  fetched_at     DATETIME        NULL                  COMMENT '释义抓取时间',
  created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_word_headword (headword),
  KEY idx_word_status (lookup_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单词词典（本地主数据 + 外部接口缓存）';


-- =============================================================================
--  组 4 · 作答（一次会话 = 一篇阅读及其下 N 道题，整体提交）
-- =============================================================================

CREATE TABLE practice_session (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id       BIGINT UNSIGNED NOT NULL,
  mode          VARCHAR(16)     NOT NULL DEFAULT 'READING' COMMENT 'READING / TRANSLATION / MIXED（预留）',
  passage_id    BIGINT UNSIGNED NULL                  COMMENT '按篇练习时记录',
  total_count   INT             NOT NULL DEFAULT 0    COMMENT '本次题量',
  answered_count INT            NOT NULL DEFAULT 0    COMMENT '已作答题量',
  correct_count INT             NOT NULL DEFAULT 0    COMMENT '答对题量',
  score         INT             NOT NULL DEFAULT 0    COMMENT '总得分',
  max_score     INT             NOT NULL DEFAULT 0    COMMENT '总分值',
  marked_word_count INT         NOT NULL DEFAULT 0    COMMENT '本次标记的去重单词数（结果页快速展示）',
  status        VARCHAR(16)     NOT NULL DEFAULT 'IN_PROGRESS'
                COMMENT 'IN_PROGRESS / FINISHED / ABANDONED',
  started_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at   DATETIME        NULL,
  duration_ms   BIGINT          NULL                  COMMENT '总耗时',
  PRIMARY KEY (id),
  KEY idx_sess_user (user_id, started_at),
  KEY idx_sess_passage (passage_id),
  CONSTRAINT fk_sess_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
  CONSTRAINT fk_sess_passage FOREIGN KEY (passage_id) REFERENCES passage(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='练习会话（一篇阅读 = 一次会话）';


-- 固化本次会话抽到的题目与顺序：文章后续被编辑也不会打乱历史会话，
-- 同时承载「该题标记了几个词」这类结果页需要的冗余统计。
CREATE TABLE session_question (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  session_id        BIGINT UNSIGNED NOT NULL,
  question_id       BIGINT UNSIGNED NOT NULL,
  sort_order        INT             NOT NULL DEFAULT 0  COMMENT '本次会话中的展示顺序',
  status            VARCHAR(16)     NOT NULL DEFAULT 'UNANSWERED' COMMENT 'UNANSWERED / ANSWERED',
  marked_word_count INT             NOT NULL DEFAULT 0  COMMENT '该题标记的词次（冗余）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sq (session_id, question_id),
  KEY idx_sq_question (question_id),
  CONSTRAINT fk_sq_session FOREIGN KEY (session_id) REFERENCES practice_session(id) ON DELETE CASCADE,
  CONSTRAINT fk_sq_question FOREIGN KEY (question_id) REFERENCES question(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话题目快照';


CREATE TABLE answer_record (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  session_id     BIGINT UNSIGNED NOT NULL,
  user_id        BIGINT UNSIGNED NOT NULL,
  question_id    BIGINT UNSIGNED NOT NULL,
  user_answer    TEXT            NULL                  COMMENT '阅读题存选项 key(A/B/C/D)，翻译题存用户译文',
  is_correct     TINYINT         NULL                  COMMENT '阅读题 0/1；翻译题在评分完成前为 NULL',
  score          INT             NULL                  COMMENT '实得分',
  max_score      INT             NULL                  COMMENT '本题满分快照',
  grading_status VARCHAR(16)     NOT NULL DEFAULT 'NONE'
                 COMMENT 'NONE=无需判分 / PENDING=待评分 / GRADING=评分中 / DONE=已评分 / FAILED=评分失败',
  graded_by      VARCHAR(16)     NULL                  COMMENT 'AUTO / AI / SELF / TEACHER',
  graded_at      DATETIME        NULL,
  duration_ms    BIGINT          NULL                  COMMENT '本题耗时',
  answered_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_answer (session_id, question_id),
  KEY idx_answer_user_q (user_id, question_id),
  KEY idx_answer_user_time (user_id, answered_at),
  KEY idx_answer_grading (grading_status),
  CONSTRAINT fk_ans_session FOREIGN KEY (session_id) REFERENCES practice_session(id) ON DELETE CASCADE,
  CONSTRAINT fk_ans_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
  CONSTRAINT fk_ans_question FOREIGN KEY (question_id) REFERENCES question(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='作答记录（错题本数据源）';


-- LLM 评分的「过程」单独存表：answer_record 只保留结论(score/graded_by)，
-- 细节(多维得分/点评/原始返回)放这里，便于保存历史评分记录与提示词效果回溯。
CREATE TABLE translation_grading (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  answer_record_id   BIGINT UNSIGNED NOT NULL,
  grader_type        VARCHAR(16)     NOT NULL              COMMENT 'AI / SELF / TEACHER',
  grader_name        VARCHAR(64)     NULL                  COMMENT '模型名或批改人',
  prompt_version     VARCHAR(32)     NULL                  COMMENT '提示词版本，便于效果回溯',
  accuracy_score     DECIMAL(5,2)    NULL                  COMMENT '准确度得分',
  fluency_score      DECIMAL(5,2)    NULL                  COMMENT '流畅度得分',
  completeness_score DECIMAL(5,2)    NULL                  COMMENT '完整度得分',
  total_score        DECIMAL(5,2)    NULL                  COMMENT '总分',
  max_score          DECIMAL(5,2)    NULL                  COMMENT '满分',
  comment            TEXT            NULL                  COMMENT '总评',
  sentence_feedback  JSON            NULL                  COMMENT '逐句点评 / 参考译法',
  raw_response       JSON            NULL                  COMMENT 'LLM 原始返回',
  is_current         TINYINT         NOT NULL DEFAULT 1    COMMENT '1=当前生效的评分，0=历史评分',
  created_at         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_tg_answer (answer_record_id, is_current),
  CONSTRAINT fk_tg_answer FOREIGN KEY (answer_record_id) REFERENCES answer_record(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='翻译题评分明细';


-- =============================================================================
--  组 5 · 单词标记 与 生词本
-- =============================================================================

-- 标记原始事件：一次划词 = 一行。
-- 同时记录「谁 + 哪个词 + 在哪篇文章/哪道题标的 + 在文本中的位置」，
-- 缺任何一维都无法还原「用户在某题中标记过的单词」。
CREATE TABLE user_word_mark (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id         BIGINT UNSIGNED NOT NULL,
  session_id      BIGINT UNSIGNED NULL                  COMMENT '所属练习会话；脱离会话的自由阅读为 NULL',
  question_id     BIGINT UNSIGNED NULL                  COMMENT '标记时正在作答的题目',
  passage_id      BIGINT UNSIGNED NULL                  COMMENT '该词实际所在文章',
  source_field    VARCHAR(16)     NOT NULL DEFAULT 'PASSAGE'
                  COMMENT 'PASSAGE=文章正文 / STEM=题干 / OPTION=选项 / SOURCE_TEXT=待译原文',
  word_id         BIGINT UNSIGNED NULL                  COMMENT '命中词典则为词条 id，未收录为 NULL',
  surface_form    VARCHAR(64)     NOT NULL              COMMENT '原文形式，如 running',
  normalized_form VARCHAR(64)     NOT NULL              COMMENT '归一化原形（小写），如 run',
  sentence        VARCHAR(1024)   NULL                  COMMENT '所在句子快照，复习时可看语境',
  char_start      INT             NOT NULL              COMMENT '在文本中的起始下标(0 基，含)',
  char_end        INT             NOT NULL              COMMENT '结束下标(不含)',
  created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mark_pos (user_id, question_id, char_start, char_end),
  KEY idx_mark_user_word (user_id, normalized_form),
  KEY idx_mark_session (session_id),
  KEY idx_mark_question (question_id),
  CONSTRAINT fk_mark_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
  CONSTRAINT fk_mark_session FOREIGN KEY (session_id) REFERENCES practice_session(id) ON DELETE CASCADE,
  CONSTRAINT fk_mark_question FOREIGN KEY (question_id) REFERENCES question(id),
  CONSTRAINT fk_mark_word FOREIGN KEY (word_id) REFERENCES word(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单词标记原始记录';


-- 生词本：user × 词 去重后的复习视图，由 user_word_mark upsert 维护。
CREATE TABLE user_vocabulary (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id         BIGINT UNSIGNED NOT NULL,
  normalized_form VARCHAR(64)     NOT NULL              COMMENT '归一化原形（小写）',
  word_id         BIGINT UNSIGNED NULL                  COMMENT '命中词典时的词条 id',
  display_form    VARCHAR(64)     NULL                  COMMENT '展示形式',
  translation     VARCHAR(1024)   NULL                  COMMENT '标记时抓到的释义快照，词典更新不影响用户',
  mark_count      INT             NOT NULL DEFAULT 1    COMMENT '累计标记次数',
  first_marked_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_marked_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  mastery         TINYINT         NOT NULL DEFAULT 0    COMMENT '0=陌生 1=模糊 2=已掌握',
  review_count    INT             NOT NULL DEFAULT 0,
  next_review_at  DATETIME        NULL                  COMMENT '留给艾宾浩斯复习',
  note            VARCHAR(255)    NULL                  COMMENT '用户笔记',
  PRIMARY KEY (id),
  UNIQUE KEY uk_vocab (user_id, normalized_form),
  KEY idx_vocab_review (user_id, next_review_at),
  KEY idx_vocab_last (user_id, last_marked_at),
  CONSTRAINT fk_vocab_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
  CONSTRAINT fk_vocab_word FOREIGN KEY (word_id) REFERENCES word(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户生词本（聚合去重）';

SET FOREIGN_KEY_CHECKS = 1;
