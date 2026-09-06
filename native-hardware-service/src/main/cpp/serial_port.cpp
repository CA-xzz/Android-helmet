#include <jni.h>

#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <pty.h>
#include <stdlib.h>
#include <string>
#include <termios.h>
#include <unistd.h>
#include <vector>

namespace {

void throw_io_exception(JNIEnv* env, const std::string& operation) {
    const int error_number = errno;
    const std::string message = operation + ": " + std::strerror(error_number);
    jclass exception_class = env->FindClass("java/io/IOException");
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message.c_str());
    }
}

bool baud_to_speed(jint baud_rate, speed_t* speed) {
    switch (baud_rate) {
        case 9600: *speed = B9600; return true;
        case 19200: *speed = B19200; return true;
        case 38400: *speed = B38400; return true;
        case 57600: *speed = B57600; return true;
        case 115200: *speed = B115200; return true;
        case 230400: *speed = B230400; return true;
        case 460800: *speed = B460800; return true;
        case 921600: *speed = B921600; return true;
        default: return false;
    }
}

ssize_t write_fully(int file_descriptor, const void* source, size_t size) {
    const auto* bytes = static_cast<const unsigned char*>(source);
    size_t written = 0;
    while (written < size) {
        const ssize_t count = ::write(file_descriptor, bytes + written, size - written);
        if (count > 0) {
            written += static_cast<size_t>(count);
            continue;
        }
        if (count < 0 && errno == EINTR) continue;
        if (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            pollfd descriptor{file_descriptor, POLLOUT, 0};
            int poll_result;
            do {
                poll_result = poll(&descriptor, 1, 200);
            } while (poll_result < 0 && errno == EINTR);
            if (poll_result > 0 && (descriptor.revents & POLLOUT) != 0) continue;
            if (poll_result == 0) errno = ETIMEDOUT;
        }
        if (count == 0) errno = EIO;
        return -1;
    }
    return static_cast<ssize_t>(written);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_example_helmet_hardware_service_NativeSerialPort_open(
        JNIEnv* env, jobject /* thiz */, jstring device_path, jint baud_rate) {
    const char* path = env->GetStringUTFChars(device_path, nullptr);
    if (path == nullptr) return -1;

    const int file_descriptor = ::open(path, O_RDWR | O_NOCTTY | O_NONBLOCK | O_CLOEXEC);
    env->ReleaseStringUTFChars(device_path, path);
    if (file_descriptor < 0) {
        throw_io_exception(env, "open serial port");
        return -1;
    }

    termios attributes{};
    if (tcgetattr(file_descriptor, &attributes) != 0) {
        throw_io_exception(env, "tcgetattr");
        ::close(file_descriptor);
        return -1;
    }

    speed_t speed{};
    if (!baud_to_speed(baud_rate, &speed)) {
        errno = EINVAL;
        throw_io_exception(env, "unsupported baud rate");
        ::close(file_descriptor);
        return -1;
    }

    cfmakeraw(&attributes);
    attributes.c_cflag |= CLOCAL | CREAD;
    attributes.c_cflag &= ~CSTOPB;
    attributes.c_cflag &= ~CRTSCTS;
    attributes.c_cflag &= ~PARENB;
    attributes.c_cflag = (attributes.c_cflag & ~CSIZE) | CS8;
    attributes.c_cc[VMIN] = 0;
    attributes.c_cc[VTIME] = 0;
    cfsetispeed(&attributes, speed);
    cfsetospeed(&attributes, speed);
    if (tcsetattr(file_descriptor, TCSANOW, &attributes) != 0) {
        throw_io_exception(env, "tcsetattr");
        ::close(file_descriptor);
        return -1;
    }
    tcflush(file_descriptor, TCIOFLUSH);
    return file_descriptor;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_helmet_hardware_service_NativeSerialPort_read(
        JNIEnv* env, jobject /* thiz */, jint file_descriptor, jbyteArray destination,
        jint timeout_millis) {
    pollfd descriptor{file_descriptor, POLLIN, 0};
    int poll_result;
    do {
        poll_result = poll(&descriptor, 1, timeout_millis);
    } while (poll_result < 0 && errno == EINTR);
    if (poll_result < 0) {
        throw_io_exception(env, "poll serial port");
        return -1;
    }
    if (poll_result == 0) return 0;
    if ((descriptor.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) {
        errno = EIO;
        throw_io_exception(env, "serial port poll error");
        return -1;
    }

    const jsize capacity = env->GetArrayLength(destination);
    jbyte* bytes = env->GetByteArrayElements(destination, nullptr);
    if (bytes == nullptr) return -1;
    ssize_t count;
    do {
        count = ::read(file_descriptor, bytes, static_cast<size_t>(capacity));
    } while (count < 0 && errno == EINTR);
    env->ReleaseByteArrayElements(destination, bytes, count > 0 ? 0 : JNI_ABORT);
    if (count < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
        throw_io_exception(env, "read serial port");
        return -1;
    }
    return count < 0 ? 0 : static_cast<jint>(count);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_helmet_hardware_service_NativeSerialPort_write(
        JNIEnv* env, jobject /* thiz */, jint file_descriptor, jbyteArray source) {
    const jsize size = env->GetArrayLength(source);
    jbyte* bytes = env->GetByteArrayElements(source, nullptr);
    if (bytes == nullptr) return -1;

    const ssize_t written = write_fully(file_descriptor, bytes, static_cast<size_t>(size));
    env->ReleaseByteArrayElements(source, bytes, JNI_ABORT);
    if (written < 0) {
        throw_io_exception(env, "write serial port");
        return -1;
    }
    return static_cast<jint>(written);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_helmet_hardware_service_NativeSerialPort_close(
        JNIEnv* env, jobject /* thiz */, jint file_descriptor) {
    if (::close(file_descriptor) != 0) {
        throw_io_exception(env, "close serial port");
    }
}

#if HELMET_ENABLE_TEST_PTY

extern "C" JNIEXPORT jint JNICALL
Java_com_example_helmet_testfixture_AndroidTestPtyFixture_nativeOpenMaster(
        JNIEnv* env, jobject /* thiz */) {
    int master_descriptor = -1;
    int slave_descriptor = -1;
    char slave_name[128]{};
    if (openpty(&master_descriptor, &slave_descriptor, slave_name, nullptr, nullptr) != 0) {
        throw_io_exception(env, "open androidTest PTY");
        return -1;
    }

    const int descriptor_flags = fcntl(master_descriptor, F_GETFD);
    const int status_flags = fcntl(master_descriptor, F_GETFL);
    if (descriptor_flags < 0 || status_flags < 0 ||
        fcntl(master_descriptor, F_SETFD, descriptor_flags | FD_CLOEXEC) != 0 ||
        fcntl(master_descriptor, F_SETFL, status_flags | O_NONBLOCK) != 0) {
        const int saved_errno = errno;
        ::close(slave_descriptor);
        ::close(master_descriptor);
        errno = saved_errno;
        throw_io_exception(env, "configure androidTest PTY");
        return -1;
    }

    if (::close(slave_descriptor) != 0) {
        const int saved_errno = errno;
        ::close(master_descriptor);
        errno = saved_errno;
        throw_io_exception(env, "close androidTest PTY slave");
        return -1;
    }
    return master_descriptor;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_helmet_testfixture_AndroidTestPtyFixture_nativeSlavePath(
        JNIEnv* env, jobject /* thiz */, jint master_descriptor) {
    char slave_name[128]{};
    const int result = ptsname_r(master_descriptor, slave_name, sizeof(slave_name));
    if (result != 0) {
        errno = result;
        throw_io_exception(env, "resolve androidTest PTY slave");
        return nullptr;
    }
    return env->NewStringUTF(slave_name);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_helmet_testfixture_AndroidTestPtyFixture_nativeWriteMaster(
        JNIEnv* env, jobject /* thiz */, jint master_descriptor, jbyteArray source) {
    const jsize size = env->GetArrayLength(source);
    jbyte* bytes = env->GetByteArrayElements(source, nullptr);
    if (bytes == nullptr) return -1;
    const ssize_t written = write_fully(master_descriptor, bytes, static_cast<size_t>(size));
    env->ReleaseByteArrayElements(source, bytes, JNI_ABORT);
    if (written < 0) {
        throw_io_exception(env, "write androidTest PTY master");
        return -1;
    }
    return static_cast<jint>(written);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_helmet_testfixture_AndroidTestPtyFixture_nativeReadMaster(
        JNIEnv* env, jobject /* thiz */, jint master_descriptor, jint maximum_bytes,
        jint timeout_millis) {
    pollfd descriptor{master_descriptor, POLLIN, 0};
    int poll_result;
    do {
        poll_result = poll(&descriptor, 1, timeout_millis);
    } while (poll_result < 0 && errno == EINTR);
    if (poll_result < 0) {
        throw_io_exception(env, "poll androidTest PTY master");
        return nullptr;
    }
    if (poll_result == 0) return env->NewByteArray(0);
    if ((descriptor.revents & (POLLERR | POLLNVAL)) != 0) {
        errno = EIO;
        throw_io_exception(env, "androidTest PTY master poll error");
        return nullptr;
    }

    std::vector<jbyte> bytes(static_cast<size_t>(maximum_bytes));
    ssize_t count;
    do {
        count = ::read(master_descriptor, bytes.data(), static_cast<size_t>(maximum_bytes));
    } while (count < 0 && errno == EINTR);
    if (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) count = 0;
    if (count < 0) {
        throw_io_exception(env, "read androidTest PTY master");
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(count));
    if (result == nullptr) return nullptr;
    if (count > 0) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(count), bytes.data());
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_helmet_testfixture_AndroidTestPtyFixture_nativeCloseMaster(
        JNIEnv* env, jobject /* thiz */, jint master_descriptor) {
    if (::close(master_descriptor) != 0) {
        throw_io_exception(env, "close androidTest PTY master");
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_helmet_debug_HardwareSelfTestPtyFixture_nativeOpenMaster(
        JNIEnv* env, jobject thiz) {
    return Java_com_example_helmet_testfixture_AndroidTestPtyFixture_nativeOpenMaster(env, thiz);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_helmet_debug_HardwareSelfTestPtyFixture_nativeSlavePath(
        JNIEnv* env, jobject thiz, jint master_descriptor) {
    return Java_com_example_helmet_testfixture_AndroidTestPtyFixture_nativeSlavePath(
            env, thiz, master_descriptor);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_helmet_debug_HardwareSelfTestPtyFixture_nativeWriteMaster(
        JNIEnv* env, jobject thiz, jint master_descriptor, jbyteArray source) {
    return Java_com_example_helmet_testfixture_AndroidTestPtyFixture_nativeWriteMaster(
            env, thiz, master_descriptor, source);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_helmet_debug_HardwareSelfTestPtyFixture_nativeReadMaster(
        JNIEnv* env, jobject thiz, jint master_descriptor, jint maximum_bytes,
        jint timeout_millis) {
    return Java_com_example_helmet_testfixture_AndroidTestPtyFixture_nativeReadMaster(
            env, thiz, master_descriptor, maximum_bytes, timeout_millis);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_helmet_debug_HardwareSelfTestPtyFixture_nativeCloseMaster(
        JNIEnv* env, jobject thiz, jint master_descriptor) {
    Java_com_example_helmet_testfixture_AndroidTestPtyFixture_nativeCloseMaster(
            env, thiz, master_descriptor);
}

#endif
