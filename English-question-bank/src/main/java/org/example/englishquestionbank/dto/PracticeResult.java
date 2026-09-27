package org.example.englishquestionbank.dto;

import org.example.englishquestionbank.entity.AnswerRecord;
import org.example.englishquestionbank.entity.UserWordMark;

import java.util.List;
import java.util.Map;

/**
 * 一次练习的<b>结果页数据</b> —— 项目最初需求「做完题后能看到标记过的单词及其翻译、
 * 以及自己做过的题和题中标记过的词」在服务层的落地形态。
 *
 * <p><b>为什么直接复用 {@link PracticeContent} 而不是把字段摊平</b>：
 * 结果页要展示的文章、题目、选项，与「开始练习」拿到的完全是同一批数据，
 * 读取逻辑（按会话快照顺序取题）也必须是同一条路径。
 * 摊平字段等于把 {@code PracticeContent} 抄一遍，两边迟早会漂移。
 *
 * <p><b>只在会话已交卷时才会构造出来</b>（见
 * {@code PracticeSessionServiceImpl#loadResult}）—— 否则等于提前泄题。
 *
 * @param content          文章 + 题目 + 选项（与会话开始时同一读取路径）
 * @param answerByQuestion 每道题的作答记录，key 为题目 id；
 *                         <b>没作答过的题不会出现在 map 里</b>（不是 null 值）
 * @param marksByQuestion  每道题上标记过的单词，key 为题目 id。
 *                         在<b>文章正文</b>上划的词 {@code questionId} 为 NULL，
 *                         无法归到某一题，因此不会出现在这里 ——
 *                         但一定会出现在 {@link #markedWords} 里
 * @param markedWords      本次会话标记过的词，<b>已按原形去重并带释义</b>
 */
public record PracticeResult(
        PracticeContent content,
        Map<Long, AnswerRecord> answerByQuestion,
        Map<Long, List<UserWordMark>> marksByQuestion,
        List<MarkedWordSummary> markedWords
) {
}
