package org.example.englishquestionbank.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.englishquestionbank.api.ApiResponse;
import org.example.englishquestionbank.api.CurrentUserProvider;
import org.example.englishquestionbank.api.dto.request.StartReadingRequest;
import org.example.englishquestionbank.api.dto.request.StartTranslationRequest;
import org.example.englishquestionbank.api.dto.request.SubmitAnswerRequest;
import org.example.englishquestionbank.api.dto.response.AnswerResultResponse;
import org.example.englishquestionbank.api.dto.response.PageResponse;
import org.example.englishquestionbank.api.dto.response.PracticeHistoryResponse;
import org.example.englishquestionbank.api.dto.response.PracticeResultResponse;
import org.example.englishquestionbank.api.dto.response.PracticeStartResponse;
import org.example.englishquestionbank.api.dto.response.SessionSummaryResponse;
import org.example.englishquestionbank.dto.AnswerResult;
import org.example.englishquestionbank.dto.PracticeContent;
import org.example.englishquestionbank.dto.PracticeResult;
import org.example.englishquestionbank.entity.PracticeSession;
import org.example.englishquestionbank.service.AnswerService;
import org.example.englishquestionbank.service.PracticeSessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 练习会话接口。
 *
 * <p><b>分层约定</b>：Controller 只做三件事 ——
 * 取身份、调 Service、把结果转成响应 DTO。
 * 它<b>不使用 Mapper</b>，也不包含业务规则。
 *
 * <p><b>身份来源</b>：{@link CurrentUserProvider}，URL 与请求体里都没有 {@code userId}。
 * 当前是临时占位实现（不安全），见 {@code docs/api-layer.md} 第四节。
 */
@Slf4j
@RestController
@RequestMapping("/api/practices")
@RequiredArgsConstructor
@Tag(name = "练习", description = "开始练习、作答、交卷、结果页")
public class PracticeController {

    private final PracticeSessionService practiceSessionService;
    private final AnswerService answerService;
    private final CurrentUserProvider currentUser;

    @PostMapping("/reading")
    @Operation(summary = "开始一次阅读练习",
            description = "按文章开一次练习，返回文章与全部题目（不含正确答案、解析与全文译文）")
    public ResponseEntity<ApiResponse<PracticeStartResponse>> startReading(
            @Valid @RequestBody StartReadingRequest request) {

        Long userId = currentUser.currentUserId();
        PracticeSession session = practiceSessionService.startSession(userId, request.passageId());

        // 重新读取而不是拼装：loadContent 保证「按会话快照顺序」返回题目，
        // 且「开始练习」与「结果页」共用同一条读取路径，不会出现两处顺序不一致。
        PracticeContent content = practiceSessionService.loadContent(session.getId());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(PracticeStartResponse.from(content)));
    }

    @PostMapping("/translation")
    @Operation(summary = "开始一次翻译练习",
            description = "按题型取翻译题开一次练习，返回待翻译原文（不含参考译文）")
    public ResponseEntity<ApiResponse<PracticeStartResponse>> startTranslation(
            @Valid @RequestBody StartTranslationRequest request) {

        Long userId = currentUser.currentUserId();
        PracticeSession session =
                practiceSessionService.startTranslationSession(userId, request.count());
        PracticeContent content = practiceSessionService.loadContent(session.getId());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(PracticeStartResponse.from(content)));
    }

    /**
     * 提交一道题的答案。
     *
     * <p>用 {@code POST} 而不是 {@code PUT}：客户端并不知道「这次作答」在服务端的资源标识
     * （唯一键是 {@code session_id + question_id}，由服务端决定），
     * 这与 {@code PUT} 的「客户端指定 URI」语义不符。
     *
     * <p>重复提交同一题是<b>覆盖</b>语义（用户改答案），因此天然幂等，
     * 不需要额外的幂等键。
     */
    @PostMapping("/{sessionId}/answers")
    @Operation(summary = "提交一道题的答案",
            description = "阅读题传选项标识（A/B/C/D）自动判分；翻译题传译文，判分状态为 PENDING。"
                    + "同一题重复提交会覆盖上一次作答，会话统计按差值调整")
    public ResponseEntity<ApiResponse<AnswerResultResponse>> submitAnswer(
            @PathVariable Long sessionId,
            @Valid @RequestBody SubmitAnswerRequest request) {

        Long userId = currentUser.currentUserId();
        AnswerResult result = answerService.submitAnswer(
                userId, sessionId, request.questionId(), request.userAnswer());

        return ResponseEntity.ok(ApiResponse.ok(
                AnswerResultResponse.from(request.questionId(), result)));
    }

    /**
     * 交卷。
     *
     * <p>交卷是<b>幂等之外</b>的操作：重复交卷会返回 409，而不是静默成功。
     * 因为第二次交卷会重算耗时（{@code duration_ms}），
     * 让一个已结束的会话继续被改写没有意义。
     */
    @PostMapping("/{sessionId}/finish")
    @Operation(summary = "交卷",
            description = "把会话置为 FINISHED 并按作答记录聚合重算统计；重复交卷返回 409")
    public ResponseEntity<ApiResponse<SessionSummaryResponse>> finish(
            @PathVariable Long sessionId) {

        Long userId = currentUser.currentUserId();
        practiceSessionService.finishSession(userId, sessionId);

        // 交卷后重新读取，返回的就是落库后的最终统计 ——
        // 而不是让 Service 返回一个可能与被篡改过的数据库不一致的内存对象
        PracticeSession session = practiceSessionService.findById(sessionId);
        return ResponseEntity.ok(ApiResponse.ok(SessionSummaryResponse.from(session)));
    }

    /**
     * 结果页 —— 本项目最初需求「做完题后能看到标记过的单词及其翻译、
     * 以及自己做过的题和题中标记过的词」的兑现。
     *
     * <p><b>只有交卷后可用</b>；练习中访问返回 409。
     * 练习中想拿标记词请走 {@code GET /api/practices/{id}/marks}。
     */
    @GetMapping("/{sessionId}/result")
    @Operation(summary = "查看结果页",
            description = "含正确答案、解析、作答记录，以及本次标记过的去重单词及释义。"
                    + "会话未交卷时返回 409")
    public ResponseEntity<ApiResponse<PracticeResultResponse>> result(
            @PathVariable Long sessionId) {

        Long userId = currentUser.currentUserId();
        PracticeResult result = practiceSessionService.loadResult(userId, sessionId);
        return ResponseEntity.ok(ApiResponse.ok(PracticeResultResponse.from(result)));
    }

    /**
     * 练习历史 —— 当前用户做过的全部练习，按开始时间倒序。
     *
     * <p><b>为什么它挂在 {@code /api/practices} 而不是 {@code /api/me/practices}</b>：
     * 返回的就是「练习会话」这一资源本身的集合，归属由令牌决定；
     * 而 {@code /api/me/*} 留给「我的」这类派生视图（生词本、错题本）。
     *
     * <p>URL 里同样没有 {@code userId}（红线 3）。
     *
     * <p><b>包含未交卷的会话</b>：用户中途关掉浏览器是常态，
     * 列表里应能看到它并继续做。前端凭 {@code status} 决定显示「继续」还是「查看结果」。
     */
    @GetMapping
    @Operation(summary = "我的练习历史（分页）",
            description = "按开始时间倒序，含未交卷的会话。带来源文章标题便于辨识")
    public ResponseEntity<ApiResponse<PageResponse<PracticeHistoryResponse>>> history(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {

        Long userId = currentUser.currentUserId();
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(
                practiceSessionService.listHistoryPage(userId, page, size),
                PracticeHistoryResponse::from)));
    }
}
