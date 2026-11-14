#!/usr/bin/env python3
"""
Reproducible offline model converter & verifier for T-Bone's installable AI add-on packs.

Builds two permissively-licensed, built-in-ops-only LiteRT (.tflite) models:
  1. nsfw_stage1_mnv2_int8.tflite   — GantMan/nsfw_model v1.2.0 MobileNetV2-140 (MIT)
                                       Input:  [1, 224, 224, 3] float32 RGB in [0.0, 1.0]
                                       Output: [1, 5] float32 probs [drawings, hentai, neutral, porn, sexy]
  2. nsfw_stage2_marqo384_int8.tflite — Marqo/nsfw-image-detection-384 ViT-Tiny (Apache-2.0)
                                       Input:  [1, 384, 384, 3] float32 RGB normalized (px/127.5 - 1.0)
                                       Output: [1, 2] float32 logits [NSFW, SFW]

Also emits SHA256SUMS.txt and ai-models-manifest.json for pinning inside T-Bone.
Run on CI or a build machine — never on the user's phone.
"""

from __future__ import annotations

import hashlib
import inspect
import json
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

import numpy as np
import tensorflow as tf
import timm
import torch


GANTMAN_ZIP_URL = (
    "https://github.com/GantMan/nsfw_model/releases/download/1.2.0/mobilenet_v2_140_224.1.zip"
)
MARQO_HF_ID = "hf_hub:Marqo/nsfw-image-detection-384"

STAGE1_FILENAME = "nsfw_stage1_mnv2_int8.tflite"
STAGE2_FILENAME = "nsfw_stage2_marqo384_int8.tflite"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def softmax(x: np.ndarray) -> np.ndarray:
    e = np.exp(x - np.max(x, axis=-1, keepdims=True))
    return e / np.sum(e, axis=-1, keepdims=True)


