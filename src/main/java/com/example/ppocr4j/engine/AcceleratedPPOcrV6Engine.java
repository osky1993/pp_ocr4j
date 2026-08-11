/*
 * Copyright (c) 2019-2026, dreamlu.net All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.ppocr4j.engine;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.example.ppocr4j.config.Accelerator;
import com.example.ppocr4j.service.OcrEngine;
import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.postprocessor.CtcLabelDecoder;
import net.dreamlu.mica.ai.ppocr.postprocessor.DbPostProcessor;
import net.dreamlu.mica.ai.ppocr.preprocessor.DetectionPreprocessor;
import net.dreamlu.mica.ai.ppocr.preprocessor.RecognitionPreprocessor;
import net.dreamlu.mica.ai.ppocr.utils.BoxUtil;
import net.dreamlu.mica.ai.ppocr.utils.CropUtil;
import net.dreamlu.mica.ai.ppocr.utils.NdArrayUtils;
import org.opencv.core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 加速版 PP-OCRv6 引擎：移植自 mica-ppocr-core 1.0.1 的
 * {@code net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine}（Apache 2.0）。
 *
 * <p>与原版唯一的实质差异：修复了「provider 只记日志、从未应用到 SessionOptions」的缺陷——
 * 本类会按 {@link Accelerator} 真正调用 {@code addCoreML()} / {@code addCUDA()}，
 * 失败时告警并回落 CPU。推理流水线逻辑逐行保持与原版一致，便于上游修复后切回官方实现。</p>
 *
 * <p>注意：启用 CoreML/CUDA 后不再保证与 Python 参考实现 bit-exact。</p>
 */
public final class AcceleratedPPOcrV6Engine implements OcrEngine {

	private static final Logger log = LoggerFactory.getLogger(AcceleratedPPOcrV6Engine.class);

	private final OrtEnvironment env;
	private final OrtSession detSession;
	private final OrtSession recSession;
	private final String detInputName;
	private final String recInputName;

	private final DetectionPreprocessor detPre;
	private final DbPostProcessor detPost;
	private final RecognitionPreprocessor recPre;
	private final CtcLabelDecoder recPost;
	private final int recBatchSize;

	private boolean closed = false;

	/**
	 * 创建加速版推理引擎。
	 *
	 * @param config      配置参数（与原版 PPOcrV6Engine 完全一致）
	 * @param accelerator 加速器；AUTO 会先按环境解析为具体值
	 */
	public AcceleratedPPOcrV6Engine(PPOcrV6Config config, Accelerator accelerator) {
		requireFile(config.getDetModelPath(), "detModelPath");
		requireFile(config.getRecModelPath(), "recModelPath");
		requireFile(config.getRecCharDictPath(), "recCharDictPath");
		if (config.getRecBatchSize() < 1) {
			throw new IllegalArgumentException("recBatchSize must be >= 1, got " + config.getRecBatchSize());
		}
		if (config.getRecImageShape() == null || config.getRecImageShape().length != 3) {
			throw new IllegalArgumentException("recImageShape must be [C, H, W]");
		}
		this.env = OrtEnvironment.getEnvironment();

		try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
			try {
				opts.setIntraOpNumThreads(Math.max(1, config.getIntraOpNumThreads()));
				opts.setInterOpNumThreads(Math.max(1, config.getInterOpNumThreads()));
			} catch (OrtException e) {
				log.warn("设置线程数失败，使用默认值: {}", e.getMessage());
			}

			// 原版缺陷修复点：把加速器真正应用到会话选项
			Accelerator resolved = accelerator.resolve();
			Accelerator applied = applyAccelerator(opts, resolved);
			log.info("加速版引擎 execution provider: {}（请求 {}，解析 {}）", applied, accelerator, resolved);

			try {
				this.detSession = env.createSession(config.getDetModelPath(), opts);
				this.recSession = env.createSession(config.getRecModelPath(), opts);
			} catch (OrtException e) {
				close();
				throw new RuntimeException("创建 ONNX Runtime 会话失败: " + e.getMessage(), e);
			}
		}

		try {
			this.detInputName = detSession.getInputNames().iterator().next();
			this.recInputName = recSession.getInputNames().iterator().next();
			this.detPre = new DetectionPreprocessor(config.getDetLimitSideLen(), config.getDetLimitType(), config.getDetMaxSideLimit());
			this.detPost = new DbPostProcessor(config.getDetThresh(), config.getDetBoxThresh(), config.getDetUnclipRatio(),
				1000, 3);
			this.recPre = new RecognitionPreprocessor(config.getRecImageShape()[1], 320, 3200);
			this.recPost = new CtcLabelDecoder(config.getRecCharDictPath());
			this.recBatchSize = config.getRecBatchSize();
		} catch (RuntimeException e) {
			closeOnInitFailure(e);
			throw e;
		}

