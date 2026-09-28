package org.example.englishquestionbank.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.englishquestionbank.api.ApiResponse;
import org.example.englishquestionbank.api.CurrentUserProvider;
import org.example.englishquestionbank.api.dto.response.PageResponse;
import org.example.englishquestionbank.api.dto.response.VocabItemResponse;
import org.example.englishquestionbank.api.dto.response.WrongQuestionResponse;
import org.example.englishquestionbank.service.AnswerService;
import org.example.englishquestionbank.service.WordMarkService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「我的」数据接口 —— 生词本、错题本。
 *
 * <h2>URL 为什么是 {@code /api/me/...} 而不是 {@code /api/users/{userId}/...}</h2>
 * 这是 {@code docs/api-layer.md} 红线 3 的直接体现：<b>{@code userId} 绝不出现在 URL 里</b>。
 * {@code /api/me/vocabulary} 的语义就是「当前登录用户的生词本」——
 * 身份来自令牌，客户端无论怎么改 URL 都拿不到别人的数据。
 * 若写成 {@code /api/users/{userId}/vocabulary}，改一个数字就能翻别人的生词本。
 *
 * <h2>分页参数为什么不用 Bean Validation 注解</h2>
 * 在 {@code @RequestParam} 上加 {@code @Min} 需要类上标 {@code @Validated}，
 * 而校验失败抛的是 {@code ConstraintViolationException}，它<strong>不是</strong>
 * {@code MethodArgumentNotValidException} —— 会被 {@code GlobalExceptionHandler}
 * 的兜底分支吞成 <b>500</b>，而不是 400。
 * 为避开这个陷阱，分页参数一律交给 Service 层归一化
 * （见 {@code support.Paging}）：{@code page < 1} 按 1、{@code size < 1} 按 20、
 * {@code size > 100} 按 100，非法值被静默修正而不是报错。
 * 对分页参数来说，「修正」比「报错」体验更好，也不会打开「返回全表」的口子。
 */
@Slf4j
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
@Tag(name = "我的", description = "当前用户的生词本、错题本")
public class MeController {

    private final WordMarkService wordMarkService;
    private final AnswerService answerService;
    private final CurrentUserProvider currentUser;

    /**
     * 生词本：当前用户标记过的词，按原形去重，带释义与音标。
     *
     * <p>这是项目最初需求「做完题后能看到标记过的单词及其翻译」的<strong>长期形态</strong> ——
     * 结果页看的是「这一次标了什么」，这里看的是「我一共积累了多少」。
     */
    @GetMapping("/vocabulary")
    @Operation(summary = "我的生词本（分页）",
            description = "按最后标记时间倒序。词条带中文释义、音标、考纲标签、累计标记次数")
    public ResponseEntity<ApiResponse<PageResponse<VocabItemResponse>>> vocabulary(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {

        Long userId = currentUser.currentUserId();
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(
                wordMarkService.listVocabularyPage(userId, page, size),
                VocabItemResponse::from)));
    }

    /**
     * 错题本：当前用户做错的题，带正确答案与解析。
     *
     * <p><b>只含已交卷的练习</b> —— 练习进行中的错题不会出现在这里，
     * 否则它就成了「提前看答案」的入口（红线 2）。
     */
    @GetMapping("/wrong-questions")
    @Operation(summary = "我的错题本（分页）",
            description = "按作答时间倒序。只包含**已交卷**练习中的错题；"
                    + "翻译题在评分完成前 is_correct 为 null，不会进错题本")
    public ResponseEntity<ApiResponse<PageResponse<WrongQuestionResponse>>> wrongQuestions(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {

        Long userId = currentUser.currentUserId();
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(
                answerService.listWrongPage(userId, page, size),
                WrongQuestionResponse::from)));
    }
}
