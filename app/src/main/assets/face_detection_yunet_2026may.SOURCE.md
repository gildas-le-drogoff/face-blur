<!--
SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors

SPDX-License-Identifier: AGPL-3.0-or-later
-->
# Face Detection Model
`face_detection_yunet_2026may.onnx` (in this folder) is the YuNet model from OpenCV Zoo, embedded as-is (no modification, no retraining).
|               |                                                                                                                                                                                   |
| ------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Project       | [opencv/opencv_zoo](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet)                                                                                   |
| Source        | <https://github.com/opencv/opencv_zoo/raw/refs/heads/main/models/face_detection_yunet/face_detection_yunet_2026may.onnx>                                                          |
| Pinned source | <https://github.com/opencv/opencv_zoo/raw/26cc381e4d2594bb9f47a26eb8fd96c94a13660d/models/face_detection_yunet/face_detection_yunet_2026may.onnx> (commit `26cc381e`, 2026-05-22) |
| License       | MIT, Copyright (c) 2020 Shiqi Yu (`face_detection_yunet_2026may.LICENSE.txt`)                                                                                                     |
| SHA-256       | `ebafce4e3c118d6554634be5c27ab333b4c047a9a8c3faf1d7cf93101c22f0f0`                                                                                                                |
| Size          | 229,738 bytes                                                                                                                                                                     |
The model is run by an ONNX engine written in Kotlin in
`app/src/main/java/fr/gildasledrogoff/faceblur/data/onnx`: no native library
and no proprietary dependency.
## Verification
Repository file (from this folder, also run by CI):
```bash
sha256sum -c face_detection_yunet_2026may.onnx.sha256
```
Comparison with upstream:
```bash
curl -sSLf https://github.com/opencv/opencv_zoo/raw/26cc381e4d2594bb9f47a26eb8fd96c94a13660d/models/face_detection_yunet/face_detection_yunet_2026may.onnx | sha256sum
```
## Updating
Replace the file, update `face_detection_yunet_2026may.onnx.sha256` and this document
(URL pinned to the new commit, hash, size).
