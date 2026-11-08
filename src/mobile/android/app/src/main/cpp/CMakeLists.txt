# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

# Declares the project name. The project name can be accessed via ${ PROJECT_NAME},
# Since this is the top level CMakeLists.txt, the project name is also accessible
# with ${CMAKE_PROJECT_NAME} (both CMake variables are in-sync within the top level
# build script scope).
project("catscriptor")

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.
#
# In this top level CMakeLists.txt, ${CMAKE_PROJECT_NAME} is used to define
# the target library name; in the sub-module's CMakeLists.txt, ${PROJECT_NAME}
# is preferred for the same purpose.
#
# In order to load a library into your app from Java/Kotlin, you must call
# System.loadLibrary() and pass the name of the library defined here;
# for GameActivity/NativeActivity derived applications, the same library name must be
# used in the AndroidManifest.xml file.
add_library(${CMAKE_PROJECT_NAME} SHARED
        # List C/C++ source files with relative paths to this CMakeLists.txt.
        native-lib.cpp)

# Specifies libraries CMake should link to your target library. You
# can link libraries from various origins, such as libraries defined in this
# build script, prebuilt third-party libraries, or Android system libraries.
target_link_libraries(${CMAKE_PROJECT_NAME}
        # List libraries link to the target library
        android
        log)

# --- Локальная LLM (issue #7): закреплённый vendored llama.cpp, см. dependencies/llama-cpp.properties.
# Скачивается и проверяется по SHA-256 скриптом scripts/prepare_models.py; в git не коммитится.
set(CATSCRIPTOR_LLAMA_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../dependencies/llama.cpp)
if (NOT EXISTS ${CATSCRIPTOR_LLAMA_ROOT}/CMakeLists.txt)
    message(FATAL_ERROR "Исходники llama.cpp не найдены: выполните python3 scripts/prepare_models.py из корня проекта")
endif ()

# AGP собирает debug APK без флага оптимизации: clang работает на -O0 по умолчанию, и llama.cpp
# в десятки раз медленнее (замер на vivo V2538: ~7 с/токен против ~0,2 с после включения -O2).
# Отладочную информацию сохраняем, оптимизацию добавляем — девайс-проверки идут на debug APK.
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O2")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O2")

# CPU-only статическая сборка: только C API, без jinja-чата, без OpenMP/llamafile/KleidiAI.
# GGML_CPU_KLEIDIAI=OFF важен: иначе CMake скачает исходники KleidiAI через FetchContent при конфигурации.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
add_subdirectory(${CATSCRIPTOR_LLAMA_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/llama-build)

# Отдельная JNI-библиотека: существующий native-lib не трогаем.
add_library(llm-jni SHARED llm-jni.cpp)
target_include_directories(llm-jni PRIVATE
        ${CATSCRIPTOR_LLAMA_ROOT}/include
        ${CATSCRIPTOR_LLAMA_ROOT}/ggml/include)
target_link_libraries(llm-jni PRIVATE llama android log)