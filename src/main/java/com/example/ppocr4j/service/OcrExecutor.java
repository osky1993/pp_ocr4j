package com.example.ppocr4j.service;

import com.example.ppocr4j.config.OcrProperties;
import com.example.ppocr4j.exception.OcrException;
import com.example.ppocr4j.web.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 识别任务执行闸门：把长 CPU 任务与 Tomcat 请求线程隔离。
 *
 * <ul>
 *   <li>信号量拒绝式限流：超过并发上限立即抛 2001（不排长队拖死上游）</li>
 *   <li>独立线程池执行，请求线程带超时等待：超时抛 2002</li>
 *   <li>ONNX Runtime 的 run 不可中断：超时后 HTTP 侧先返回，
 *       任务线程自然跑完才释放许可（文档已注明该语义）</li>
 * </ul>
 */
@Component
public class OcrExecutor implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OcrExecutor.class);

    private final Semaphore permits;
    private final ExecutorService pool;
    private final long timeoutMs;
    private final int concurrency;

    public OcrExecutor(OcrProperties props) {
        this.concurrency = props.effectiveConcurrency();
        this.timeoutMs = props.getTimeoutMs();
        this.permits = new Semaphore(concurrency);
        AtomicInteger seq = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(concurrency,
                r -> new Thread(r, "ocr-worker-" + seq.incrementAndGet()));
        log.info("OCR 执行器初始化: concurrency={}, timeoutMs={}", concurrency, timeoutMs);
    }

    /**
     * 同步执行（供 HTTP 同步接口）：拿不到许可立即拒绝，等待结果带超时。
     */
    public <T> T execute(Callable<T> task) {
        if (!permits.tryAcquire()) {
            throw new OcrException(ErrorCode.RATE_LIMITED,
                    "识别并发已达上限(" + concurrency + ")，请稍后重试");
        }
        Future<T> future = pool.submit(withMdc(task));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // ONNX run 不可中断，cancel 只是解除 HTTP 等待；工作线程会跑完并释放许可
            future.cancel(true);
            throw new OcrException(ErrorCode.TIMEOUT, "识别超时(" + timeoutMs + "ms)，请压缩图片或降低模型档次");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcrException(ErrorCode.INTERNAL_ERROR, "识别被中断", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof OcrException oe) {
                throw oe;
            }
            throw new OcrException(ErrorCode.INTERNAL_ERROR, "识别执行失败: " + e.getCause().getMessage(), e.getCause());
        }
    }

    /**
     * 异步提交（供任务接口）：拿不到许可同样立即拒绝，返回 Future 由调用方跟踪。
     */
    public <T> Future<T> submit(Callable<T> task) {
        if (!permits.tryAcquire()) {
            throw new OcrException(ErrorCode.RATE_LIMITED,
                    "识别并发已达上限(" + concurrency + ")，请稍后重试");
        }
        return pool.submit(withMdc(task));
    }

    /** 把提交线程的 MDC（traceId 等）透传到工作线程，日志保持可追踪；并保证许可释放。 */
    private <T> Callable<T> withMdc(Callable<T> task) {
        var context = MDC.getCopyOfContextMap();
        return () -> {
            if (context != null) {
                MDC.setContextMap(context);
            }
            try {
                return task.call();
            } finally {
                MDC.clear();
                permits.release();
            }
        };
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    @Override
    public void destroy() {
        pool.shutdown();
    }
}
