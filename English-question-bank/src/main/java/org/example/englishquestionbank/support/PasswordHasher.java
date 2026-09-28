package org.example.englishquestionbank.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 密码哈希工具 —— 对 Spring Security 的 {@code BCryptPasswordEncoder} 的一层薄封装。
 *
 * <h2>为什么需要这一层</h2>
 * 直接 {@code new BCryptPasswordEncoder()} 会在两个地方各造一个实例
 * （{@code SysUserService} 设置密码、{@code AuthService} 校验密码）。
 * 虽然它无状态、重复创建无害，但把「用什么算法、强度多少」的决策散落在多处，
 * 将来换算法时极易只改一处，造成「新密码用新算法、旧密码验不过」的故障。
 * 收敛到一个组件，算法决策只有一个出口。
 *
 * <h2>为什么只引 spring-security-crypto，不引 spring-boot-starter-security</h2>
 * starter 会连带装上 Spring Security 的过滤器链，并<strong>默认锁死所有端点</strong>，
 * 需要额外写一大段配置才放行。本项目此刻只需要「哈希与校验密码」这一个能力，
 * 引整套框架是杀鸡用牛刀，还会把现有 7 个接口全部打进 401。
 * {@code spring-security-crypto} 是一个独立的工具 jar，不含任何自动配置。
 *
 * <h2>BCrypt 的两个特性</h2>
 * <ul>
 *   <li><b>自带随机盐</b>：同一个密码每次哈希结果都不同，无需另存盐字段
 *       （这也是 {@code sys_user.password_hash} 只有一列的原因）</li>
 *   <li><b>故意慢</b>：默认强度 10 约 50~100ms 一次，正是用来拖垮离线爆破。
 *       因此它<b>只适合低熵口令</b>；令牌那种高熵随机串必须用快速哈希，见 {@link #sha256Hex}</li>
 * </ul>
 */
@Slf4j
@Component
public class PasswordHasher {

    /**
     * BCrypt 强度（cost factor），2^10 = 1024 轮。
     *
     * <p>不调大：默认 10 已是业界通行值，调到 12 会让每次登录多花约 200ms，
     * 而收益在当前规模下微乎其微。真要提高，应先做压测再定。
     */
    private static final int BCRYPT_STRENGTH = 10;

    /**
     * 一个固定的、无对应密码的 BCrypt 哈希，用于「用户不存在时也照样做一次校验」。
     *
     * <p><b>为什么需要它</b>：如果用户名不存在就立刻返回，登录接口的响应时间会
     * 明显短于「用户存在但密码错」的情况，攻击者据此可以<strong>枚举出哪些用户名真实存在</strong>
     * （时序攻击）。用一个等价的假哈希走完同样的计算量，把两条路径的耗时拉平。
     *
     * <p>这个值由 {@code BCryptPasswordEncoder.encode("dummy")} 生成，仅用于消耗时间，
     * 永远不会匹配任何真实输入。
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH);

    /** 哈希明文密码。返回的 60 字符串自带盐，直接存 {@code sys_user.password_hash}。 */
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /**
     * 校验明文密码是否匹配哈希。
     *
     * @param rawPassword  用户提交的明文密码
     * @param passwordHash 库里的 BCrypt 哈希
     * @return 匹配返回 true；{@code passwordHash} 为空（如游客账号）时返回 false
     */
    public boolean matches(String rawPassword, String passwordHash) {
        if (rawPassword == null || passwordHash == null || passwordHash.isBlank()) {
            return false;
        }
        return encoder.matches(rawPassword, passwordHash);
    }

    /**
     * 消耗一次与真实校验等量的计算量，然后必然返回 false。
     *
     * <p>用户不存在或未设密码时调用它，用于抵抗时序攻击（见 {@link #DUMMY_HASH}）。
     */
    public boolean burnTimeAndFail(String rawPassword) {
        return encoder.matches(rawPassword == null ? "" : rawPassword, DUMMY_HASH);
    }

    /**
     * 计算 SHA-256 的十六进制小写串（64 字符），用于登录令牌的入库形式。
     *
     * <p><b>为什么令牌用 SHA-256 而不是 BCrypt</b>：令牌是高熵随机串（32 字节），
     * 不存在被暴力破解的可能，无需「故意慢」；而每个请求都要校验它，
     * 用 BCrypt 会平白给每次 API 调用加上几十毫秒。
     */
    public String sha256Hex(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须实现的算法，走不到这里；真走到了说明运行环境损坏
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
