# JNI обращается к классам конфигурации и полям по исходным именам.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Имена JNI-функций в llm-jni.cpp закодированы полным именем класса бриджа (llama.cpp, issue #7).
-keep class org.cfm.catscriptor.native.models.LlamaNativeBridge { *; }
