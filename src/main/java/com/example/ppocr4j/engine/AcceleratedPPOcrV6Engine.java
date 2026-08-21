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
import net.dreamlu.mica.ai.ppocr.postprocessor.DocOrientationPostprocessor;
import net.dreamlu.mica.ai.ppocr.preprocessor.DetectionPreprocessor;
import net.dreamlu.mica.ai.ppocr.preprocessor.DocOrientationPreprocessor;
import net.dreamlu.mica.ai.ppocr.preprocessor.RecognitionPreprocessor;
import net.dreamlu.mica.ai.ppocr.utils.BoxUtil;
import net.dreamlu.mica.ai.ppocr.utils.CropUtil;
import net.dreamlu.mica.ai.ppocr.utils.NdArrayUtils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 加速版 PP-OCRv6 引擎：移植自 mica-ppocr-core <b>1.1.3</b> 的
 * {@code net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine}（Apache 2.0），
 * 含 1.1.1 引入的文档方向分类（doc_ori）能力。
 *
 * <p>与原版唯一的实质差异：修复了「provider 只记日志、从未应用到 SessionOptions」的缺陷——
 * 本类会按 {@link Accelerator} 真正调用 {@code addCoreML()} / {@code addCUDA()}，
 * 失败时告警并回落 CPU。推理流水线逻辑与原版 runMat 保持一致，便于上游修复后切回官方实现。</p>
 *
 * <p>注意：启用 CoreML/CUDA 后不再保证与 Python 参考实现 bit-exact。</p>
 */
public final class AcceleratedPPOcrV6Engine implements OcrEngine {

	private static final Logger log = LoggerFactory.getLogger(AcceleratedPPOcrV6Engine.class);

	private final OrtEnvironment env;
	private final OrtSession detSession;
	private final OrtSession recSession;
	private final OrtSession docOriSession;
	private final String detInputName;
	private final String recInputName;
	private final String docOriInputName;
	private final String detOutputName;
	private final String recOutputName;
	private final String docOriOutputName;

	private final DetectionPreprocessor detPre;
	private final DbPostProcessor detPost;
	private final RecognitionPreprocessor recPre;
	private final CtcLabelDecoder recPost;
	private final int recBatchSize;
	private final DocOrientationPreprocessor docOriPre;
	private final DocOrientationPostprocessor docOriPost;
	private final boolean docOriEnabled;

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
		this.docOriEnabled = config.isUseDocOrientationClassify();
		if (docOriEnabled) {
			if (config.getDocOrientationModelPath() == null || config.getDocOrientationModelPath().isEmpty()) {
				throw new IllegalArgumentException(
					"useDocOrientationClassify=true 时必须指定 docOrientationModelPath");
			}
			requireFile(config.getDocOrientationModelPath(), "docOrientationModelPath");
		}
		this.env = OrtEnvironment.getEnvironment();

