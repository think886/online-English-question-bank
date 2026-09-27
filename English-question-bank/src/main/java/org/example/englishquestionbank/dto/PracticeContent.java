package org.example.englishquestionbank.dto;

import org.example.englishquestionbank.entity.Passage;
import org.example.englishquestionbank.entity.PracticeSession;
import org.example.englishquestionbank.entity.Question;
import org.example.englishquestionbank.entity.QuestionOption;

import java.util.List;
import java.util.Map;

/**
 * 一次练习的完整内容 —— 「开始练习」与「结果页」都要用它。
 *
 * <p><b>为什么需要这个聚合对象</b>：Controller 需要文章、题目、选项三类数据，
 * 若让它自己拼 mappers，就把数据访问逻辑泄漏到了 Web 层。
 * 由 Service 一次性组装好，Web 层只负责转成响应 DTO。
 *
 * @param session          会话
 * @param passage          文章；<b>翻译题会话为 {@code null}</b>
 * @param questions        题目，<b>按会话快照的顺序</b>（不是按文章当前顺序）
 * @param optionsByQuestion 每道题的选项，按 {@code seq} 排序；翻译题在 map 中可能不存在
 */
public record PracticeContent(
        PracticeSession session,
        Passage passage,
        List<Question> questions,
        Map<Long, List<QuestionOption>> optionsByQuestion
) {
}
