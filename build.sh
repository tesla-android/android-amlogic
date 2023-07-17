#!/bin/bash

echo Building the Android
pushd aosptree
. build/envsetup.sh
lunch tesla_android_radxa_zero-userdebug
make images -k || make images -j1
make sdcard
make otapackage
popd
