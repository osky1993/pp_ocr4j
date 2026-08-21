package com.example.ppocr4j.task;

import com.example.ppocr4j.config.OcrProperties;
import com.example.ppocr4j.exception.OcrException;
import com.example.ppocr4j.service.OcrExecutor;
import com.example.ppocr4j.service.OcrService;
import com.example.ppocr4j.web.ErrorCode;
import com.example.ppocr4j.web.OcrResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 异步识别任务管理器（内存态，单实例语义）。
 *
 * <ul>
 *   <li>提交走 {@link OcrExecutor} 同一并发闸门：并发满时提交即被拒（2001），不积压。</li>
 *   <li>结果保留 ocr.task.ttl-minutes（默认 30 分钟），定时清理；重启即失。</li>
 *   <li>多副本部署时任务查询不跨实例——需要粘性路由或改用外部存储（README 已注明）。</li>
 * </ul>
 */
@Component
public class OcrTaskManager {

    private static final Logger log = LoggerFactory.getLogger(OcrTaskManager.class);

    public enum Status { RUNNING, DONE, FAILED }

        /** 任务记录：终态字段由工作线程写、查询线程读，全部 volatile。 */
        public static class TaskRecord {
            private final String taskId;
            private final Instant createdAt;
            private volatile Status status = Status.RUNNING;
            private volatile OcrResponse result;
        private volatile Integer errorCode;
        private volatile String errorMessage;
        private volatile Instant finishedAt;

        TaskRecord(String taskId) {
            this.taskId = taskId;
            this.createdAt = Instant.now();
        }

        /**
         * 导出任务查询视图：仅暴露 API 需要的字段，避免返回内部可变句柄。
         *
         * <p>status 不同时，字段会按需裁剪（例如 RUNNING 仅返回 taskId/status）。</p>
         */
        public Map<String, Object> toView() {
            var view = new java.util.LinkedHashMap<String, Object>();
            view.put("taskId", taskId);
            view.put("status", status.name());
            view.put("createdAt", createdAt.toString());
            if (finishedAt != null) {
                view.put("finishedAt", finishedAt.toString());
            }
            if (result != null) {
                view.put("result", result);
            }
            if (errorCode != null) {
                view.put("errorCode", errorCode);
                view.put("errorMessage", errorMessage);
            }
            return view;
        }
    }

    private final Map<String, TaskRecord> tasks = new ConcurrentHashMap<>();
    private final OcrService ocrService;
    private final OcrProperties props;

    /**
     * 构造任务管理器。
     *
     * <p>依赖注入的 {@code OcrService} 提供异步识别能力，{@code OcrProperties}
     * 提供 TTL 与并发参数（TTL 用于过期清理）。</p>
     */
    public OcrTaskManager(OcrService ocrService, OcrProperties props) {
        this.ocrService = ocrService;
        this.props = props;
    }

    /**
     * 提交异步识别任务。并发闸门拒绝时直接抛 2001（任务不落表）。
     *
     * @return taskId
     */
    public String submit(byte[] imageBytes, String source, String tier, int rotate, boolean autoRotate) {
        String taskId = UUID.randomUUID().toString().replace("-", "");
        TaskRecord record = new TaskRecord(taskId);
        // 先注册再提交：识别很快完成时查询也能命中
        tasks.put(taskId, record);
        try {
            ocrService.recognizeAsync(imageBytes, tier, rotate, autoRotate,
                    (results, error) -> complete(record, source, tier, results, error));
        } catch (RuntimeException e) {
            tasks.remove(taskId);
            throw e;
        }
        return taskId;
    }

    /**
     * 异步执行结束回调：将成功结果或异常信息写回内存记录。
     *
     * <ul>
     *   <li>成功：记录 OcrResponse 与完成时间、状态 DONE。</li>
     *   <li>失败：区分 OcrException 与未知异常，统一转码并附带错误信息，状态 FAILED。</li>
     * </ul>
     */
    private void complete(TaskRecord record, String source, String tier,
                          OcrService.TimedResults results, Throwable error) {
        record.finishedAt = Instant.now();
        if (error == null) {
            record.result = OcrResponse.of(source, results.tier(), results.results(), results.costMs());
            record.status = Status.DONE;
        } else {
            if (error instanceof OcrException oe) {
                record.errorCode = oe.getErrorCode().code();
                record.errorMessage = oe.getMessage();
            } else {
                record.errorCode = ErrorCode.INTERNAL_ERROR.code();
                record.errorMessage = "识别执行失败: " + error.getMessage();
            }
            record.status = Status.FAILED;
        }
    }

    /**
     * 查询任务。
     *
     * <p>可能返回 RUNNING/DONE/FAILED；查询到不存在/已清理任务一律按 INVALID_PARAM 报 1001。</p>
     */
    public Map<String, Object> get(String taskId) {
        TaskRecord record = tasks.get(taskId);
        if (record == null) {
            throw new OcrException(ErrorCode.INVALID_PARAM, "任务不存在或已过期: " + taskId);
        }
        return record.toView();
    }

    /** 定时清理超过 TTL 的任务（含异常悬挂的未完成任务，防泄漏）。 */
    @Scheduled(fixedDelay = 60_000)
    public void evictExpired() {
        Instant deadline = Instant.now().minusSeconds(props.getTask().getTtlMinutes() * 60);
        int before = tasks.size();
        tasks.values().removeIf(r -> r.createdAt.isBefore(deadline));
        int removed = before - tasks.size();
        if (removed > 0) {
            log.info("清理过期异步任务 {} 个，剩余 {}", removed, tasks.size());
        }
    }
}
