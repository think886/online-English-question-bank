package org.example.englishquestionbank.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.englishquestionbank.dto.MarkCommand;
import org.example.englishquestionbank.dto.MarkResult;
import org.example.englishquestionbank.dto.MarkedWordSummary;
import org.example.englishquestionbank.entity.PracticeSession;
import org.example.englishquestionbank.entity.UserVocabulary;
import org.example.englishquestionbank.entity.UserWordMark;
import org.example.englishquestionbank.entity.Word;
import org.example.englishquestionbank.mapper.PracticeSessionMapper;
import org.example.englishquestionbank.mapper.SessionQuestionMapper;
import org.example.englishquestionbank.mapper.UserVocabularyMapper;
import org.example.englishquestionbank.mapper.UserWordMarkMapper;
import org.example.englishquestionbank.mapper.WordMapper;
import org.example.englishquestionbank.support.WordFormNormalizer;
import org.example.englishquestionbank.service.WordMarkService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link WordMarkService} 的实现。
 *
 * <p><b>本类是全项目写入最复杂的 Service</b>，一次 {@link #markWord} 会触及三张表：
 * {@code user_word_mark}（插入）、{@code user_vocabulary}（upsert 累加）、
 * {@code session_question}（原子自增）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WordMarkServiceImpl implements WordMarkService {

    /** {@code source_field} 允许的取值，与数据库列注释保持一致。 */
    private static final Set<String> ALLOWED_SOURCE_FIELDS =
            Set.of("PASSAGE", "STEM", "OPTION", "SOURCE_TEXT");

    /** 与 {@code user_word_mark.sentence VARCHAR(1024)} 对齐。 */
    private static final int MAX_SENTENCE_LENGTH = 1024;

    private final WordFormNormalizer normalizer;
    private final UserWordMarkMapper userWordMarkMapper;
    private final UserVocabularyMapper userVocabularyMapper;
    private final SessionQuestionMapper sessionQuestionMapper;
    private final PracticeSessionMapper practiceSessionMapper;
    private final WordMapper wordMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MarkResult markWord(MarkCommand cmd) {
        // ---- 1. 校验入参 ----
        validate(cmd);
        Long passageId = resolvePassageId(cmd);

        // ---- 2. 清洗 + 3. 归一化 ----
        String cleaned = WordFormNormalizer.clean(cmd.surfaceForm());
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("划词内容清洗后为空: " + cmd.surfaceForm());
        }
        WordFormNormalizer.Normalized normalized = normalizer.normalize(cleaned);
        log.debug("划词归一化 '{}' → '{}'（{}，{}）",
                cleaned, normalized.form(), normalized.path(), normalized.evidence());

        // ---- 4. 该位置是否已标记过 ----
        UserWordMark existing = findMarkAtSamePosition(cmd);
        if (existing != null) {
            // 空操作：不插入、不累加计数，直接返回已有信息 + 刚查到的释义
            log.debug("划词重复，未新增行 markId={}", existing.getId());
            return new MarkResult(existing.getId(), existing.getNormalizedForm(),
                    existing.getWordId(), normalized.translation(), true);
        }

        // ---- 5a. 写标记 ----
        // ⚠ 不写 session_key / question_key —— 它们是 VIRTUAL 生成列，由数据库自动计算
        UserWordMark mark = new UserWordMark();
        mark.setUserId(cmd.userId());
        mark.setSessionId(cmd.sessionId());
        mark.setQuestionId(cmd.questionId());
        mark.setPassageId(passageId);
        mark.setSourceField(cmd.sourceField());
        mark.setWordId(normalized.wordId());
        mark.setSurfaceForm(cmd.surfaceForm());
        mark.setNormalizedForm(normalized.form());
        mark.setSentence(truncate(cmd.sentence(), MAX_SENTENCE_LENGTH));
        mark.setCharStart(cmd.charStart());
        mark.setCharEnd(cmd.charEnd());
        userWordMarkMapper.insertIgnoreDuplicate(mark);

        // ---- 5b. upsert 生词本（SQL 层 mark_count + 1）----
        String displayForm = normalized.word() != null
                ? normalized.word().getDisplayForm()
                : cleaned;
        userVocabularyMapper.upsertMark(cmd.userId(), normalized.form(),
                normalized.wordId(), displayForm, normalized.translation());

        // ---- 5c. 该题标记词次 +1 ----
        // 只有落在具体题目上时才需要；在文章正文上划词（questionId 为空）不涉及
        if (cmd.sessionId() != null && cmd.questionId() != null) {
            sessionQuestionMapper.incrementMarkedWordCount(cmd.sessionId(), cmd.questionId());
        }

        return new MarkResult(mark.getId(), normalized.form(),
                normalized.wordId(), normalized.translation(), false);
    }

    @Override
    public List<UserWordMark> listMarksByQuestion(Long userId, Long questionId) {
        return userWordMarkMapper.selectList(
                Wrappers.<UserWordMark>lambdaQuery()
                        .eq(UserWordMark::getUserId, userId)
                        .eq(UserWordMark::getQuestionId, questionId)
                        .orderByAsc(UserWordMark::getCharStart));
    }

    @Override
    public List<UserWordMark> listMarksBySession(Long userId, Long sessionId) {
        return userWordMarkMapper.selectList(
                Wrappers.<UserWordMark>lambdaQuery()
                        .eq(UserWordMark::getUserId, userId)
                        .eq(UserWordMark::getSessionId, sessionId)
                        .orderByAsc(UserWordMark::getCharStart));
    }

    @Override
    public List<UserVocabulary> listVocabulary(Long userId, int limit) {
        return userVocabularyMapper.selectByUser(userId, limit);
    }

    // =====================================================================

    private void validate(MarkCommand cmd) {
        if (cmd == null) {
            throw new IllegalArgumentException("标记入参不能为空");
        }
        if (cmd.userId() == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (cmd.surfaceForm() == null || cmd.surfaceForm().isBlank()) {
            throw new IllegalArgumentException("surfaceForm 不能为空");
        }
        if (cmd.sourceField() == null || !ALLOWED_SOURCE_FIELDS.contains(cmd.sourceField())) {
            throw new IllegalArgumentException("sourceField 非法: " + cmd.sourceField()
                    + "，允许值 " + ALLOWED_SOURCE_FIELDS);
        }
        if (cmd.charStart() == null || cmd.charEnd() == null
                || cmd.charStart() < 0 || cmd.charEnd() <= cmd.charStart()) {
            throw new IllegalArgumentException(String.format(
                    "偏移量非法: charStart=%s charEnd=%s", cmd.charStart(), cmd.charEnd()));
        }
        // 会话的校验挪到 resolvePassageId()，那里顺便把 passageId 定下来
    }

    /**
     * 校验会话存在性 / 归属，并返回该标记应当写入的 {@code passageId}。
     *
     * <p><b>为什么不直接用 {@code cmd.passageId()}：</b>划词最主流的场景是在**文章正文**上划，
     * 此时前端只知道「我正在做这么一份练习」，并不知道文章在库里的主键。
     * 让前端传 passageId 等于把内部 id 暴露给客户端，而且一旦传错就会写脏数据 ——
     * 文章本来就在会话上，直接以会话为准即可，前端传什么都不影响。
     *
     * <p>无会话时（自由阅读划词）才回退到入参里的 passageId，允许为 null。
     */
    private Long resolvePassageId(MarkCommand cmd) {
        if (cmd.sessionId() == null) {
            return cmd.passageId();
        }
        PracticeSession session = practiceSessionMapper.selectById(cmd.sessionId());
        if (session == null) {
            throw new IllegalStateException("会话不存在: sessionId=" + cmd.sessionId());
        }
        if (!cmd.userId().equals(session.getUserId())) {
            throw new IllegalStateException("会话不属于该用户: sessionId=" + cmd.sessionId()
                    + " sessionUserId=" + session.getUserId() + " userId=" + cmd.userId());
        }
        return session.getPassageId();
    }

    /**
     * 查询「同一用户 + 同一会话 + 同一文本来源 + 同一题目 + 同一字符区间」是否已标记过。
     *
     * <p>这正是唯一键 {@code uk_mark_pos} 的判定条件。
     * 用构造器而不是把它写进 XML：它只是单表条件查询，属「简单操作」。
     *
     * <p>三个可空的 id 需要分别用 {@code eq} / {@code isNull} 处理 ——
     * 直接 {@code eq(field, null)} 会生成 {@code = NULL}，永远匹配不到任何行。
     * LambdaQueryWrapper 的 {@code condition} 重载正好用来做这件事。
     */
    private UserWordMark findMarkAtSamePosition(MarkCommand cmd) {
        return userWordMarkMapper.selectOne(
                Wrappers.<UserWordMark>lambdaQuery()
                        .eq(UserWordMark::getUserId, cmd.userId())
                        .eq(UserWordMark::getSourceField, cmd.sourceField())
                        .eq(UserWordMark::getCharStart, cmd.charStart())
                        .eq(UserWordMark::getCharEnd, cmd.charEnd())
                        .eq(cmd.sessionId() != null, UserWordMark::getSessionId, cmd.sessionId())
                        .isNull(cmd.sessionId() == null, UserWordMark::getSessionId)
                        .eq(cmd.questionId() != null, UserWordMark::getQuestionId, cmd.questionId())
                        .isNull(cmd.questionId() == null, UserWordMark::getQuestionId)
                        .last("LIMIT 1"));
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    @Override
    public List<MarkedWordSummary> summarizeSessionMarks(Long userId, Long sessionId) {
        List<UserWordMark> marks = listMarksBySession(userId, sessionId);
        if (marks.isEmpty()) {
            return List.of();
        }

        // 按原形聚合，用 LinkedHashMap 保持 listMarksBySession 给出的顺序
        //
        // 【纠正】这里原本写的是「保持首次标记的顺序」—— 错的。
        // listMarksBySession 的 SQL 是 ORDER BY char_start ASC，
        // 所以分组顺序其实是「该词在文本中首次出现的位置」顺序（阅读顺序），
        // 与用户实际点击的先后无关。行为本身没问题（按阅读顺序展示更自然），
        // 但注释把机制说错了，现予更正。
        Map<String, List<UserWordMark>> byForm = new LinkedHashMap<>();
        for (UserWordMark mark : marks) {
            byForm.computeIfAbsent(mark.getNormalizedForm(), k -> new ArrayList<>()).add(mark);
        }

        // 批量取释义：一次 IN 查询，而不是每个词查一次
        List<Long> wordIds = marks.stream()
                .map(UserWordMark::getWordId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Word> wordById = new HashMap<>();
        if (!wordIds.isEmpty()) {
            for (Word word : wordMapper.selectList(
                    Wrappers.<Word>lambdaQuery().in(Word::getId, wordIds))) {
                wordById.put(word.getId(), word);
            }
        }

        List<MarkedWordSummary> summaries = new ArrayList<>(byForm.size());
        for (Map.Entry<String, List<UserWordMark>> entry : byForm.entrySet()) {
            List<UserWordMark> group = entry.getValue();
            UserWordMark first = group.get(0);
            Word word = first.getWordId() == null ? null : wordById.get(first.getWordId());
            summaries.add(new MarkedWordSummary(
                    entry.getKey(),
                    word != null && word.getDisplayForm() != null
                            ? word.getDisplayForm() : first.getSurfaceForm(),
                    // 词典未收录时为 null —— 前端可据此提示「暂未收录释义」
                    word == null ? null : word.getTranslation(),
                    first.getWordId(),
                    group.size(),
                    group.stream().map(UserWordMark::getSentence)
                            .filter(Objects::nonNull).findFirst().orElse(null)));
        }
        return summaries;
    }
}
