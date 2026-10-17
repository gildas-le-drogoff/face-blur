# Le code natif d'ONNX Runtime retrouve ses classes Java par nom (JNI FindClass) :
# les règles embarquées dans l'AAR ne couvrent que la télémétrie.
-keep class ai.onnxruntime.** { *; }
