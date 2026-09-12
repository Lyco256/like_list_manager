#include <jni.h>

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "usearch.h"

static usearch_index_t index_from_handle(jlong handle) {
    return (usearch_index_t)(uintptr_t)handle;
}

static jlong handle_from_index(usearch_index_t index) {
    return (jlong)(uintptr_t)index;
}

static void throw_illegal_state(JNIEnv *env, const char *message) {
    jclass exception = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (exception != NULL) {
        (*env)->ThrowNew(env, exception, message);
    }
}

static void throw_usearch_error(JNIEnv *env, const char *operation, usearch_error_t error) {
    char message[512];
    const char *detail = error != NULL ? error : "unknown USearch error";
    (void)snprintf(message, sizeof(message), "%s: %s", operation, detail);
    throw_illegal_state(env, message);
}

static int parse_metric(const char *metric, usearch_metric_kind_t *result) {
    if (strcmp(metric, "cos") == 0) {
        *result = usearch_metric_cos_k;
        return 1;
    }
    return 0;
}

static int parse_quantization(const char *quantization, usearch_scalar_kind_t *result) {
    if (strcmp(quantization, "f32") == 0) {
        *result = usearch_scalar_f32_k;
        return 1;
    }
    return 0;
}

static int check_positive_long(JNIEnv *env, jlong value, const char *label) {
    if (value <= 0) {
        char message[128];
        (void)snprintf(message, sizeof(message), "%s must be positive", label);
        throw_illegal_state(env, message);
        return 0;
    }
    return 1;
}

JNIEXPORT jlong JNICALL Java_cloud_unum_usearch_Index_c_1create(
        JNIEnv *env,
        jclass unused,
        jstring metric,
        jstring quantization,
        jlong dimensions,
        jlong capacity,
        jlong connectivity,
        jlong expansion_add,
        jlong expansion_search) {
    (void)unused;
    if (metric == NULL || quantization == NULL || dimensions <= 0 || capacity < 0 ||
        connectivity <= 0 || expansion_add <= 0 || expansion_search <= 0) {
        throw_illegal_state(env, "Invalid USearch index configuration");
        return 0;
    }

    const char *metric_chars = (*env)->GetStringUTFChars(env, metric, NULL);
    const char *quantization_chars = (*env)->GetStringUTFChars(env, quantization, NULL);
    if (metric_chars == NULL || quantization_chars == NULL) {
        if (metric_chars != NULL) (*env)->ReleaseStringUTFChars(env, metric, metric_chars);
        if (quantization_chars != NULL) (*env)->ReleaseStringUTFChars(env, quantization, quantization_chars);
        return 0;
    }

    usearch_metric_kind_t metric_kind;
    usearch_scalar_kind_t scalar_kind;
    if (!parse_metric(metric_chars, &metric_kind) || !parse_quantization(quantization_chars, &scalar_kind)) {
        (*env)->ReleaseStringUTFChars(env, metric, metric_chars);
        (*env)->ReleaseStringUTFChars(env, quantization, quantization_chars);
        throw_illegal_state(env, "Unsupported USearch metric or quantization");
        return 0;
    }
    (*env)->ReleaseStringUTFChars(env, metric, metric_chars);
    (*env)->ReleaseStringUTFChars(env, quantization, quantization_chars);

    usearch_init_options_t options;
    memset(&options, 0, sizeof(options));
    options.metric_kind = metric_kind;
    options.metric = NULL;
    options.quantization = scalar_kind;
    options.dimensions = (size_t)dimensions;
    options.connectivity = (size_t)connectivity;
    options.expansion_add = (size_t)expansion_add;
    options.expansion_search = (size_t)expansion_search;
    options.multi = false;

    usearch_error_t error = NULL;
    usearch_index_t index = usearch_init(&options, &error);
    if (index == NULL) {
        throw_usearch_error(env, "usearch_init", error);
        return 0;
    }

    if (capacity > 0) {
        usearch_change_threads_add(index, 1, &error);
        usearch_change_threads_search(index, 1, &error);
        usearch_reserve(index, (size_t)capacity, &error);
        if (error != NULL) {
            usearch_free(index, NULL);
            throw_usearch_error(env, "usearch_reserve", error);
            return 0;
        }
    }
    return handle_from_index(index);
}

JNIEXPORT void JNICALL Java_cloud_unum_usearch_Index_c_1destroy(
        JNIEnv *env,
        jclass unused,
        jlong handle) {
    (void)unused;
    usearch_error_t error = NULL;
    usearch_free(index_from_handle(handle), &error);
    if (error != NULL) throw_usearch_error(env, "usearch_free", error);
}

JNIEXPORT jlong JNICALL Java_cloud_unum_usearch_Index_c_1size(
        JNIEnv *env,
        jclass unused,
        jlong handle) {
    (void)unused;
    usearch_error_t error = NULL;
    size_t size = usearch_size(index_from_handle(handle), &error);
    if (error != NULL) {
        throw_usearch_error(env, "usearch_size", error);
        return 0;
    }
    return (jlong)size;
}

JNIEXPORT jlong JNICALL Java_cloud_unum_usearch_Index_c_1connectivity(
        JNIEnv *env,
        jclass unused,
        jlong handle) {
    (void)unused;
    usearch_error_t error = NULL;
    size_t value = usearch_connectivity(index_from_handle(handle), &error);
    if (error != NULL) {
        throw_usearch_error(env, "usearch_connectivity", error);
        return 0;
    }
    return (jlong)value;
}

