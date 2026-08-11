package com.example.ppocr4j.service;

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import org.opencv.core.Mat;

import java.io.Closeable;
import java.util.List;

/**
 * OCR 引擎统一抽象：屏蔽「库原版 CPU 引擎」与「本项目加速版引擎」的差异，
 * 让 OcrEngineManager / OcrService 无差别使用。
 */
public interface OcrEngine extends Closeable {

    /** 完整 OCR 流程：检测 → 排序 → 裁剪 → 识别。 */
    List<PPOcrV6Result> run(Mat imgBgr);

    /** 释放推理会话；实现若由外部（如 Spring）管理生命周期可为 no-op。 */
    @Override
    void close();
}
