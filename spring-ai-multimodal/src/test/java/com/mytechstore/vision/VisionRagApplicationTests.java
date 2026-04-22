package com.mytechstore.vision;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {"OPENAI_API_KEY=test-key-placeholder"})
class VisionRagApplicationTests {

    @Test
    void contextLoads() {
    }
}