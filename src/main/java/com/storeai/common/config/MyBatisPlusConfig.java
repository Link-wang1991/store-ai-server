package com.storeai.common.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.storeai.**.repository")
public class MyBatisPlusConfig {
}
