package org.example.englishquestionbank.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.englishquestionbank.entity.UserToken;

/**
 * 登录令牌 Mapper。
 *
 * <p><b>本接口没有任何自定义方法，也没有配套的 XML</b> —— 这是刻意的。
 * 它需要的三个操作全部是<strong>单表条件查询/更新</strong>，属于项目规范里的
 * 「简单操作」，应当用 MyBatis-Plus 构造器在 Service 层内联表达：
 * <ul>
 *   <li>按 token_hash 查有效令牌 → {@code selectOne(lambdaQuery()...)}</li>
 *   <li>按 id 更新 last_used_at → {@code updateById(...)}</li>
 *   <li>按 token_hash / user_id 撤销 → {@code update(null, lambdaUpdate()...)}</li>
 * </ul>
 * 为空而写一个 XML 只会制造「有个文件但没人看懂为什么存在」的噪音。
 *
 * <p>一旦将来出现「取前 N 条 + JOIN + 动态条件」（例如「列出某用户所有登录设备」），
 * 那才是复杂操作，届时应新建 {@code UserTokenMapper.xml}。
 */
@Mapper
public interface UserTokenMapper extends BaseMapper<UserToken> {
}
