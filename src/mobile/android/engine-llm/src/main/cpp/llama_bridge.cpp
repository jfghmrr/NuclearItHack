#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"

namespace {

constexpr const char *kTag = "StenokotLlama";

/** Коды итога генерации. Значения совпадают с GenerationStatus в LlamaBridge.kt. */
enum Status : jlong {
    kCompleted = 0,
    kLimitReached = 1,
    kCancelled = 2,
    kTimedOut = 3,
    kPromptTooLong = 4,
    kBadGrammar = 5,
    kFailed = 6,
};

/** Коды итога загрузки. Значения совпадают с LoadStatus в LlamaBridge.kt. */
enum LoadCode : jint {
    kLoaded = 0,
    kLoadCancelled = 1,
    kModelFailed = 2,
    kContextFailed = 3,
};

/** Позиции в массиве счётчиков. Значения совпадают с LlamaBridge.kt. */
enum StatSlot : int {
    kSlotStatus = 0,
    kSlotPromptTokens = 1,
    kSlotPromptDecoded = 2,
    kSlotGenerated = 3,
    kSlotPromptMs = 4,
    kSlotGenerationMs = 5,
    kSlotSamplingMs = 6,
    kSlotCount = 7,
};

/** Модель и контекст живут всё время работы процесса :llm, поэтому хранятся глобально. */
struct Engine {
    llama_model *model = nullptr;
    llama_context *context = nullptr;
    /** Токены, уже лежащие в KV-кэше. Общий префикс с новым запросом заново не считается. */
    std::vector<llama_token> cached;
    int32_t batch_size = 0;
};

Engine g_engine;
std::atomic<bool> g_cancel{false};
std::atomic<int64_t> g_deadline_ns{0};

int64_t now_ns() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
                   std::chrono::steady_clock::now().time_since_epoch())
            .count();
}

/** Переводит наносекунды в миллисекунды счётчика. */
jlong to_ms(int64_t ns) {
    return static_cast<jlong>(ns / 1000000);
}

bool is_deadline_passed() {
    const int64_t deadline = g_deadline_ns.load(std::memory_order_relaxed);
    return deadline > 0 && now_ns() >= deadline;
}

/** Вызывается ggml между операциями графа, поэтому отмена не ждёт конца пакета. */
bool should_abort(void *) {
    return g_cancel.load(std::memory_order_relaxed) || is_deadline_passed();
}

/** При false llama.cpp немедленно прекращает загрузку модели. */
bool should_continue_loading(float, void *) {
    return !g_cancel.load(std::memory_order_relaxed);
}

void forward_log(enum ggml_log_level level, const char *text, void *) {
    int priority;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR:
            priority = ANDROID_LOG_ERROR;
            break;
        case GGML_LOG_LEVEL_WARN:
            priority = ANDROID_LOG_WARN;
            break;
        case GGML_LOG_LEVEL_INFO:
            priority = ANDROID_LOG_INFO;
            break;
        default:
            return;
    }
    __android_log_write(priority, kTag, text);
}

/** Сбрасывает KV-кэш. Нужно после прерванного пакета: часть токенов могла в него попасть. */
void forget_cache() {
    llama_memory_clear(llama_get_memory(g_engine.context), true);
    g_engine.cached.clear();
}

std::string to_string(JNIEnv *env, jbyteArray bytes) {
    if (bytes == nullptr) {
        return {};
    }
    const jsize size = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(size), '\0');
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text) {
    const auto length = static_cast<int32_t>(text.size());
    int32_t count = -llama_tokenize(vocab, text.data(), length, nullptr, 0, false, true);
    if (count <= 0) {
        return {};
    }
    std::vector<llama_token> tokens(static_cast<size_t>(count));
    count = llama_tokenize(vocab, text.data(), length, tokens.data(), count, false, true);
    if (count < 0) {
        return {};
    }
    tokens.resize(static_cast<size_t>(count));
    return tokens;
}

struct SamplerDeleter {
    void operator()(llama_sampler *sampler) const { llama_sampler_free(sampler); }
};
using SamplerPtr = std::unique_ptr<llama_sampler, SamplerDeleter>;

/**
 * Грамматика стоит первой: иначе top_k мог бы оставить только запрещённые ею токены.
 * Цепочка владеет всеми добавленными сэмплерами.
 */
