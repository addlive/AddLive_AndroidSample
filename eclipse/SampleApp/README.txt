# update project settings

>>$ ${ANDROID_SDK_ROOT}/tools/android update project -p . -t android-17 -n AddLiveDemo

# compile

>>$ ant debug

# install on device

>>$ adb install -r ./bin/AddLiveDemo-debug.apk

# start on device

>>$ adb shell am start -n com.addlive.sampleapp/com.addlive.sampleapp.AddLiveSampleApp
