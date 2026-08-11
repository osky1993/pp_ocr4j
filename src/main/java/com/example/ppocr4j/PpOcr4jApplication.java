package com.example.ppocr4j;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 应用入口：启动 pp-ocr4j Spring Boot 服务。
 *
 * <p>当前项目是单模块引导类，所有控制器、服务和 OCR 配置均在同一应用上下文中创建，
 * 启动参数或环境变量可直接影响运行时模型档次与运行参数。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@org.springframework.scheduling.annotation.EnableScheduling
public class PpOcr4jApplication {

    /**
     * 标准 Spring Boot 启动入口。
     * <ul>
     *   <li>从命令行或 IDE 传入参数会透传给 SpringApplication。</li>
     *   <li>默认端口与配置文件在 {@code application.yml} 中维护。</li>
     * </ul>
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(PpOcr4jApplication.class, args);
    }
}
