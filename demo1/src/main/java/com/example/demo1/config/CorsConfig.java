package com.example.demo1.config; // 确保包名正确

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableWebMvc // 保留这个注解
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        System.out.println("Initializing CORS Configuration..."); // 调试日志
        registry.addMapping("/**")
                .allowedOrigins("http://localhost:5173") // **再次确认这里是具体地址！**
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // 包含 OPTIONS
                .allowedHeaders("*")
                .allowCredentials(true) // **与前端 SSE 对应**
                .maxAge(3600);
        System.out.println("CORS Configuration Applied for origin: http://localhost:5173");
    }
}