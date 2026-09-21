package org.example.englishquestionbank.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.englishquestionbank.entity.SysUser;

/**
 * {@code sys_user} 表的 Mapper。
 *
 * <p>继承 {@link BaseMapper} 即获得全部单表 CRUD（insert / selectById / selectList /
 * updateById / deleteById / selectCount 等），无需手写 SQL。
 * 复杂条件或跨表查询在业务需要时再补 {@code @Select} 方法。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