		OrtSession detSess = null;
		OrtSession recSess = null;
		OrtSession docOriSess = null;
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
				detSess = env.createSession(config.getDetModelPath(), opts);
				recSess = env.createSession(config.getRecModelPath(), opts);
				if (docOriEnabled) {
					docOriSess = env.createSession(config.getDocOrientationModelPath(), opts);
				}
			} catch (OrtException e) {
				silentClose(detSess);
				silentClose(recSess);
				silentClose(docOriSess);
				throw new RuntimeException("创建 ONNX Runtime 会话失败: " + e.getMessage(), e);
			}
		}
		this.detSession = detSess;
		this.recSession = recSess;
		this.docOriSession = docOriSess;

		try {
			this.detInputName = detSession.getInputNames().iterator().next();
			this.recInputName = recSession.getInputNames().iterator().next();
			this.detOutputName = detSession.getOutputNames().iterator().next();
			this.recOutputName = recSession.getOutputNames().iterator().next();
			this.detPre = new DetectionPreprocessor(config.getDetLimitSideLen(), config.getDetLimitType(), config.getDetMaxSideLimit());
			this.detPost = new DbPostProcessor(config.getDetThresh(), config.getDetBoxThresh(), config.getDetUnclipRatio(),
				1000, 3);
			this.recPre = new RecognitionPreprocessor(config.getRecImageShape()[1], 320, 3200);
			this.recPost = new CtcLabelDecoder(config.getRecCharDictPath());
			this.recBatchSize = config.getRecBatchSize();
			this.docOriPre = new DocOrientationPreprocessor();
			this.docOriPost = new DocOrientationPostprocessor(config.getDocOrientationThresh());
			if (docOriEnabled) {
				this.docOriInputName = docOriSession.getInputNames().iterator().next();
				this.docOriOutputName = docOriSession.getOutputNames().iterator().next();
			} else {
				this.docOriInputName = null;
				this.docOriOutputName = null;
			}
		} catch (RuntimeException e) {
			closeOnInitFailure(e);
			throw e;
		}

		log.info("AcceleratedPPOcrV6Engine 初始化完成: det={}, rec={}, vocab={}, docOri={}",
			this.detPre, this.recPre, this.recPost.vocabSize(), docOriEnabled ? "enabled" : "disabled");
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

	private static void silentClose(OrtSession session) {
		if (session == null) {
			return;
		}
		try {
			session.close();
		} catch (OrtException e) {
			log.debug("关闭 session 失败: {}", e.getMessage());
		}
	}

	/** 关闭单个 ONNX Session，忽略关闭异常（仅用于清理路径）。 */
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

	/** 执行关闭逻辑：释放所有 session，防止 native 资源长期占用。 */
	@Override
	public void close() {
		if (!closed) {
			closeSessions(e -> log.debug("关闭 session 失败: {}", e.getMessage()));
			closed = true;
			log.info("AcceleratedPPOcrV6Engine 已关闭");
		}
	}

	private void closeSessions(Consumer<OrtException> onError) {
		for (OrtSession session : new OrtSession[]{detSession, recSession, docOriSession}) {
			if (session == null) {
				continue;
			}
			try {
				session.close();
			} catch (OrtException e) {
				onError.accept(e);
			}
		}
	}

	/** 检查关闭状态，避免调用已关闭引擎产生不可预期行为。 */
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
	 * 文本检测（Mat 版）。Mat 的 release 由调用方负责。
	 *
	 * @param imgBgr BGR 格式图像 (H, W, 3) uint8
	 * @return boxes 形状 (N, 4, 2) int，scores 长度 N
	 */
	public DetectResult detectMat(Mat imgBgr) {
		requireOpen();
		DetectionPreprocessor.Result prep = detPre.call(imgBgr);
		long[] shape = toLongArray(prep.shape());
		FloatBuffer buf = NdArrayUtils.toBuffer(prep.data());
		try (
			OnnxTensor input = OnnxTensor.createTensor(env, buf, shape);
			OrtSession.Result result = detSession.run(Map.of(detInputName, input))
		) {
			OnnxTensor outTensor = (OnnxTensor) result.get(detOutputName).get();
			Mat probMat = readProbToMat(outTensor);
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
	 * 文本识别（Mat 版，支持批量）。每个 crop Mat 的 release 由调用方负责。
	 *
	 * @param imgList 裁剪后的 BGR 文本行图像列表
	 * @return texts 与 scores 长度一致
	 */
	public RecognizeResult recognizeMat(List<Mat> imgList) {
		requireOpen();
		int n = imgList.size();
		if (n == 0) {
			return new RecognizeResult(new String[0], new float[0]);
		}

		// 按宽高比排序：让 batch 内尺寸相近，padding 浪费最小
		Integer[] sortedOrder = new Integer[n];
		double[] ratios = new double[n];
		for (int i = 0; i < n; i++) {
			sortedOrder[i] = i;
			ratios[i] = (double) imgList.get(i).cols() / imgList.get(i).rows();
		}
		Arrays.sort(sortedOrder, Comparator.comparingDouble(i -> ratios[i]));

		String[] texts = new String[n];
		float[] scores = new float[n];

		for (int start = 0; start < n; start += recBatchSize) {
			int end = Math.min(start + recBatchSize, n);
			List<Mat> batch = new ArrayList<>(end - start);
			for (int i = start; i < end; i++) {
				batch.add(imgList.get(sortedOrder[i]));
			}
			RecognitionPreprocessor.Result prep = recPre.call(batch);
			long[] shape = toLongArray(prep.shape());
			FloatBuffer buf = NdArrayUtils.toBuffer(prep.data());
			try (
				OnnxTensor input = OnnxTensor.createTensor(env, buf, shape);
				OrtSession.Result result = recSession.run(Map.of(recInputName, input))
			) {
				OnnxTensor outTensor = (OnnxTensor) result.get(recOutputName).get();
				long[] outShape = outTensor.getInfo().getShape();
				int bOut = (int) outShape[0];
				int tOut = (int) outShape[1];
				int cOut = (int) outShape[2];
				float[] flat = new float[bOut * tOut * cOut];
				outTensor.getFloatBuffer().get(flat);
				CtcLabelDecoder.Result decoded = recPost.call(flat, bOut, tOut, cOut);
				for (int j = 0; j < decoded.texts().length; j++) {
					int orig = sortedOrder[start + j];
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
	 * 完整 OCR 流程（Mat 版）：doc_ori 方向校正（可选）→ 检测 → 排序 → 裁剪 → 识别。
	 * Mat 的 release 由调用方负责。
	 *
	 * @param imgBgr BGR 格式图像 (H, W, 3) uint8
	 * @return 识别结果列表（按阅读顺序排列）；启用 doc_ori 时每个
	 *         {@link PPOcrV6Result#rotatedDegrees()} 记录应用到原图的顺时针旋转角度
	 */
	@Override
	public List<PPOcrV6Result> run(Mat imgBgr) {
		requireOpen();
		DocOriRotated rotatedInfo = classifyAndRotateDocOrientation(imgBgr);
		Mat rotated = rotatedInfo.mat();
		try {
			List<PPOcrV6Result> results = runOnMat(rotated);
			if (rotatedInfo.degrees() == 0) {
				return results;
			}
			int deg = rotatedInfo.degrees();
			List<PPOcrV6Result> wrapped = new ArrayList<>(results.size());
			for (PPOcrV6Result r : results) {
				wrapped.add(new PPOcrV6Result(r.text(), r.score(), r.box(), deg));
			}
			return wrapped;
		} finally {
			if (rotated != imgBgr) {
				rotated.release();
			}
		}
	}

	/** 在已正向化的 Mat 上跑核心 OCR 流水线；内部负责所有 crop Mat 的 release。 */
	private List<PPOcrV6Result> runOnMat(Mat imgBgr) {
		DetectResult dr = detectMat(imgBgr);
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

			RecognizeResult rr = recognizeMat(validCrops);
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

	/**
	 * 文档方向分类 + 旋转：返回正向的 Mat 与应用到原图的顺时针旋转角度。
	 * 未启用或判定为 0° 时返回原图（不旋转、不 release），degrees=0。
	 */
	private DocOriRotated classifyAndRotateDocOrientation(Mat imgBgr) {
		if (!docOriEnabled) {
			return new DocOriRotated(imgBgr, 0);
		}
		DocOrientationPostprocessor.Result ori;
		try {
			ori = classifyDocOrientationMat(imgBgr);
		} catch (RuntimeException e) {
			log.warn("文档方向分类失败，按 0° 处理: {}", e.getMessage());
			return new DocOriRotated(imgBgr, 0);
		}
		if (ori.degrees() == 0) {
			return new DocOriRotated(imgBgr, 0);
		}
		log.debug("文档方向分类: label={}, degrees={}, score={}", ori.label(), ori.degrees(), ori.score());
		// PaddleX 官方语义：label N 表示图片已经顺时针旋转了 N 度，摆正需逆向旋转
		int code = switch (ori.degrees()) {
			case 90 -> Core.ROTATE_90_COUNTERCLOCKWISE;
			case 180 -> Core.ROTATE_180;
			case 270 -> Core.ROTATE_90_CLOCKWISE;
			default -> -1;
		};
		if (code == -1) {
			return new DocOriRotated(imgBgr, 0);
		}
		Mat rotated = new Mat();
		try {
			Core.rotate(imgBgr, rotated, code);
			return new DocOriRotated(rotated, ori.degrees());
		} catch (RuntimeException | Error e) {
			rotated.release();
			throw e;
		}
	}

	/** 文档方向分类推理（仅返回结果，不做任何旋转）。 */
	private DocOrientationPostprocessor.Result classifyDocOrientationMat(Mat imgBgr) {
		DocOrientationPreprocessor.Result prep = docOriPre.call(imgBgr);
		long[] shape = toLongArray(prep.shape());
		FloatBuffer buf = NdArrayUtils.toBuffer(prep.data());
		try (
			OnnxTensor input = OnnxTensor.createTensor(env, buf, shape);
			OrtSession.Result result = docOriSession.run(Map.of(docOriInputName, input))
		) {
			OnnxTensor outTensor = (OnnxTensor) result.get(docOriOutputName).get();
			FloatBuffer out = outTensor.getFloatBuffer();
			float[] logits = new float[4];
			out.get(logits);
			return docOriPost.call(logits);
		} catch (OrtException e) {
			throw new RuntimeException("doc_ori 推理失败: " + e.getMessage(), e);
		}
	}

	/** toLongArray 将 int[] 形状转换为 ONNX API 需要的 long[]。 */
	private long[] toLongArray(int[] arr) {
		long[] out = new long[arr.length];
		for (int i = 0; i < arr.length; i++) {
			out[i] = arr[i];
		}
		return out;
	}

	/**
	 * 将 det 模型输出张量转为 OpenCV Mat，便于 DB 后处理读取文本概率图。
	 */
	private Mat readProbToMat(OnnxTensor tensor) throws OrtException {
		FloatBuffer buf = tensor.getFloatBuffer();
		long[] shape = tensor.getInfo().getShape();
		int h = (int) shape[2];
		int w = (int) shape[3];
		float[] data = new float[h * w];
		buf.get(data);
		Mat m = new Mat(h, w, org.opencv.core.CvType.CV_32F);
		try {
			m.put(0, 0, data);
			return m;
		} catch (RuntimeException | Error e) {
			m.release();
			throw e;
		}
	}

	/**
	 * 文档方向分类 + 旋转结果。
	 *
	 * @param mat     正向化后的 Mat（不旋转时就是原图）
	 * @param degrees doc_ori 应用到原图的顺时针旋转角度（0/90/180/270）
	 */
	private record DocOriRotated(Mat mat, int degrees) {
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