JNIEXPORT jlong JNICALL Java_cloud_unum_usearch_Index_c_1dimensions(
        JNIEnv *env,
        jclass unused,
        jlong handle) {
    (void)unused;
    usearch_error_t error = NULL;
    size_t value = usearch_dimensions(index_from_handle(handle), &error);
    if (error != NULL) {
        throw_usearch_error(env, "usearch_dimensions", error);
        return 0;
    }
    return (jlong)value;
}

JNIEXPORT jlong JNICALL Java_cloud_unum_usearch_Index_c_1capacity(
        JNIEnv *env,
        jclass unused,
        jlong handle) {
    (void)unused;
    usearch_error_t error = NULL;
    size_t value = usearch_capacity(index_from_handle(handle), &error);
    if (error != NULL) {
        throw_usearch_error(env, "usearch_capacity", error);
        return 0;
    }
    return (jlong)value;
}

JNIEXPORT void JNICALL Java_cloud_unum_usearch_Index_c_1reserve(
        JNIEnv *env,
        jclass unused,
        jlong handle,
        jlong capacity,
        jlong threads_add,
        jlong threads_search) {
    (void)unused;
    if (!check_positive_long(env, capacity, "capacity") ||
        !check_positive_long(env, threads_add, "threadsAdd") ||
        !check_positive_long(env, threads_search, "threadsSearch")) {
        return;
    }
    usearch_error_t error = NULL;
    usearch_index_t index = index_from_handle(handle);
    usearch_change_threads_add(index, (size_t)threads_add, &error);
    usearch_change_threads_search(index, (size_t)threads_search, &error);
    usearch_reserve(index, (size_t)capacity, &error);
    if (error != NULL) throw_usearch_error(env, "usearch_reserve", error);
}

JNIEXPORT void JNICALL Java_cloud_unum_usearch_Index_c_1add_1f32(
        JNIEnv *env,
        jclass unused,
        jlong handle,
        jlong key,
        jfloatArray vector) {
    (void)unused;
    if (vector == NULL) {
        throw_illegal_state(env, "USearch vector is null");
        return;
    }
    usearch_index_t index = index_from_handle(handle);
    usearch_error_t error = NULL;
    size_t dimensions = usearch_dimensions(index, &error);
    if (error != NULL) {
        throw_usearch_error(env, "usearch_dimensions", error);
        return;
    }
    if ((size_t)(*env)->GetArrayLength(env, vector) != dimensions) {
        throw_illegal_state(env, "USearch vector dimensions mismatch");
        return;
    }
    jfloat *values = (*env)->GetFloatArrayElements(env, vector, NULL);
    if (values == NULL) return;
    usearch_add(index, (usearch_key_t)(uint64_t)key, values, usearch_scalar_f32_k, &error);
    (*env)->ReleaseFloatArrayElements(env, vector, values, JNI_ABORT);
    if (error != NULL) throw_usearch_error(env, "usearch_add", error);
}

JNIEXPORT jlongArray JNICALL Java_cloud_unum_usearch_Index_c_1search_1f32(
        JNIEnv *env,
        jclass unused,
        jlong handle,
        jfloatArray query,
        jlong count) {
    (void)unused;
    if (query == NULL || count < 0 || count > 2147483647L) {
        throw_illegal_state(env, "Invalid USearch search request");
        return NULL;
    }
    if (count == 0) return (*env)->NewLongArray(env, 0);

    usearch_index_t index = index_from_handle(handle);
    usearch_error_t error = NULL;
    size_t dimensions = usearch_dimensions(index, &error);
    if (error != NULL) {
        throw_usearch_error(env, "usearch_dimensions", error);
        return NULL;
    }
    if ((size_t)(*env)->GetArrayLength(env, query) != dimensions) {
        throw_illegal_state(env, "USearch query dimensions mismatch");
        return NULL;
    }

    jfloat *values = (*env)->GetFloatArrayElements(env, query, NULL);
    if (values == NULL) return NULL;
    size_t requested = (size_t)count;
    usearch_key_t *keys = (usearch_key_t *)calloc(requested, sizeof(usearch_key_t));
    usearch_distance_t *distances = (usearch_distance_t *)calloc(requested, sizeof(usearch_distance_t));
    if (keys == NULL || distances == NULL) {
        free(keys);
        free(distances);
        (*env)->ReleaseFloatArrayElements(env, query, values, JNI_ABORT);
        throw_illegal_state(env, "Unable to allocate USearch result buffers");
        return NULL;
    }

    size_t found = usearch_search(
        index,
        values,
        usearch_scalar_f32_k,
        requested,
        keys,
        distances,
        &error);
    (*env)->ReleaseFloatArrayElements(env, query, values, JNI_ABORT);
    free(distances);
    if (error != NULL || found > requested) {
        free(keys);
        if (error != NULL) throw_usearch_error(env, "usearch_search", error);
        else throw_illegal_state(env, "USearch returned too many results");
        return NULL;
    }

    jlongArray result = (*env)->NewLongArray(env, (jsize)found);
    if (result != NULL) {
        jlong *java_keys = (jlong *)malloc(found * sizeof(jlong));
        if (java_keys == NULL && found > 0) {
            (*env)->DeleteLocalRef(env, result);
            free(keys);
            throw_illegal_state(env, "Unable to allocate USearch key results");
            return NULL;
        }
        for (size_t index = 0; index < found; index++) {
            java_keys[index] = (jlong)(uint64_t)keys[index];
        }
        if (found > 0) (*env)->SetLongArrayRegion(env, result, 0, (jsize)found, java_keys);
        free(java_keys);
    }
    free(keys);
    return result;
}

JNIEXPORT jstring JNICALL Java_cloud_unum_usearch_Index_c_1library_1version(
        JNIEnv *env,
        jclass unused) {
    (void)unused;
    const char *version = usearch_version();
    return (*env)->NewStringUTF(env, version != NULL ? version : "");
}
