package org.example.englishquestionbank.bootstrap;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.englishquestionbank.entity.Passage;
import org.example.englishquestionbank.entity.Question;
import org.example.englishquestionbank.entity.QuestionOption;
import org.example.englishquestionbank.entity.SysUser;
import org.example.englishquestionbank.mapper.PassageMapper;
import org.example.englishquestionbank.mapper.QuestionMapper;
import org.example.englishquestionbank.mapper.QuestionOptionMapper;
import org.example.englishquestionbank.service.SysUserService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 示例数据初始化器（开发期）。
 *
 * <p><b>为什么需要它</b>：{@code passage} / {@code question} / {@code question_option}
 * 三张表是空的，没有文章和题目就无法开始练习，也就无法验证
 * {@code PracticeSessionService.startSession} 的跨表事务。
 * 真题抽取排在项目最后（见 {@code docs/backlog.md} 的 B-06），所以先用少量
 * <b>自编示例数据</b>把流程跑通。
 *
 * <p><b>幂等性</b>：以 {@code passage.source = "示例数据（非真题）"} 作为识别标记，
 * 已存在就整体跳过，重复启动不会产生重复数据。
 *
 * <p><b>内容声明</b>：这里的文章与题目是<strong>为跑通流程自编的，不是考试真题</strong>，
 * 不涉及版权问题。真实题库抽取时应当替换掉。
 *
 * <p><b>为什么用循环单条插入而不是 XML 批量</b>：这是只执行一次的开发工具，
 * 总共 1 篇文章 + 2 道题 + 8 个选项，而且每次都只在空库时跑。
 * 项目规范里「批量插入写 XML」针对的是**生产路径**上会随业务数据增长的操作
 * （如 {@code SessionQuestionMapper.insertBatch}）。这里用循环更直白。
 */
@Slf4j
@Component
@Order(1)   // 必须早于 WritePathVerifier 执行
@RequiredArgsConstructor
public class SampleDataInitializer implements CommandLineRunner {

    /** 识别标记：用 source 字段判断示例数据是否已经存在。 */
    public static final String SAMPLE_SOURCE = "示例数据（非真题）";

    /** 演示用户的登录名。 */
    public static final String DEMO_USERNAME = "demo";

    private static final String PASSAGE_TITLE = "Community Gardens";
    private static final String PASSAGE_CONTENT = """
            Many cities are turning unused land into community gardens. These small plots,
            often squeezed between buildings or beside railway lines, give residents a chance
            to grow their own vegetables.

            Supporters argue that the benefits go beyond food. Gardening brings neighbours
            together, encourages gentle exercise, and helps children understand where their
            meals actually come from.

            Critics point out that the harvest is rarely enough to feed a family, and that
            the waiting list for a plot can be years long. Even so, demand continues to rise.
            Some local governments now treat community gardens as a normal part of urban
            planning rather than a temporary fashion.""";

    /** 示例翻译题：待翻译的中文原文（汉译英，符合四六级翻译题型）。 */
    private static final String TRANSLATION_SOURCE = """
            随着城市的发展，越来越多的人选择骑自行车出行。这不仅有助于减少交通拥堵，\
            还能改善空气质量，让城市变得更加宜居。""";

    /** 示例翻译题的参考译文。 */
    private static final String TRANSLATION_REFERENCE =
            "With the development of cities, more and more people choose to travel by bicycle. "
                    + "This not only helps reduce traffic congestion, but also improves air quality, "
                    + "making cities more livable.";

    private final SysUserService sysUserService;
    private final PassageMapper passageMapper;
    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper questionOptionMapper;

    @Override
    public void run(String... args) {
        try {
            SysUser demoUser = ensureDemoUser();
            if (demoUser == null) {
                return;
            }
            ensureSamplePassage();
            ensureSampleTranslationQuestion();
        } catch (Throwable t) {
            log.error("示例数据初始化失败：{} - {}", t.getClass().getName(), t.getMessage());
        }
    }

    /** 确保存在演示用户。返回 null 表示创建失败（已记录日志）。 */
    private SysUser ensureDemoUser() {
        SysUser existing = sysUserService.findByUsername(DEMO_USERNAME);
        if (existing != null) {
            log.info("示例数据：演示用户 '{}' 已存在 id={}", DEMO_USERNAME, existing.getId());
            return existing;
        }
        SysUser created = sysUserService.register(DEMO_USERNAME, "演示用户");
        log.info("示例数据：已创建演示用户 '{}' id={}", DEMO_USERNAME, created.getId());
        return created;
    }

