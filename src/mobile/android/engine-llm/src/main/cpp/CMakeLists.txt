# Обвязка JNI и llama.cpp из подмодуля third_party. Варианты CPU выбираются при запуске.
cmake_minimum_required(VERSION 3.31.6)

project(stenokot_llm LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../../../third_party/llama.cpp)
if(NOT EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(FATAL_ERROR "Нет подмодуля llama.cpp, выполните: git submodule update --init --depth 1")
endif()

add_subdirectory(${LLAMA_SRC} build-llama)

add_library(stenokot_llm SHARED llama_bridge.cpp)
target_link_libraries(stenokot_llm PRIVATE llama ggml android log)
