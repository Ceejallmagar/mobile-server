#include <jni.h>
#include <node.h>
#include <cstdlib>
#include <string>
#include <vector>
#include <unistd.h>

extern "C" JNIEXPORT jint JNICALL
Java_dev_pockettunnel_NodeEngine_startNode(JNIEnv *env, jobject, jobjectArray java_args, jstring cwd, jstring port) {
    const char *working_dir = env->GetStringUTFChars(cwd, nullptr);
    const char *port_value = env->GetStringUTFChars(port, nullptr);
    if (chdir(working_dir) != 0) {
        env->ReleaseStringUTFChars(cwd, working_dir);
        env->ReleaseStringUTFChars(port, port_value);
        return -1;
    }
    setenv("PORT", port_value, 1);
    env->ReleaseStringUTFChars(cwd, working_dir);
    env->ReleaseStringUTFChars(port, port_value);

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
