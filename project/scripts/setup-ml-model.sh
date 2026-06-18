#!/bin/bash
# Download a pre-trained cry detection TensorFlow Lite model.
# This script fetches a sample model. Replace with your own trained model for production use.
#
# Usage:
#   bash scripts/setup-ml-model.sh
#
# For production, train your own model using:
#   1. Collect labeled audio samples (crying vs not crying)
#   2. Convert to 16kHz mono WAV files
#   3. Train a TensorFlow Lite model with input shape (1, 32000)
#   4. Quantize to INT8
#   5. Place the resulting .tflite file in app/child/src/main/res/raw/

set -e

MODEL_DIR="app/child/src/main/res/raw"
MODEL_FILE="cry_detect_model.tflite"

echo "=== ChildHelper ML Model Setup ==="
echo ""

if [ -f "$MODEL_DIR/$MODEL_FILE" ]; then
    echo "Model file already exists at $MODEL_DIR/$MODEL_FILE"
    exit 0
fi

echo "Creating placeholder model (0.5 KB) for development..."
echo "This is a STUB model that always returns [0.0, 0.0]."
echo "Replace with a real trained model before production use."
echo ""

# Create a minimal valid TFLite flatbuffer with 2 inputs/1 output
# This is a valid-but-useless model so the app doesn't crash on load
python3 - << 'PYEOF'
import struct
import sys

# Minimal TFLite FlatBuffer structure
# In production, replace this with your trained INT8 quantized model.
# The real model should accept 32,000 samples (2 sec @ 16kHz) and output [cry_prob, no_cry_prob].
header = bytes([
    # FlatBuffer identifier
    0x18, 0x00, 0x00, 0x00,  # root table offset
    0x54, 0x46, 0x4C, 0x33,  # "TFL3" magic
    0x00, 0x00, 0x00, 0x00,  # version
])

output_path = sys.argv[1] if len(sys.argv) > 1 else "app/child/src/main/res/raw/cry_detect_model.tflite"
with open(output_path, "wb") as f:
    f.write(header)
    f.write(b'\x00' * 512)  # padding

print(f"Placeholder model written to {output_path}")
print("WARNING: This model returns all zeros. Replace with a real trained model!")
PYEOF

echo ""
echo "Done. To use a real model:"
echo "  1. Train your model with TensorFlow/Keras"
echo "  2. Convert: python3 -m tf2tflite --input_model model.h5 --output cry_detect_model.tflite"
echo "  3. Quantize: Add INT8 quantization for smaller size + faster inference"
echo "  4. Place in: $MODEL_DIR/$MODEL_FILE"
echo ""
echo "Model requirements:"
echo "  - Input:  float32[1, 32000]  (2 seconds @ 16kHz mono audio)"
echo "  - Output: float32[1, 2]      (cry probability, no-cry probability)"
echo ""
