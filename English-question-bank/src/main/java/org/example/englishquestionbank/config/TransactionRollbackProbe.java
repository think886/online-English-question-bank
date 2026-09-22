package org.example.englishquestionbank.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.englishquestionbank.dto.MarkCommand;
import org.example.englishquestionbank.service.WordMarkService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事务回滚探针（开发期辅助）。
 *
 * <p><b>存在的唯一理由</b>：验证「事务回滚」这件事真的会发生。
 * 在它出现之前，我们只验证过「成功时一起提交」，从未验证过
 * 「中途失败 → 已写入的部分被撤销」。
 *
 * <p><b>为什么写成独立组件而不是直接在验证器里加 try-catch</b>：
 * 要让回滚发生，抛异常的代码必须处在<b>事务边界之内</b>。
 * 如果把「调用 markWord」和「抛异常」都放在验证器的同一个方法里，
 * 而验证器方法本身没有 {@code @Transactional}，
 * 那么 {@code markWord} 会自己开一个事务并<b>正常提交</b>，
 * 之后抛异常也回滚不了已经提交的数据 —— 这个测试就成了假的。
 *
 * <p><b>因此本类的方法自己带 {@code @Transactional}</b>：
 * <ol>
 *   <li>调用 {@code wordMarkService.markWord(...)}（它默认传播行为是 REQUIRED，会加入本事务）</li>
 *   <li>随后抛异常</li>
 *   <li>整个事务回滚 —— 若标记确实被撤销，说明事务配置有效</li>
 * </ol>
 * 这个场景本身也是真实的：上层编排多个 Service 调用时失败，就应该整体回滚。
 *
 * <p>属于开发期辅助类，事务行为确认后可以删除。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionRollbackProbe {

    private final WordMarkService wordMarkService;

    /**
     * 先标记一个单词，然后立刻抛异常。
     *
     * <p>调用方应当观察到：抛出 {@link IllegalStateException}，且这次标记<b>没有留在库里</b>。
     *
     * @param cmd 要标记的内容
     */
    @Transactional(rollbackFor = Exception.class)
    public void markThenFail(MarkCommand cmd) {
        wordMarkService.markWord(cmd);
        log.debug("回滚探针：标记已写入，即将抛异常触发回滚");
        throw new IllegalStateException("回滚探针故意抛出的异常（不是缺陷，是测试手段）");
    }
}
