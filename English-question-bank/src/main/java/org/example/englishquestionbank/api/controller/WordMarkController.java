package org.example.englishquestionbank.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.englishquestionbank.api.ApiResponse;
import org.example.englishquestionbank.api.CurrentUserProvider;
import org.example.englishquestionbank.api.dto.request.MarkWordRequest;
import org.example.englishquestionbank.api.dto.response.MarkedWordResponse;
import org.example.englishquestionbank.api.dto.response.MarkResultResponse;
import org.example.englishquestionbank.dto.MarkCommand;
import org.example.englishquestionbank.dto.MarkResult;
import org.example.englishquestionbank.entity.UserWordMark;
import org.example.englishquestionbank.service.WordMarkService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 划词标记接口 —— 本系统最核心的功能入口。
 *
 * <p><b>为什么挂在 {@code /api/practices/{sessionId}/marks} 下</b>：
 * 标记天然属于某一次练习（会话），这样 URL 本身就表达了归属关系，
 * 也让服务端能直接拿 {@code sessionId} 做归属校验，不必在请求体里传 userId。
 *
 * <p><b>请求体里没有 {@code userId}，也没有 {@code passageId}</b>：
 * <ul>
 *   <li>{@code userId} 来自请求上下文（{@link CurrentUserProvider}）</li>
 *   <li>{@code passageId} 由服务端从会话推导 —— 前端在文章正文上划词时
 *       并不知道文章在库里的主键，也不该知道</li>
 * </ul>
 *
 * <p><b>本接口不返回正确答案</b>，因此练习进行中也可以随时调用。
 */
@Slf4j
@RestController
@RequestMapping("/api/practices/{sessionId}/marks")
@RequiredArgsConstructor
@Tag(name = "划词标记", description = "标记生词、查看已标记的词")
public class WordMarkController {

    private final WordMarkService wordMarkService;
    private final CurrentUserProvider currentUser;

    /**
     * 标记一个单词。重复标记同一位置是空操作，返回 {@code duplicated=true}。
     *
     * <p>用 200 而不是 201：这里有两种结果 —— 新建和「已存在」。
     * 统一返回 200 并让客户端看 {@code duplicated} 字段，比按结果切换状态码更好处理
     * （客户端只需判断一次 HTTP 状态）。
     */
    @PostMapping
    @Operation(summary = "标记一个单词",
            description = "返回归一化原形与中文释义，供前端就地弹出。"
                    + "同一位置重复标记不新增记录、不重复计数")
    public ResponseEntity<ApiResponse<MarkResultResponse>> mark(
            @PathVariable Long sessionId,
            @Valid @RequestBody MarkWordRequest request) {

        Long userId = currentUser.currentUserId();

        MarkResult result = wordMarkService.markWord(new MarkCommand(
                userId,
                sessionId,
                request.questionId(),
                null,                  // passageId 由服务端从会话推导，不信任前端
                request.sourceField(),
                request.surfaceForm(),
                request.sentence(),
                request.charStart(),
                request.charEnd()));

        return ResponseEntity.ok(ApiResponse.ok(MarkResultResponse.from(result)));
    }

    /**
     * 查看本次练习已标记的词 —— <b>每一次划词事件一行</b>，按文本位置升序。
     *
     * <p>与结果页的 {@code markedWords} 不同：那个是<b>按原形去重</b>的汇总，
     * 这个保留位置信息（在哪一题、第几个字符），前端据此把标记渲染回原文上。
     * 练习进行中就要用这个接口把已有标记画出来，因此不能等到交卷。
     */
    @GetMapping
    @Operation(summary = "查看本次练习的标记（按位置，未去重）",
            description = "按 charStart 升序返回每一次划词，含题目 id 与偏移量，"
                    + "用于把标记渲染回原文。去重后的生词汇总在结果页接口里")
    public ResponseEntity<ApiResponse<List<MarkedWordResponse>>> list(
            @PathVariable Long sessionId) {

        Long userId = currentUser.currentUserId();
        List<UserWordMark> marks = wordMarkService.listMarksBySession(userId, sessionId);
        List<MarkedWordResponse> body = marks.stream().map(MarkedWordResponse::from).toList();

        // 显式写 200：本接口必然有响应体（可能为空数组），不用 204
        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.ok(body));
    }
}