		log.info("AcceleratedPPOcrV6Engine 初始化完成: det={}, rec={}, vocab={}",
			this.detPre, this.recPre, this.recPost.vocabSize());
	}

	/** 应用加速器到会话选项；失败回落 CPU。返回实际生效的加速器。 */
	private static Accelerator applyAccelerator(OrtSession.SessionOptions opts, Accelerator resolved) {
		try {
			switch (resolved) {
				case COREML -> opts.addCoreML();
				case CUDA -> opts.addCUDA(0);
				default -> { /* CPU：不添加任何 EP */ }
			}
			return resolved;
		} catch (OrtException e) {
			log.warn("应用加速器 {} 失败，回落 CPU: {}", resolved, e.getMessage());
			return Accelerator.CPU;
		}
	}

	private static void requireFile(String path, String name) {
		if (path == null) {
			throw new IllegalArgumentException(name + " is null");
		}
		if (!Files.isRegularFile(Path.of(path))) {
			throw new IllegalArgumentException(name + ": file not found: " + path);
		}
	}

	private void closeOnInitFailure(Exception cause) {
		closeSessions(cause::addSuppressed);
		closed = true;
	}

	@Override
	public void close() {
		if (!closed) {
			closeSessions(e -> log.debug("关闭 session 失败: {}", e.getMessage()));
			closed = true;
			log.info("AcceleratedPPOcrV6Engine 已关闭");
		}
	}

	private void closeSessions(Consumer<OrtException> onError) {
		if (detSession != null) {
			try {
				detSession.close();
			} catch (OrtException e) {
				onError.accept(e);
			}
		}
		if (recSession != null) {
			try {
				recSession.close();
			} catch (OrtException e) {
				onError.accept(e);
			}
		}
	}

	private void requireOpen() {
		if (closed) {
			throw new IllegalStateException("AcceleratedPPOcrV6Engine has been closed and can no longer be used.");
		}
	}

	@Override
	public String toString() {
		return "AcceleratedPPOcrV6Engine(det=" + detPre + ", rec=" + recPre
			+ ", vocab=" + recPost.vocabSize() + ", closed=" + closed + ")";
	}

	/**
	 * 文本检测。
	 *
	 * @param imgBgr BGR 格式图像 (H, W, 3) uint8
	 * @return boxes 形状 (N, 4, 2) int，scores 长度 N
	 */
	public DetectResult detect(Mat imgBgr) {
		requireOpen();
		DetectionPreprocessor.Result prep = detPre.call(imgBgr);
		long[] shape = toLongArray(prep.shape());
		FloatBuffer buf = NdArrayUtils.toBuffer(prep.data());
		try (
			OnnxTensor input = OnnxTensor.createTensor(env, buf, shape);
			OrtSession.Result result = detSession.run(Map.of(detInputName, input))
		) {
			OnnxTensor outTensor = (OnnxTensor) result.get(0);
			float[][] prob = readProb2D(outTensor);
			Mat probMat = probToMat(prob);
			try {
				DbPostProcessor.Result post = detPost.call(probMat, prep.imgShape());
				return new DetectResult(post.boxes(), post.scores());
			} finally {
				probMat.release();
			}
		} catch (OrtException e) {
			throw new RuntimeException("det 推理失败: " + e.getMessage(), e);
		}
	}

	/**
	 * 文本识别（支持批量）。
	 *
	 * @param imgList 裁剪后的 BGR 文本行图像列表
	 * @return texts 与 scores 长度一致
	 */
	public RecognizeResult recognize(List<Mat> imgList) {
		requireOpen();
		int n = imgList.size();
		if (n == 0) {
			return new RecognizeResult(new String[0], new float[0]);
		}

		List<Integer> order = new ArrayList<>(n);
		List<Double> ratios = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			Mat m = imgList.get(i);
			order.add(i);
			ratios.add((double) m.cols() / m.rows());
		}
		List<Integer> sortedOrder = new ArrayList<>(order);
		sortedOrder.sort(Comparator.comparingDouble(ratios::get));

		List<Mat> sortedImgs = new ArrayList<>(n);
		for (int idx : sortedOrder) {
			sortedImgs.add(imgList.get(idx));
		}

		String[] texts = new String[n];
		float[] scores = new float[n];

		for (int start = 0; start < n; start += recBatchSize) {
			int end = Math.min(start + recBatchSize, n);
			List<Mat> batch = sortedImgs.subList(start, end);
			RecognitionPreprocessor.Result prep = recPre.call(batch);
			long[] shape = toLongArray(prep.shape());
			FloatBuffer buf = NdArrayUtils.toBuffer(prep.data());
			try (
				OnnxTensor input = OnnxTensor.createTensor(env, buf, shape);
				OrtSession.Result result = recSession.run(Map.of(recInputName, input))
			) {
				OnnxTensor outTensor = (OnnxTensor) result.get(0);
				float[][][] modelOutput = read3D(outTensor);
				CtcLabelDecoder.Result decoded = recPost.call(modelOutput);
				for (int j = 0; j < decoded.texts().length; j++) {
					int orig = sortedOrder.get(start + j);
					texts[orig] = decoded.texts()[j];
					scores[orig] = decoded.scores()[j];
				}
			} catch (OrtException e) {
				throw new RuntimeException("rec 推理失败: " + e.getMessage(), e);
			}
		}
		return new RecognizeResult(texts, scores);
	}

	/**
	 * 完整 OCR 流程：检测 → 排序 → 裁剪 → 识别。
	 *
	 * @param imgBgr BGR 格式图像 (H, W, 3) uint8
	 * @return 识别结果列表（按阅读顺序排列）
	 */
	@Override
	public List<PPOcrV6Result> run(Mat imgBgr) {
		requireOpen();
		DetectResult dr = detect(imgBgr);
		if (dr.boxes().length == 0) {
			return List.of();
		}

		int[][][] sortedBoxes = BoxUtil.sortQuadBoxes(dr.boxes());

		List<Mat> crops = CropUtil.cropByPolys(imgBgr, sortedBoxes);
		try {
			List<int[][]> validBoxes = new ArrayList<>();
			List<Mat> validCrops = new ArrayList<>();
			for (int i = 0; i < sortedBoxes.length; i++) {
				if (crops.get(i) != null) {
					validBoxes.add(sortedBoxes[i]);
					validCrops.add(crops.get(i));
				}
			}
			if (validCrops.isEmpty()) {
				return List.of();
			}

			RecognizeResult rr = recognize(validCrops);

			List<PPOcrV6Result> results = new ArrayList<>(validBoxes.size());
			for (int i = 0; i < validBoxes.size(); i++) {
				results.add(new PPOcrV6Result(rr.texts()[i], rr.scores()[i], validBoxes.get(i)));
			}
			return results;
		} finally {
			for (Mat crop : crops) {
				if (crop != null) {
					crop.release();
				}
			}
		}
	}

	private long[] toLongArray(int[] arr) {
		long[] out = new long[arr.length];
		for (int i = 0; i < arr.length; i++) {
			out[i] = arr[i];
		}
		return out;
	}

	private float[][] readProb2D(OnnxTensor tensor) throws OrtException {
		FloatBuffer buf = tensor.getFloatBuffer();
		long[] shape = tensor.getInfo().getShape();
		int total = (int) (shape[0] * shape[1] * shape[2] * shape[3]);
		float[] data = new float[total];
		buf.get(data);
		int h = (int) shape[2];
		int w = (int) shape[3];
		float[][] out = new float[h][w];
		for (int i = 0; i < h; i++) {
			System.arraycopy(data, i * w, out[i], 0, w);
		}
		return out;
	}

	private float[][][] read3D(OnnxTensor tensor) throws OrtException {
		FloatBuffer buf = tensor.getFloatBuffer();
		long[] shape = tensor.getInfo().getShape();
		if (shape.length != 3) {
			throw new IllegalArgumentException("期望 3D rec 输出, 实际 " + shape.length + "D");
		}
		int b = (int) shape[0];
		int t = (int) shape[1];
		int c = (int) shape[2];
		float[] data = new float[b * t * c];
		buf.get(data);
		float[][][] out = new float[b][t][c];
		for (int i = 0; i < b; i++) {
			for (int j = 0; j < t; j++) {
				System.arraycopy(data, (i * t + j) * c, out[i][j], 0, c);
			}
		}
		return out;
	}

	private Mat probToMat(float[][] prob) {
		int h = prob.length;
		int w = prob[0].length;
		Mat m = new Mat(h, w, org.opencv.core.CvType.CV_32F);
		try {
			float[] flat = new float[h * w];
			for (int i = 0; i < h; i++) {
				System.arraycopy(prob[i], 0, flat, i * w, w);
			}
			m.put(0, 0, flat);
			return m;
		} catch (RuntimeException | Error e) {
			m.release();
			throw e;
		}
	}

	/**
	 * 检测结果。
	 *
	 * @param boxes  文本框 (N, 4, 2) int
	 * @param scores 每框分数
	 */
	public record DetectResult(int[][][] boxes, float[] scores) {
	}

	/**
	 * 识别结果。
	 *
	 * @param texts  识别文本
	 * @param scores 每条文本的置信度
	 */
	public record RecognizeResult(String[] texts, float[] scores) {
	}
}
