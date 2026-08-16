# APK 编译工具链环境（解包自 termux .deb，无需 root）
# 用法: source /data/data/com.coomi.android/files/home/build/env.sh
export PREFIX=/data/data/com.coomi.android/files/usr
export JAVA_HOME=$PREFIX/lib/jvm/java-17-openjdk
export LD_LIBRARY_PATH=$PREFIX/lib
export PATH=$JAVA_HOME/bin:$PREFIX/bin:$PATH
export APK_TOOLS=$PREFIX/bin   # aapt zipalign d8 r8 apksigner adb
export ANDROID_FRAMEWORK=/system/framework/framework-res.apk
