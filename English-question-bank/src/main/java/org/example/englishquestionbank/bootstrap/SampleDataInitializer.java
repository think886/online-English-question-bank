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
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * 演示用户的初始密码（开发期用）。
     *
     * <p>⚠️ 这是<b>公开写在源码里的弱口令</b>，只为了让开发机上能立刻登录一次。
     * 对外部署前必须删掉演示用户，或强制改密（见 {@code docs/backlog.md} B-07）。
     */
    public static final String DEMO_PASSWORD = "demo123";

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

    /**
     * 开发期便利开关，见 {@code application.yml} 的 {@code app.dev-mode}。
     *
     * <p>这里用<b>字段注入</b>而不是构造器注入：本类用了
     * {@code @RequiredArgsConstructor}，而 Lombok <b>默认不会把 {@code @Value}
     * 复制到生成的构造器参数上</b>（需要额外配 {@code lombok.copyableAnnotations}）。
     * 为一个标量配置项去改 Lombok 全局配置不划算，字段注入足够。
     *
     * <p>兜底默认值是 {@code false} —— 配置缺失时行为是安全的那种。
     */
    @Value("${app.dev-mode:false}")
    private boolean devMode;

    @Override
    public void run(String... args) {
        warnIfDevMode();
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

    /**
     * 开发期开关打开时，在启动日志里<strong>大声告警</strong>。
     *
     * <p><b>为什么值得专门写一段</b>：这个开关打开时，任何人都能自称是任意用户。
     * 它默认就是开的（因为还没有注册接口，见 B-17），所以很容易一路带到线上而没人注意。
     * 一行醒目的日志是最低成本的提醒 —— 尤其是将来换人接手或自己隔几周回来时。
     */
    private void warnIfDevMode() {
        if (devMode) {
            log.warn("==============================================================");
            log.warn("⚠️  开发期便利功能已启用（app.dev-mode=true）");
            log.warn("    ① 请求头 X-Debug-User-Id 可直接冒充任意用户（见 CurrentUserProvider）");
            log.warn("    ② 演示账号 {} 的弱口令 {} 会被补设", DEMO_USERNAME, DEMO_PASSWORD);
            log.warn("    ⚠️ 这些都【不可用于对外环境】。上线前把 application.yml 里");
            log.warn("       app.dev-mode 改为 false（见 docs/backlog.md B-07）");
            log.warn("==============================================================");
        } else {
            log.info("开发期便利功能已关闭（app.dev-mode=false）：只认 Authorization: Bearer 令牌");
        }
    }

    /**
     * 确保存在演示用户，并且它<b>一定有一个可用的密码</b>。
     *
     * <p><b>为什么要单独补密码</b>：本项目的演示用户是先于登录功能存在的，
     * 当时 {@code password_hash} 保持为空。如果只在「创建用户」时设密码，
     * 那么开发机上那个已经存在的 demo 用户永远没有密码，
     * 登录接口会一直返回「用户名或密码错误」，而现象很难联想到「密码从来没设过」。
     * 所以这里对「已存在」的分支也要检查一次。
     *
     * <p><b>为什么不覆盖已有密码</b>：只修补空值。否则每次重启都会把用户
     * 自己改过的密码重置回 {@link #DEMO_PASSWORD}，属于严重的数据破坏。
     */
    private SysUser ensureDemoUser() {
        SysUser existing = sysUserService.findByUsername(DEMO_USERNAME);
        if (existing == null) {
            existing = sysUserService.register(DEMO_USERNAME, "演示用户");
            log.info("示例数据：已创建演示用户 '{}' id={}", DEMO_USERNAME, existing.getId());
        } else {
            log.info("示例数据：演示用户 '{}' 已存在 id={}", DEMO_USERNAME, existing.getId());
        }

        if (existing.getPasswordHash() == null || existing.getPasswordHash().isBlank()) {
            if (!devMode) {
                // 关闭状态下【不】补设弱口令。演示账号于是无法登录 —— 这是正确行为：
                // 要登录就得先有注册接口（backlog B-17），而不是靠一个写死在源码里的密码。
                log.info("示例数据：演示用户 '{}' 没有密码，且 app.dev-mode=false，因此不补设初始密码", DEMO_USERNAME);
            } else {
                sysUserService.setPassword(existing.getId(), DEMO_PASSWORD);
                log.info("示例数据：演示用户 '{}' 原本没有密码，已补设初始密码（开发期弱口令，见 backlog B-07）",
                        DEMO_USERNAME);
            }
        }
        return existing;
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
