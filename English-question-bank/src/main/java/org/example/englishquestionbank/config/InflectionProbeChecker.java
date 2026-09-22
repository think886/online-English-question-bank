package org.example.englishquestionbank.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.englishquestionbank.support.WordFormNormalizer;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 词形归一化器验证（开发期辅助）。
 *
 * <p><b>本类现在测的是真实组件</b>：直接调用 {@link WordFormNormalizer}，
 * 而不是自己维护一份算法副本。早期版本在本类里内联了归一化逻辑，
 * 那样一旦服务侧改动，验证就失去意义 —— 现在两边共用同一份实现。
 *
 * <p><b>为什么保留这些用例</b>：它们是归一化算法的回归测试，
 * 覆盖了三级兜底中容易出问题的情况，特别是「仅靠正向查词会漏掉」的那一类：
 * <pre>
 *   abandoning / abandons —— ECDICT 没有独立词条，只能反查 exchange
 *   gave                  —— 同上（give 的 exchange 里记着 p:gave）
 *   apples                —— 同上
 * </pre>
 *
 * <p>结论确认后本类可以删除；删掉后归一化仍由 {@code WordMarkVerifier} 的
 * 第 1 项（{@code running, → run}）间接覆盖。
 */
@Slf4j
@Component
@Order(30)   // 放在其他验证器之后，避免干扰
@RequiredArgsConstructor
public class InflectionProbeChecker implements CommandLineRunner {

    private final WordFormNormalizer normalizer;

    /** 用例：输入的变形形式 + 期望归一到哪个原形。 */
    private record Case(String surface, String expectedLemma) {
    }

    private static final List<Case> CASES = List.of(
            // 正向命中：本身即原形（exchange 无 0: 段）
            new Case("abandon", "abandon"),
            // 正向命中：exchange 含 0:lemma
            new Case("abandoned", "abandon"),
            new Case("perceived", "perceive"),
            new Case("running", "run"),
            new Case("taken", "take"),
            new Case("children", "child"),
            new Case("better", "good"),
            // 只能靠反查补齐的（ECDICT 没有这些独立词条）
            new Case("abandoning", "abandon"),
            new Case("abandons", "abandon"),
            new Case("gave", "give"),
            new Case("apples", "apple"),
            // 带标点的输入，验证清洗
            new Case("running,", "run"),
            new Case("\"harvest\"", "harvest")
    );

    @Override
    public void run(String... args) {
        log.info("=============== 词形归一化器验证 ===============");
        try {
            int forward = 0;
            int reverse = 0;
            int missed = 0;
            int wrong = 0;
            List<String> failures = new ArrayList<>();

            for (Case c : CASES) {
                WordFormNormalizer.Normalized r = normalizer.normalize(c.surface());
                boolean ok = c.expectedLemma().equalsIgnoreCase(r.form());

                switch (r.path()) {
                    case "正向" -> forward++;
                    case "反查" -> reverse++;
                    default -> missed++;
                }
                if (!ok) {
                    wrong++;
                    failures.add(String.format("%s 期望 %s 实得 %s（%s）",
                            c.surface(), c.expectedLemma(), r.form(), r.path()));
                }
                log.info("  {}", String.format("%-14s → %-12s [%-4s] %s   %s",
                        c.surface(), r.form(), r.path(), ok ? "✅" : "❌", r.evidence()));
            }

            log.info("----------------------------------------------------------");
            log.info("共 {} 项：正向命中 {} / 反查命中 {} / 未命中 {} / 归一出错 {}",
                    CASES.size(), forward, reverse, missed, wrong);
            if (wrong == 0) {
                log.info("=============== ✅ 归一化器行为符合预期 ===============");
            } else {
                log.error("=============== ❌ 归一化器存在 {} 项错误 ===============", wrong);
                for (String f : failures) {
                    log.error("  - {}", f);
                }
            }
        } catch (Throwable t) {
            log.error("归一化验证异常：{} - {}", t.getClass().getName(), t.getMessage());
        }
    }
}
