# Имена нативных методов связаны с функциями JNI
-keepclasseswithmembernames class ru.stenokot.llm.LlamaBridge {
    native <methods>;
}
