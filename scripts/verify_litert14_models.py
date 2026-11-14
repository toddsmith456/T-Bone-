#!/usr/bin/env python3
"""
Standalone verifier for exported .tflite add-on models against LiteRT 1.4.0
(matching Android com.google.ai.edge.litert:litert:1.4.2).
Requires only numpy + ai-edge-litert==1.4.0.
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
from ai_edge_litert.interpreter import Interpreter


STAGE1_FILENAME = "nsfw_stage1_mnv2_int8.tflite"
STAGE2_FILENAME = "nsfw_stage2_marqo384_fp16.tflite"


def verify_model(model_path: Path, inputs: np.ndarray, ref_outputs: np.ndarray) -> None:
    interp = Interpreter(model_path=str(model_path))
    interp.allocate_tensors()
    in_det = interp.get_input_details()[0]
    out_det = interp.get_output_details()[0]

    max_err = 0.0
    for idx in range(len(inputs)):
        x = np.expand_dims(inputs[idx], axis=0).astype(in_det["dtype"])
        interp.set_tensor(in_det["index"], x)
        interp.invoke()
        y = interp.get_tensor(out_det["index"])[0]
        err = float(np.max(np.abs(y - ref_outputs[idx])))
        max_err = max(max_err, err)

    print(
        f"[litert 1.4.0] {model_path.name}: OK "
        f"(in={list(in_det['shape'])} {np.dtype(in_det['dtype']).name}, "
        f"out={list(out_det['shape'])} {np.dtype(out_det['dtype']).name}, "
        f"max_diff_vs_tf={max_err:.6f})"
    )
    if max_err > 1e-2:
        raise RuntimeError(f"LiteRT 1.4.0 mismatch for {model_path.name}: max_err={max_err}")


def main() -> int:
    work_dir = Path(sys.argv[1])
    out_dir = Path(sys.argv[2])

    verify_model(
        out_dir / STAGE1_FILENAME,
        np.load(work_dir / "stage1_inputs.npy"),
        np.load(work_dir / "stage1_ref_outputs.npy"),
    )
    verify_model(
        out_dir / STAGE2_FILENAME,
        np.load(work_dir / "stage2_inputs.npy"),
        np.load(work_dir / "stage2_ref_outputs.npy"),
    )
    print("[litert 1.4.0] ALL MODELS VERIFIED COMPATIBLE WITH ANDROID LITERT 1.4.2")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
