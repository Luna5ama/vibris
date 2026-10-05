"""Independent NumPy/SciPy reference for the real PNG8 comparison QA response."""
import json
import pathlib
import sys

import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter


def main():
    response = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
    result = response["result"]["structuredContent"]["result"]
    with Image.open(result["a"]) as image:
        a = np.asarray(image)
    with Image.open(result["b"]) as image:
        b = np.asarray(image)
    assert a.dtype == b.dtype == np.uint8, "This reference requires PNG8 inputs"
    a, b = a.astype(np.float64) / 255, b.astype(np.float64) / 255
    error = np.abs(b - a)
    reference = {"mean_absolute_error": error.mean(),
                 "root_mean_square_error": np.sqrt(np.square(error).mean()),
                 "max_absolute_error": error.max(),
                 "p95_absolute_error": np.quantile(error, .95),
                 "relative_mse": np.square(error).sum() / np.square(a).sum(),
                 "threshold_pixel_ratio": np.any(error > 0, axis=-1).mean()}
    left, right = a[..., :3] @ [.2126, .7152, .0722], b[..., :3] @ [.2126, .7152, .0722]
    smooth = lambda value: gaussian_filter(value, 1.5, radius=5, mode="mirror")
    ma, mb = smooth(left), smooth(right)
    va, vb = np.maximum(0, smooth(left * left) - ma * ma), np.maximum(0, smooth(right * right) - mb * mb)
    covariance = smooth(left * right) - ma * mb
    reference["ssim"] = (((2 * ma * mb + .01**2) * (2 * covariance + .03**2)) /
                         ((ma * ma + mb * mb + .01**2) * (va + vb + .03**2))).mean()
    differences = {key: abs(float(value) - result[key]) for key, value in reference.items()}
    assert max(differences.values()) < 1e-12, differences
    print(json.dumps({"passed": True, "absolute_errors": differences}))


if __name__ == "__main__":
    main()
