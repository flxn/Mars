#!/usr/bin/env bash
rm -rf MARS.app
mkdir -p MARS.app/Contents/MacOS
mkdir -p MARS.app/Contents/Resources
cp Info.plist MARS.app/Contents/Info.plist
cp macos_launcher MARS.app/Contents/MacOS/launcher
chmod +x MARS.app/Contents/MacOS/launcher
cp images/MARS.icns MARS.app/Contents/Resources/MARS.icns
./CreateMarsJar.sh
cp Mars.jar MARS.app/Contents/MacOS/Mars.jar