    /** 确保存在示例文章及其题目。已存在则整体跳过。 */
    private void ensureSamplePassage() {
        Long existingId = findSamplePassageId();
        if (existingId != null) {
            log.info("示例数据：示例文章已存在 id={}，跳过初始化", existingId);
            return;
        }

        // ---- 文章 ----
        Passage passage = new Passage();
        passage.setTitle(PASSAGE_TITLE);
        passage.setContent(PASSAGE_CONTENT);
        passage.setSource(SAMPLE_SOURCE);
        passage.setCategory("社会");
        passage.setDifficulty(3);
        passage.setWordCount(countWords(PASSAGE_CONTENT));
        passage.setStatus(1);
        passageMapper.insert(passage);

        // ---- 两道阅读题 ----
        Question q1 = insertReadingQuestion(passage.getId(), 1,
                "What is the main idea of the passage?");
        insertOptions(q1.getId(),
                "Community gardens are only a short-lived fashion.",
                "Community gardens are increasingly treated as part of city planning.",
                "Community gardens can fully replace supermarkets.",
                "Community gardens are designed mainly for children.",
                "B");

        Question q2 = insertReadingQuestion(passage.getId(), 2,
                "According to critics, what is a limitation of community gardens?");
        insertOptions(q2.getId(),
                "They cost too much for local governments to maintain.",
                "They attract far more visitors than the neighbourhood can handle.",
                "The harvest is rarely enough to feed a family.",
                "They damage the soil around railway lines.",
                "C");

        log.info("示例数据：已创建文章 '{}' (id={})，含 {} 道题、{} 个选项",
                PASSAGE_TITLE, passage.getId(), 2, 8);
    }

    /** 按识别标记查示例文章的 id；不存在返回 null。 */
    private Long findSamplePassageId() {
        // 单表等值查询 → 简单操作 → 用构造器（项目规范）
        Passage found = passageMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Passage>lambdaQuery()
                        .eq(Passage::getSource, SAMPLE_SOURCE)
                        .last("LIMIT 1"));
        return found == null ? null : found.getId();
    }

    /**
     * 确保存在一道示例翻译题。
     *
     * <p><b>幂等判断为什么要独立于文章</b>：翻译题的 {@code passage_id} 为 NULL，
     * 与示例文章没有关联。如果把它挂在「文章是否已存在」的判断之下，
     * 那么对于已经建好示例文章的库（比如开发机上已经存在的库），
     * 这段代码会被整体跳过，翻译题永远补不上。
     *
     * <p>这里用「库里是否已存在任意翻译题」作为判断依据 ——
     * 既幂等，又不会覆盖将来录入的真实翻译题。
     */
    private void ensureSampleTranslationQuestion() {
        long existing = questionMapper.selectCount(
                Wrappers.<Question>lambdaQuery()
                        .eq(Question::getQuestionType, "TRANSLATION"));
        if (existing > 0) {
            log.info("示例数据：库中已有 {} 道翻译题，跳过示例翻译题初始化", existing);
            return;
        }

        Question q = new Question();
        q.setQuestionType("TRANSLATION");
        q.setPassageId(null);          // 翻译题不属于任何文章 —— 这正是它需要独立组卷入口的原因
        q.setStem("请将下面这段中文翻译成英文。");
        q.setSourceText(TRANSLATION_SOURCE);
        q.setReferenceAnswer(TRANSLATION_REFERENCE);
        q.setDifficulty(3);
        q.setScore(15);                // 四六级翻译占 15%，与阅读题的 1 分区分开
        q.setSeq(1);
        q.setStatus(1);
        questionMapper.insert(q);

        log.info("示例数据：已创建示例翻译题 id={}", q.getId());
    }

    private Question insertReadingQuestion(Long passageId, int seq, String stem) {
        Question q = new Question();
        q.setQuestionType("READING");
        q.setPassageId(passageId);
        q.setStem(stem);
        q.setDifficulty(3);
        q.setScore(1);
        q.setSeq(seq);
        q.setStatus(1);
        questionMapper.insert(q);
        return q;
    }

    /** 写入 A/B/C/D 四个选项，correctKey 指定的那个标记为正确。 */
    private void insertOptions(Long questionId, String a, String b, String c, String d,
                               String correctKey) {
        String[] texts = {a, b, c, d};
        String[] keys = {"A", "B", "C", "D"};
        for (int i = 0; i < keys.length; i++) {
            QuestionOption option = new QuestionOption();
            option.setQuestionId(questionId);
            option.setOptionKey(keys[i]);
            option.setContent(texts[i]);
            option.setIsCorrect(keys[i].equals(correctKey) ? 1 : 0);
            option.setSeq(i);
            questionOptionMapper.insert(option);
        }
    }

    /** 粗略统计词数，仅用于填充 word_count 字段。 */
    private static int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }
}
