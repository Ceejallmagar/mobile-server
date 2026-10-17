#include <jni.h>
#include <node.h>
#include <cstdlib>
#include <string>
#include <vector>
#include <unistd.h>
#include <fcntl.h>
#include <cerrno>
#include <android/log.h>

extern "C" JNIEXPORT jint JNICALL
Java_dev_pockettunnel_NodeEngine_redirectOutput(JNIEnv *env, jobject, jstring path) {
    const char *target = env->GetStringUTFChars(path, nullptr);
    int fd = open(target, O_WRONLY | O_CREAT | O_APPEND, 0644);
    const int error = fd < 0 ? errno : 0;
    if (fd >= 0) {
        dup2(fd, STDOUT_FILENO);
        dup2(fd, STDERR_FILENO);
        if (fd > STDERR_FILENO) close(fd);
    } else {
        __android_log_print(ANDROID_LOG_ERROR, "PocketTunnelRuntime", "Could not open Node output log %s: %d", target, error);
    }
    env->ReleaseStringUTFChars(path, target);
    return error;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_pockettunnel_NodeEngine_startNode(JNIEnv *env, jobject, jobjectArray java_args, jstring cwd, jstring port, jstring app_home, jstring temp_directory) {
    const char *working_dir = env->GetStringUTFChars(cwd, nullptr);
    const char *port_value = env->GetStringUTFChars(port, nullptr);
    const char *home_value = env->GetStringUTFChars(app_home, nullptr);
    const char *temp_value = env->GetStringUTFChars(temp_directory, nullptr);
    if (chdir(working_dir) != 0) {
        const int error = errno;
        __android_log_print(ANDROID_LOG_ERROR, "PocketTunnelRuntime", "Node could not enter project directory %s: errno=%d", working_dir, error);
        env->ReleaseStringUTFChars(cwd, working_dir);
        env->ReleaseStringUTFChars(port, port_value);
        env->ReleaseStringUTFChars(app_home, home_value);
        env->ReleaseStringUTFChars(temp_directory, temp_value);
        return -error;
    }
    setenv("PORT", port_value, 1);
    setenv("HOME", home_value, 1);
    setenv("TMPDIR", temp_value, 1);
    setenv("TMP", temp_value, 1);
    setenv("TEMP", temp_value, 1);
    setenv("PWD", working_dir, 1);
    env->ReleaseStringUTFChars(cwd, working_dir);
    env->ReleaseStringUTFChars(port, port_value);
    env->ReleaseStringUTFChars(app_home, home_value);
    env->ReleaseStringUTFChars(temp_directory, temp_value);

    const jsize count = env->GetArrayLength(java_args);
    std::vector<std::string> arguments;
    std::vector<char *> argv;
    arguments.reserve(count);
    argv.reserve(count);
    for (jsize i = 0; i < count; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(java_args, i));
        const char *value = env->GetStringUTFChars(item, nullptr);
        arguments.emplace_back(value);
        env->ReleaseStringUTFChars(item, value);
        env->DeleteLocalRef(item);
    }
    for (auto &arg : arguments) argv.push_back(arg.data());
    return node::Start(static_cast<int>(argv.size()), argv.data());
}
