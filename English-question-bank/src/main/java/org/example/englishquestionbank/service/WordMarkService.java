package org.example.englishquestionbank.service;

import org.example.englishquestionbank.dto.MarkCommand;
import org.example.englishquestionbank.dto.MarkResult;
import org.example.englishquestionbank.dto.MarkedWordSummary;
import org.example.englishquestionbank.entity.UserVocabulary;
import org.example.englishquestionbank.entity.UserWordMark;

import java.util.List;

/**
 * 划词标记服务 —— 本系统最核心的功能。
 *
 * <p>负责两件事：记录用户每一次划词（{@code user_word_mark}），
 * 以及维护按原形去重的生词本（{@code user_vocabulary}）。
 *
 * <p><b>当前范围</b>：不含「取消标记」，也不含词典未收录时的外部 API 查词回写
 * （未收录的词 {@code wordId} 写 NULL，释义留空，等后续接入）。
 */
public interface WordMarkService {

    /**
     * 标记一个单词。
     *
     * <p>执行步骤（整个过程在<strong>一个事务</strong>内）：
     * <ol>
     *   <li>校验入参，并确认会话（若提供）属于该用户</li>
     *   <li>清洗原文形式（剥掉首尾标点、转小写）</li>
     *   <li><b>词形归一化</b>：把 {@code running} 还原成 {@code run}，并顺带取出释义</li>
     *   <li>查询该位置是否已标记过 —— 这决定后续要不要动计数，也决定返回值</li>
     *   <li>若未标记过：写入 {@code user_word_mark}；upsert 生词本（{@code mark_count + 1}）；
     *       该题在本次会话中的标记词次 +1</li>
     * </ol>
     *
     * <p><b>幂等性</b>：同一位置重复标记是空操作，不会产生第二行，也不会重复累加计数。
     *
     * <p><b>为什么「是否新增」要靠先查而不是靠 insert 的返回值</b>：
     * {@code ON DUPLICATE KEY UPDATE} 的 affected-rows 语义受 JDBC 的
     * {@code useAffectedRows} 开关影响，Connector/J 默认会把「匹配到但未改变」也计成 1，
     * 无法区分。而这里的「先查」不是计数器读改写（那种在并发下会丢更新），
     * 只是判断存在性，真正的累加仍然由 SQL 的 {@code mark_count + 1} 原子完成。
     *
     * @param cmd 标记入参
     * @return 标记结果，含归一化原形与释义，供前端立即显示
     * @throws IllegalArgumentException 入参非法（用户/词为空、偏移量不合法、sourceField 非法）
     * @throws IllegalStateException    会话不存在或不属于该用户
     */
    MarkResult markWord(MarkCommand cmd);

    /**
     * 查询某用户在某道题上标记过的所有单词，按文本位置排序。
     *
     * <p>用于答题结果页展示「你在这道题里标了哪些词」。
     *
     * @param userId     用户 id
     * @param questionId 题目 id
     * @return 标记列表，按 {@code charStart} 升序
     */
    List<UserWordMark> listMarksByQuestion(Long userId, Long questionId);

    /**
     * 查询某用户在某次练习会话中标记过的所有单词，按文本位置排序。
     *
     * @param userId    用户 id
     * @param sessionId 会话 id
     * @return 标记列表，按 {@code charStart} 升序
     */
    List<UserWordMark> listMarksBySession(Long userId, Long sessionId);

    /**
     * 查询用户的生词本，按最后标记时间倒序。
     *
     * @param userId 用户 id
     * @param limit  取多少条
     * @return 生词列表
     */
    List<UserVocabulary> listVocabulary(Long userId, int limit);

    /**
     * 汇总本次会话标记过的词 —— <b>按原形去重，并补上中文释义</b>。
     *
     * <p>与 {@link #listMarksBySession} 的区别：
     * 后者返回的是**每一次划词事件**（同一个词在不同位置标两次就是两行），
     * 前者返回的是「本次练习中我标记了哪些**不同的词**、分别是什么意思」。
     *
     * <p>结果页需要的是后者 —— 这是项目最初的核心需求之一。
     *
     * <p><b>返回顺序</b>：按各词在文本中<b>首次出现的位置</b>（即 {@code char_start} 升序）
     * 排列，也就是阅读顺序。
     * 【纠正】此处曾写作「保持首次标记的顺序」—— 与实现不符：
     * 底层 {@link #listMarksBySession} 是按 {@code char_start} 排序的，
     * 与用户点击的先后无关。现予更正。
     *
     * @param userId    用户 id
     * @param sessionId 会话 id
     * @return 去重后的标记词摘要，按文本位置排序；没有任何标记时返回空列表
     */
    List<MarkedWordSummary> summarizeSessionMarks(Long userId, Long sessionId);
}
