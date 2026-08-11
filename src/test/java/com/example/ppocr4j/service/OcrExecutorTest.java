package com.example.ppocr4j.service;

import com.example.ppocr4j.config.OcrProperties;
import com.example.ppocr4j.exception.OcrException;
import com.example.ppocr4j.web.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OcrExecutorTest {

    private OcrExecutor executor;

    private OcrExecutor build(int concurrency, long timeoutMs) {
        OcrProperties props = new OcrProperties();
        props.setConcurrency(concurrency);
        props.setTimeoutMs(timeoutMs);
        executor = new OcrExecutor(props);
        return executor;
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.destroy();
        }
    }

    @Test
    void executesAndReturnsResult() {
        var ex = build(1, 1000);
        assertThat(ex.execute(() -> 42)).isEqualTo(42);
    }

    @Test
    void timesOutSlowTask() {
        var ex = build(1, 100);
        assertThatThrownBy(() -> ex.execute(() -> {
            Thread.sleep(5_000);
            return 1;
        }))
                .isInstanceOf(OcrException.class)
                .extracting(e -> ((OcrException) e).getErrorCode())
                .isEqualTo(ErrorCode.TIMEOUT);
    }

    @Test
    void rejectsWhenSaturated() throws Exception {
        var ex = build(1, 5_000);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Object> holderResult = new AtomicReference<>();
        Thread holder = new Thread(() -> holderResult.set(ex.execute(() -> {
            started.countDown();
            release.await();
            return "done";
        })));
        holder.start();
        started.await();
        // 唯一许可被占用，第二个请求应立即被拒
        assertThatThrownBy(() -> ex.execute(() -> "second"))
                .isInstanceOf(OcrException.class)
                .extracting(e -> ((OcrException) e).getErrorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
        release.countDown();
        holder.join(2_000);
        assertThat(holderResult.get()).isEqualTo("done");
    }

    @Test
    void unwrapsOcrExceptionFromTask() {
        var ex = build(1, 1000);
        assertThatThrownBy(() -> ex.execute(() -> {
            throw new OcrException(ErrorCode.IMAGE_DECODE_ERROR, "boom");
        }))
                .isInstanceOf(OcrException.class)
                .extracting(e -> ((OcrException) e).getErrorCode())
                .isEqualTo(ErrorCode.IMAGE_DECODE_ERROR);
    }

    @Test
    void releasesPermitAfterCompletion() {
        var ex = build(1, 1000);
        ex.execute(() -> 1);
        ex.execute(() -> 2);
        assertThat(ex.availablePermits()).isEqualTo(1);
    }
}
