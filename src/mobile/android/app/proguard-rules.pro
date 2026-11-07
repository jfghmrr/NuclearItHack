# Классы sherpa-onnx читаются из нативного кода по именам полей.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# Мост llama.cpp вызывается из нативного кода.
-keep class ru.stenokot.llm.** { *; }
