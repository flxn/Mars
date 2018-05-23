#!/usr/bin/env bash
echo "Compiling .java files..."
find . -name "*.java" -print0 | xargs -0 javac
echo "Bundling .jar..."
jar cmf mainclass.txt Mars.jar PseudoOps.txt Config.properties Syscall.properties Settings.properties MARSlicense.txt mainclass.txt MipsXRayOpcode.xml registerDatapath.xml controlDatapath.xml ALUcontrolDatapath.xml CreateMarsJar.bat Mars.java Mars.class docs help images mars 
echo "Deleting .class files..."
find . -name "*.class" -delete
