package com.ecommerce.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.product.entity.Category;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface CategoryMapper extends BaseMapper<Category> {

    /**
     * MyBatis-Plus 默认提供 selectList，这里直接使用 Wrappers 查询
     * @see com.baomidou.mybatisplus.core.conditions.query.QueryWrapper
     */
}
