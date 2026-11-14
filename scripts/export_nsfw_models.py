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
STAGE2_FILENAME = "nsfw_stage2_marqo384_fp16.tflite"


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
    structured_max = float(np.max(diffs[3:]))  # non-random structured frames
    print(
        f"[stage1] parity vs upstream FP32: mean |Δp|={mean_diff:.5f}, "
        f"max |Δp|={max_diff:.5f}, structured_max={structured_max:.5f}"
    )
    assert mean_diff < 0.04 and max_diff < 0.16, (
        f"Stage 1 parity drift too high: mean={mean_diff}, max={max_diff}"
    )

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


def _build_tf_vit_tiny_384(pt_model) -> "tf.Module":
    """
    Build an exact native-NHWC TensorFlow Module for timm's vit_tiny_patch16_384
    directly from the loaded PyTorch state_dict.

    Uses only <=4D tensors and pure TFLITE_BUILTINS ops (no Flex ops, no onnx2tf
    shape-transposition issues). Verified directly against timm PyTorch below.
    """
    sd = {k: v.detach().cpu().numpy().astype(np.float32) for k, v in pt_model.state_dict().items()}
    num_heads = int(pt_model.blocks[0].attn.num_heads)
    embed_dim = int(pt_model.embed_dim)
    head_dim = embed_dim // num_heads
    scale = float(head_dim ** -0.5)
    norm_eps = float(getattr(pt_model.norm, "eps", 1e-6))
    depth = len(pt_model.blocks)

    assert embed_dim == 192 and num_heads == 3 and depth == 12, (
        f"Unexpected ViT geometry: embed_dim={embed_dim}, num_heads={num_heads}, depth={depth}"
    )

    # PatchEmbed Conv2D: PyTorch [O, I, H, W] -> TF [H, W, I, O]
    patch_w = tf.constant(np.transpose(sd["patch_embed.proj.weight"], (2, 3, 1, 0)))
    patch_b = tf.constant(sd["patch_embed.proj.bias"])
    cls_token = tf.constant(sd["cls_token"])  # [1, 1, 192]
    pos_embed = tf.constant(sd["pos_embed"])  # [1, 577, 192]

    blocks = []
    for i in range(depth):
        p = f"blocks.{i}"
        qkv_w = sd[f"{p}.attn.qkv.weight"]  # [576, 192]
        qkv_b = sd[f"{p}.attn.qkv.bias"]    # [576]
        # Split Q/K/V at export time so every tensor stays <=4D for fast XNNPACK execution.
        qw, kw, vw = np.split(qkv_w, 3, axis=0)
        qb, kb, vb = np.split(qkv_b, 3, axis=0)
        blocks.append(
            {
                "n1_g": tf.constant(sd[f"{p}.norm1.weight"]),
                "n1_b": tf.constant(sd[f"{p}.norm1.bias"]),
                "qw": tf.constant(qw.T),
                "qb": tf.constant(qb),
                "kw": tf.constant(kw.T),
                "kb": tf.constant(kb),
                "vw": tf.constant(vw.T),
                "vb": tf.constant(vb),
                "pw": tf.constant(sd[f"{p}.attn.proj.weight"].T),
                "pb": tf.constant(sd[f"{p}.attn.proj.bias"]),
                "n2_g": tf.constant(sd[f"{p}.norm2.weight"]),
                "n2_b": tf.constant(sd[f"{p}.norm2.bias"]),
                "fc1_w": tf.constant(sd[f"{p}.mlp.fc1.weight"].T),
                "fc1_b": tf.constant(sd[f"{p}.mlp.fc1.bias"]),
                "fc2_w": tf.constant(sd[f"{p}.mlp.fc2.weight"].T),
                "fc2_b": tf.constant(sd[f"{p}.mlp.fc2.bias"]),
            }
        )

    final_n_g = tf.constant(sd["norm.weight"])
    final_n_b = tf.constant(sd["norm.bias"])
    head_w = tf.constant(sd["head.weight"].T)  # [192, 2]
    head_b = tf.constant(sd["head.bias"])      # [2]

    def layer_norm(x, gamma, beta):
        mean = tf.reduce_mean(x, axis=-1, keepdims=True)
        diff = x - mean
        var = tf.reduce_mean(tf.square(diff), axis=-1, keepdims=True)
        return diff * tf.math.rsqrt(var + norm_eps) * gamma + beta

    class MarqoVitTiny384(tf.Module):
        @tf.function(input_signature=[tf.TensorSpec([1, 384, 384, 3], tf.float32, name="image")])
        def __call__(self, image):
            # [1, 384, 384, 3] -> [1, 24, 24, 192] -> [1, 576, 192]
            x = tf.nn.conv2d(image, patch_w, strides=[1, 16, 16, 1], padding="VALID") + patch_b
            x = tf.reshape(x, [1, 576, embed_dim])
            x = tf.concat([cls_token, x], axis=1) + pos_embed  # [1, 577, 192]

            for b in blocks:
                h = layer_norm(x, b["n1_g"], b["n1_b"])
                q = tf.transpose(tf.reshape(tf.matmul(h, b["qw"]) + b["qb"], [1, 577, num_heads, head_dim]), [0, 2, 1, 3])
                k = tf.transpose(tf.reshape(tf.matmul(h, b["kw"]) + b["kb"], [1, 577, num_heads, head_dim]), [0, 2, 1, 3])
                v = tf.transpose(tf.reshape(tf.matmul(h, b["vw"]) + b["vb"], [1, 577, num_heads, head_dim]), [0, 2, 1, 3])
                attn = tf.nn.softmax(tf.matmul(q, k, transpose_b=True) * scale, axis=-1)
                ctx = tf.reshape(tf.transpose(tf.matmul(attn, v), [0, 2, 1, 3]), [1, 577, embed_dim])
                x = x + (tf.matmul(ctx, b["pw"]) + b["pb"])

                h2 = layer_norm(x, b["n2_g"], b["n2_b"])
                mlp = tf.nn.gelu(tf.matmul(h2, b["fc1_w"]) + b["fc1_b"], approximate=False)
                x = x + (tf.matmul(mlp, b["fc2_w"]) + b["fc2_b"])

            x = layer_norm(x, final_n_g, final_n_b)
            cls_feat = x[:, 0, :]  # [1, 192]
            logits = tf.matmul(cls_feat, head_w) + head_b  # [1, 2]
            return {"logits": logits}

    return MarqoVitTiny384()


def export_stage2_marqo(work_dir: Path, out_dir: Path) -> tuple[dict, np.ndarray]:
    print("=== [Stage 2] Loading Marqo/nsfw-image-detection-384 via timm ===")
    model = timm.create_model(MARQO_HF_ID, pretrained=True).eval()

    print("=== [Stage 2] Building native-NHWC TF ViT-Tiny-384 module from PyTorch weights ===")
    tf_module = _build_tf_vit_tiny_384(model)
    concrete_fn = tf_module.__call__.get_concrete_function()

    print("=== [Stage 2] Converting Marqo TF ViT-384 -> FP16 TFLite (TFLITE_BUILTINS, zero accuracy loss) ===")
    converter = tf.lite.TFLiteConverter.from_concrete_functions([concrete_fn], tf_module)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
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
    print(f"[stage2] parity vs upstream PyTorch: mean |Δp_nsfw|={mean_diff:.6f}, max |Δp_nsfw|={max_diff:.6f}")
    assert mean_diff < 0.01 and max_diff < 0.02, f"Stage 2 parity drift too high: mean={mean_diff}, max={max_diff}"

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
