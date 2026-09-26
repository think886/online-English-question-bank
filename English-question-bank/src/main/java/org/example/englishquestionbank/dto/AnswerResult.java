package org.example.englishquestionbank.dto;

/**
 * 提交答案的返回结果。
 *
 * <p>返回判分结论是为了让前端<b>立即得到反馈</b>（对吗、得了多少分、
 * 翻译题是否还在评分中），不必再查一次作答记录。
 *
 * @param isCorrect      阅读题 0/1；翻译题为 {@code null}（评分完成前无法判定）
 * @param score          实得分；翻译题在评分前为 {@code null}
 * @param maxScore       本题满分
 * @param gradingStatus  判分状态：阅读题为 {@code DONE}，翻译题为 {@code PENDING}
 * @param firstSubmit    {@code true} 表示这是本题第一次作答；{@code false} 表示覆盖了上次答案
 */
public record AnswerResult(
        Integer isCorrect,
        Integer score,
        Integer maxScore,
        String gradingStatus,
        boolean firstSubmit
) {
}
