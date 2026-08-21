package com.example.ppocr4j.web;

import com.example.ppocr4j.exception.OcrException;
import com.example.ppocr4j.task.OcrTaskManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 异步识别任务接口：提交（multipart / base64 两形态）→ 轮询查询。
 * 任务为内存态、单实例语义；并发满时提交即返回 2001。
 */
@RestController
public class OcrTaskController {

    private final OcrTaskManager taskManager;

    /**
     * 只需依赖任务管理器。Controller 不持有图片解码与超时逻辑，核心行为由 taskManager 统一承接。
     */
    public OcrTaskController(OcrTaskManager taskManager) {
        this.taskManager = taskManager;
    }

    /**
     * 提交异步任务（multipart）：参数与同步接口 /api/ocr 一致。
     */
    @PostMapping("/api/ocr/tasks")
    public ApiResult<Map<String, String>> submit(@RequestParam("file") MultipartFile file,
                                                 @RequestParam(value = "tier", required = false) String tier,
                                                 @RequestParam(value = "rotate", defaultValue = "0") int rotate,
                                                 @RequestParam(value = "autoRotate", defaultValue = "false") boolean autoRotate)
            throws IOException {
        if (file.isEmpty()) {
            throw new OcrException(ErrorCode.INVALID_PARAM, "上传文件为空");
        }
        String taskId = taskManager.submit(file.getBytes(), file.getOriginalFilename(), tier, rotate, autoRotate);
        return ApiResult.ok(Map.of("taskId", taskId));
    }

    /**
     * 提交异步任务（base64 JSON），请求体同 /api/ocr/base64。
     */
    @PostMapping("/api/ocr/tasks/base64")
    public ApiResult<Map<String, String>> submitBase64(@RequestBody Base64Request req) {
        String taskId = taskManager.submit(req.toBytes(), "base64", req.tier(),
                req.rotateOrDefault(), req.autoRotateOrDefault());
        return ApiResult.ok(Map.of("taskId", taskId));
    }

    /**
     * 查询任务状态与结果：status = RUNNING / DONE / FAILED；
     * 不存在或超过 TTL 已清理 → 1001。
     */
    @GetMapping("/api/ocr/tasks/{taskId}")
    public ApiResult<Map<String, Object>> get(@PathVariable String taskId) {
        return ApiResult.ok(taskManager.get(taskId));
    }
}