SamplerPtr make_sampler(const llama_vocab *vocab, const std::string &grammar, float temperature,
                        int top_k, float top_p, uint32_t seed) {
    SamplerPtr chain(llama_sampler_chain_init(llama_sampler_chain_default_params()));
    if (!grammar.empty()) {
        llama_sampler *rules = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (rules == nullptr) {
            return nullptr;
        }
        llama_sampler_chain_add(chain.get(), rules);
    }
    llama_sampler_chain_add(chain.get(), llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(chain.get(), llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(chain.get(), llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(chain.get(), llama_sampler_init_dist(seed));
    return chain;
}

struct BatchDeleter {
    void operator()(llama_batch_ext *batch) const { llama_batch_ext_free(batch); }
};
using BatchPtr = std::unique_ptr<llama_batch_ext, BatchDeleter>;

void fill_batch(llama_batch_ext *batch, const llama_token *tokens, int32_t count, llama_pos start,
                bool want_logits) {
    llama_batch_ext_clear(batch);
    for (int32_t i = 0; i < count; ++i) {
        const int32_t index = llama_batch_ext_add_token(batch, 0, tokens[i]);
        const llama_pos position = start + i;
        llama_batch_ext_set_pos(batch, index, &position);
    }
    if (want_logits) {
        llama_batch_ext_set_output_logits(batch, count - 1, true);
    }
}

/** Переводит код llama_process в итог генерации. */
jlong status_of_decode(int32_t code) {
    if (code != 2) {
        return kFailed;
    }
    return g_cancel.load(std::memory_order_relaxed) ? kCancelled : kTimedOut;
}

/** Считает, сколько начальных токенов запроса уже есть в кэше. Последний считается заново ради логитов. */
int32_t reusable_prefix(const std::vector<llama_token> &tokens) {
    const size_t limit = std::min(tokens.size() - 1, g_engine.cached.size());
    size_t same = 0;
    while (same < limit && tokens[same] == g_engine.cached[same]) {
        ++same;
    }
    return static_cast<int32_t>(same);
}

/** Прогоняет промпт пакетами и возвращает итог: kCompleted означает готовые логиты. */
jlong decode_prompt(llama_batch_ext *batch, const std::vector<llama_token> &tokens, jlong *stats) {
    llama_memory_t memory = llama_get_memory(g_engine.context);
    int32_t keep = reusable_prefix(tokens);
    if (!llama_memory_seq_rm(memory, 0, keep, -1)) {
        forget_cache();
        keep = 0;
    }
    g_engine.cached.resize(static_cast<size_t>(keep));

    const auto total = static_cast<int32_t>(tokens.size());
    stats[kSlotPromptDecoded] = total - keep;
    const int64_t started = now_ns();
    for (int32_t position = keep; position < total;) {
        const int32_t count = std::min(g_engine.batch_size, total - position);
        const bool last = position + count == total;
        fill_batch(batch, tokens.data() + position, count, position, last);
        const int32_t code = llama_process(g_engine.context, LLAMA_PROCESS_TYPE_DECODE, batch);
        if (code != 0) {
            stats[kSlotPromptMs] = to_ms(now_ns() - started);
            forget_cache();
            return status_of_decode(code);
        }
        g_engine.cached.insert(g_engine.cached.end(), tokens.begin() + position,
                               tokens.begin() + position + count);
        position += count;
    }
    stats[kSlotPromptMs] = to_ms(now_ns() - started);
    return kCompleted;
}

/** Выбирает токены до конца грамматики или предела. Байты токена могут резать символ UTF-8. */
jlong decode_answer(llama_batch_ext *batch, llama_sampler *sampler, int32_t limit,
                    std::string *answer, jlong *stats) {
    const llama_vocab *vocab = llama_model_get_vocab(g_engine.model);
    auto position = static_cast<llama_pos>(g_engine.cached.size());
    int64_t sampling_ns = 0;
    int64_t decoding_ns = 0;
    jlong status = kLimitReached;

    for (int32_t produced = 0; produced < limit; ++produced) {
        if (should_abort(nullptr)) {
            status = g_cancel.load(std::memory_order_relaxed) ? kCancelled : kTimedOut;
            break;
        }
        const int64_t sample_start = now_ns();
        llama_token token = llama_sampler_sample(sampler, g_engine.context, -1);
        sampling_ns += now_ns() - sample_start;
        if (llama_vocab_is_eog(vocab, token)) {
            status = kCompleted;
            break;
        }

        char piece[256];
        const int32_t length = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
        if (length < 0) {
            status = kFailed;
            break;
        }
        answer->append(piece, static_cast<size_t>(length));
        stats[kSlotGenerated] = produced + 1;
        if (produced + 1 == limit) {
            break;
        }

        const int64_t decode_start = now_ns();
        fill_batch(batch, &token, 1, position, true);
        const int32_t code = llama_process(g_engine.context, LLAMA_PROCESS_TYPE_DECODE, batch);
        decoding_ns += now_ns() - decode_start;
        if (code != 0) {
            forget_cache();
            status = status_of_decode(code);
            break;
        }
        g_engine.cached.push_back(token);
        ++position;
    }
    stats[kSlotGenerationMs] = to_ms(decoding_ns);
    stats[kSlotSamplingMs] = to_ms(sampling_ns);
    return status;
}

}

extern "C" JNIEXPORT jstring JNICALL
Java_ru_stenokot_llm_LlamaBridge_nativeInit(JNIEnv *env, jobject, jstring native_lib_dir) {
    llama_log_set(forward_log, nullptr);
    const char *path = env->GetStringUTFChars(native_lib_dir, nullptr);
    ggml_backend_load_all_from_path(path);
    env->ReleaseStringUTFChars(native_lib_dir, path);
    llama_backend_init();

    const char *info = llama_print_system_info();
    __android_log_print(ANDROID_LOG_INFO, kTag, "system_info: %s", info);
    return env->NewStringUTF(info);
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_stenokot_llm_LlamaBridge_nativeLoad(JNIEnv *env, jobject, jstring model_path, jint n_ctx,
                                            jint n_batch, jint n_threads) {
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
    model_params.progress_callback = should_continue_loading;

    const char *path = env->GetStringUTFChars(model_path, nullptr);
    llama_model *model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);
    if (model == nullptr) {
        return g_cancel.load(std::memory_order_relaxed) ? kLoadCancelled : kModelFailed;
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(n_ctx);
    context_params.n_batch = static_cast<uint32_t>(n_batch);
    context_params.n_ubatch = static_cast<uint32_t>(n_batch);
    context_params.n_seq_max = 1;
    context_params.n_threads = n_threads;
    context_params.n_threads_batch = n_threads;
    context_params.abort_callback = should_abort;
    context_params.no_perf = true;

    llama_context *context = llama_init_from_model(model, context_params);
    if (context == nullptr) {
        llama_model_free(model);
        return kContextFailed;
    }
    g_engine.model = model;
    g_engine.context = context;
    g_engine.batch_size = n_batch;
    g_engine.cached.clear();
    return kLoaded;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ru_stenokot_llm_LlamaBridge_nativeGenerate(JNIEnv *env, jobject, jbyteArray prompt,
                                                jbyteArray grammar, jint n_predict, jfloat temperature,
                                                jint top_k, jfloat top_p, jint seed, jlong timeout_ms,
                                                jlongArray stats_out) {
    jlong stats[kSlotCount] = {};
    std::string answer;
    const auto finish = [&](jlong status) {
        stats[kSlotStatus] = status;
        env->SetLongArrayRegion(stats_out, 0, kSlotCount, stats);
        g_deadline_ns.store(0, std::memory_order_relaxed);
        const auto size = static_cast<jsize>(answer.size());
        jbyteArray out = env->NewByteArray(size);
        if (out != nullptr) {
            env->SetByteArrayRegion(out, 0, size, reinterpret_cast<const jbyte *>(answer.data()));
        }
        return out;
    };

    if (g_engine.context == nullptr) {
        return finish(kFailed);
    }
    g_deadline_ns.store(timeout_ms > 0 ? now_ns() + timeout_ms * 1000000 : 0,
                        std::memory_order_relaxed);

    const llama_vocab *vocab = llama_model_get_vocab(g_engine.model);
    const std::vector<llama_token> tokens = tokenize(vocab, to_string(env, prompt));
    stats[kSlotPromptTokens] = static_cast<jlong>(tokens.size());
    if (tokens.empty()) {
        return finish(kFailed);
    }
    const auto capacity = static_cast<int64_t>(llama_n_ctx(g_engine.context));
    if (static_cast<int64_t>(tokens.size()) + n_predict > capacity) {
        return finish(kPromptTooLong);
    }

    SamplerPtr sampler = make_sampler(vocab, to_string(env, grammar), temperature, top_k, top_p,
                                      static_cast<uint32_t>(seed));
    if (sampler == nullptr) {
        return finish(kBadGrammar);
    }

    BatchPtr batch(llama_batch_ext_init(g_engine.context));
    const jlong prompt_status = decode_prompt(batch.get(), tokens, stats);
    if (prompt_status != kCompleted) {
        return finish(prompt_status);
    }
    return finish(decode_answer(batch.get(), sampler.get(), n_predict, &answer, stats));
}

extern "C" JNIEXPORT void JNICALL
Java_ru_stenokot_llm_LlamaBridge_nativeCancel(JNIEnv *, jobject) {
    g_cancel.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_ru_stenokot_llm_LlamaBridge_nativeResetCancel(JNIEnv *, jobject) {
    g_cancel.store(false, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_ru_stenokot_llm_LlamaBridge_nativeUnload(JNIEnv *, jobject) {
    if (g_engine.context != nullptr) {
        llama_free(g_engine.context);
        g_engine.context = nullptr;
    }
    if (g_engine.model != nullptr) {
        llama_model_free(g_engine.model);
        g_engine.model = nullptr;
    }
    g_engine.cached.clear();
    llama_backend_free();
}