def make_synthetic_batches(size: int, norm: str) -> list[np.ndarray]:
    """Deterministic varied RGB synthetic images for numerical parity verification."""
    rng = np.random.default_rng(seed=20261114)
    samples: list[np.ndarray] = []

    # 1. Uniform random noise
    for _ in range(3):
        samples.append(rng.integers(0, 256, size=(size, size, 3), dtype=np.uint8))

    # 2. Smooth color gradients (landscape / sky / skin-tone hues)
    ys = np.linspace(0, 255, size, dtype=np.float32)[:, None]
    xs = np.linspace(0, 255, size, dtype=np.float32)[None, :]
    grad1 = np.stack([np.broadcast_to(xs, (size, size)), np.broadcast_to(ys, (size, size)), np.full((size, size), 140.0)], axis=-1)
    samples.append(grad1.astype(np.uint8))

    # 3. Warm skin-like patch on dark background (tests challenging neutral/skin calibration)
    patch = np.full((size, size, 3), 24, dtype=np.uint8)
    m = size // 4
    patch[m : size - m, m : size - m] = np.array([218, 174, 146], dtype=np.uint8)
    samples.append(patch)

    # 4. High-contrast checkerboard (meme/text-like)
    block = max(4, size // 16)
    yy, xx = np.indices((size, size))
    chk = (((yy // block) + (xx // block)) % 2 * 255).astype(np.uint8)
    samples.append(np.stack([chk, chk, chk], axis=-1))

    # 5. Solid dark & solid light frames
    samples.append(np.full((size, size, 3), 15, dtype=np.uint8))
    samples.append(np.full((size, size, 3), 240, dtype=np.uint8))

    out: list[np.ndarray] = []
    for img in samples:
        f = img.astype(np.float32)
        if norm == "0_1":
            f = f / 255.0
        elif norm == "neg1_1":
            f = (f / 127.5) - 1.0
        else:
            raise ValueError(f"Unknown norm: {norm}")
        out.append(np.expand_dims(f, axis=0))
    return out


def verify_builtin_ops_only(tflite_path: Path) -> dict:
    """Assert the exported TFLite flatbuffer uses ONLY standard built-in ops (no Flex/custom ops)."""
    interp = tf.lite.Interpreter(
        model_path=str(tflite_path),
        experimental_op_resolver_type=tf.lite.experimental.OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES,
    )
    interp.allocate_tensors()
    in_det = interp.get_input_details()[0]
    out_det = interp.get_output_details()[0]
    op_details = interp._get_ops_details()  # noqa: SLF001
    op_names = sorted({op["op_name"] for op in op_details})
    bad_ops = [op for op in op_names if op.startswith("Flex") or op == "CUSTOM"]
    if bad_ops:
        raise RuntimeError(f"{tflite_path.name} contains non-builtin ops: {bad_ops}")
    print(f"[ops] {tflite_path.name}: {len(op_names)} unique built-in ops OK: {op_names}")
    return {
        "input_shape": [int(x) for x in in_det["shape"]],
        "input_dtype": np.dtype(in_det["dtype"]).name,
        "output_shape": [int(x) for x in out_det["shape"]],
        "output_dtype": np.dtype(out_det["dtype"]).name,
        "op_count": len(op_details),
        "unique_ops": op_names,
    }


def export_stage1_gantman(work_dir: Path, out_dir: Path) -> tuple[dict, np.ndarray]:
    print("=== [Stage 1] Downloading GantMan/nsfw_model v1.2.0 ===")
    zip_path = work_dir / "mobilenet_v2_140_224.1.zip"
    if not zip_path.exists():
        urllib.request.urlretrieve(GANTMAN_ZIP_URL, zip_path)

    extract_dir = work_dir / "gantman_extract"
    if extract_dir.exists():
        shutil.rmtree(extract_dir)
    with zipfile.ZipFile(zip_path, "r") as zf:
        zf.extractall(extract_dir)

    model_root = extract_dir / "mobilenet_v2_140_224"
    ref_tflite = model_root / "saved_model.tflite"
    labels_txt = (model_root / "class_labels.txt").read_text(encoding="utf-8").strip().splitlines()
    labels = [line.strip() for line in labels_txt if line.strip()]
    print(f"[stage1] upstream class_labels.txt = {labels}")
    assert labels == ["drawings", "hentai", "neutral", "porn", "sexy"], f"Unexpected labels: {labels}"

    print("=== [Stage 1] Converting SavedModel -> dynamic-range INT8 TFLite (TFLITE_BUILTINS) ===")
    converter = tf.lite.TFLiteConverter.from_saved_model(str(model_root))
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    tflite_bytes = converter.convert()

    out_path = out_dir / STAGE1_FILENAME
    out_path.write_bytes(tflite_bytes)
    spec = verify_builtin_ops_only(out_path)
    assert spec["input_shape"] == [1, 224, 224, 3], f"Unexpected Stage 1 input shape: {spec}"
    assert spec["output_shape"] == [1, 5], f"Unexpected Stage 1 output shape: {spec}"

    # Verify parity against upstream FP32 saved_model.tflite
    ref_interp = tf.lite.Interpreter(model_path=str(ref_tflite))
    ref_interp.allocate_tensors()
    ref_in = ref_interp.get_input_details()[0]["index"]
    ref_out = ref_interp.get_output_details()[0]["index"]

    q_interp = tf.lite.Interpreter(model_path=str(out_path))
    q_interp.allocate_tensors()
    q_in = q_interp.get_input_details()[0]["index"]
    q_out = q_interp.get_output_details()[0]["index"]

    batches = make_synthetic_batches(224, "0_1")
    diffs = []
    sample_outputs = []
    for x in batches:
        ref_interp.set_tensor(ref_in, x)
        ref_interp.invoke()
        ref_y = ref_interp.get_tensor(ref_out)[0].copy()

        q_interp.set_tensor(q_in, x)
        q_interp.invoke()
        q_y = q_interp.get_tensor(q_out)[0].copy()

        prob_sum = float(np.sum(q_y))
        assert abs(prob_sum - 1.0) < 0.02, f"Stage 1 output does not sum to 1: {prob_sum} ({q_y})"
        diffs.append(float(np.mean(np.abs(ref_y - q_y))))
        sample_outputs.append(q_y)

    mean_diff = float(np.mean(diffs))
    max_diff = float(np.max(diffs))
    print(f"[stage1] parity vs upstream FP32: mean |Δp|={mean_diff:.5f}, max |Δp|={max_diff:.5f}")
    assert mean_diff < 0.03 and max_diff < 0.08, f"Stage 1 parity drift too high: mean={mean_diff}, max={max_diff}"

    meta = {
        "id": "fast_mnv2_v1",
        "filename": STAGE1_FILENAME,
        "display_name": "Fast Scanner (MobileNetV2)",
        "upstream": "https://github.com/GantMan/nsfw_model/releases/tag/1.2.0",
        "license": "MIT (with third-party notices)",
        "size_bytes": out_path.stat().st_size,
        "sha256": sha256_file(out_path),
        "input_size": 224,
        "normalization": "zero_to_one",
        "labels": labels,
        "output_activation": "softmax_probs",
        "parity_mean_abs_diff": round(mean_diff, 6),
        "spec": spec,
    }
    return meta, np.stack(sample_outputs, axis=0)


def export_stage2_marqo(work_dir: Path, out_dir: Path) -> tuple[dict, np.ndarray]:
    print("=== [Stage 2] Loading Marqo/nsfw-image-detection-384 via timm ===")
    model = timm.create_model(MARQO_HF_ID, pretrained=True).eval()

    dummy = torch.randn(1, 3, 384, 384, dtype=torch.float32)
    onnx_path = work_dir / "marqo_nsfw_384.onnx"
    export_kwargs = dict(
        opset_version=17,
        input_names=["image"],
        output_names=["logits"],
        do_constant_folding=True,
    )
    # Classic TorchScript exporter avoids Dynamo ONNX graphs that onnx2tf cannot transpose cleanly
    if "dynamo" in inspect.signature(torch.onnx.export).parameters:
        export_kwargs["dynamo"] = False

    print(f"=== [Stage 2] Exporting ONNX -> {onnx_path} ===")
    torch.onnx.export(model, dummy, str(onnx_path), **export_kwargs)

    tf_out_dir = work_dir / "marqo_tf"
    if tf_out_dir.exists():
        shutil.rmtree(tf_out_dir)

    print("=== [Stage 2] Running onnx2tf (NCHW -> NHWC SavedModel, onnxsim disabled) ===")
    subprocess.run(
        [
            sys.executable,
            "-m",
            "onnx2tf",
            "-i",
            str(onnx_path),
            "-o",
            str(tf_out_dir),
            "-nuo",
            "-osd",
            "-n",
        ],
        check=True,
    )

    saved_model_pb = tf_out_dir / "saved_model.pb"
    if not saved_model_pb.exists():
        raise FileNotFoundError(f"Expected SavedModel at {saved_model_pb}, found: {list(tf_out_dir.iterdir())}")

    print("=== [Stage 2] Converting Marqo SavedModel -> dynamic-range INT8 TFLite (TFLITE_BUILTINS) ===")
    converter = tf.lite.TFLiteConverter.from_saved_model(str(tf_out_dir))
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    tflite_bytes = converter.convert()

    out_path = out_dir / STAGE2_FILENAME
    out_path.write_bytes(tflite_bytes)
    spec = verify_builtin_ops_only(out_path)
    assert spec["input_shape"] == [1, 384, 384, 3], f"Unexpected Stage 2 input shape: {spec}"
    assert spec["output_shape"] == [1, 2], f"Unexpected Stage 2 output shape: {spec}"

    # Verify numerical parity against upstream PyTorch model (NCHW)
    q_interp = tf.lite.Interpreter(model_path=str(out_path))
    q_interp.allocate_tensors()
    q_in = q_interp.get_input_details()[0]["index"]
    q_out = q_interp.get_output_details()[0]["index"]

    batches = make_synthetic_batches(384, "neg1_1")
    diffs = []
    sample_outputs = []
    for x_nhwc in batches:
        q_interp.set_tensor(q_in, x_nhwc)
        q_interp.invoke()
        tfl_logits = q_interp.get_tensor(q_out)[0].copy()
        tfl_p_nsfw = float(softmax(tfl_logits)[0])

        x_nchw = torch.from_numpy(np.transpose(x_nhwc, (0, 3, 1, 2)))
        with torch.no_grad():
            pt_logits = model(x_nchw).numpy()[0]
        pt_p_nsfw = float(softmax(pt_logits)[0])

        diffs.append(abs(tfl_p_nsfw - pt_p_nsfw))
        sample_outputs.append(tfl_logits)

    mean_diff = float(np.mean(diffs))
    max_diff = float(np.max(diffs))
    print(f"[stage2] parity vs upstream PyTorch: mean |Δp_nsfw|={mean_diff:.5f}, max |Δp_nsfw|={max_diff:.5f}")
    assert mean_diff < 0.04 and max_diff < 0.10, f"Stage 2 parity drift too high: mean={mean_diff}, max={max_diff}"

    meta = {
        "id": "accurate_marqo384_v1",
        "filename": STAGE2_FILENAME,
        "display_name": "High-Accuracy Scanner (Marqo ViT-384)",
        "upstream": "https://huggingface.co/Marqo/nsfw-image-detection-384",
        "license": "Apache-2.0",
        "size_bytes": out_path.stat().st_size,
        "sha256": sha256_file(out_path),
        "input_size": 384,
        "normalization": "neg1_to_1",
        "labels": ["NSFW", "SFW"],
        "output_activation": "logits",
        "parity_mean_abs_diff": round(mean_diff, 6),
        "spec": spec,
    }
    return meta, np.stack(sample_outputs, axis=0)


def main() -> int:
    work_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("build/ai-models-work")
    out_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("dist/ai-models")
    work_dir.mkdir(parents=True, exist_ok=True)
    out_dir.mkdir(parents=True, exist_ok=True)

    stage1_meta, stage1_ref = export_stage1_gantman(work_dir, out_dir)
    stage2_meta, stage2_ref = export_stage2_marqo(work_dir, out_dir)

    # Save exact inputs & reference outputs so isolated ai-edge-litert==1.4.0 confirms bit-exact parity!
    np.save(work_dir / "stage1_inputs.npy", np.stack([b[0] for b in make_synthetic_batches(224, "0_1")], axis=0))
    np.save(work_dir / "stage2_inputs.npy", np.stack([b[0] for b in make_synthetic_batches(384, "neg1_1")], axis=0))
    np.save(work_dir / "stage1_ref_outputs.npy", stage1_ref)
    np.save(work_dir / "stage2_ref_outputs.npy", stage2_ref)

    manifest = {
        "schema_version": 1,
        "tag": "ai-models-v1",
        "litert_min_version": "1.4.2",
        "models": [stage1_meta, stage2_meta],
    }
    manifest_path = out_dir / "ai-models-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    sums_lines = [
        f"{stage1_meta['sha256']}  {stage1_meta['filename']}",
        f"{stage2_meta['sha256']}  {stage2_meta['filename']}",
        f"{sha256_file(manifest_path)}  {manifest_path.name}",
    ]
    (out_dir / "SHA256SUMS.txt").write_text("\n".join(sums_lines) + "\n", encoding="utf-8")

    print("=== Export complete ===")
    print((out_dir / "SHA256SUMS.txt").read_text(encoding="utf-8"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
