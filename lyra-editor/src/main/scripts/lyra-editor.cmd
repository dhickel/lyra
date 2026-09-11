@echo off
setlocal
set "LYRA_EDITOR_JAVA=java"
if defined JAVA_HOME set "LYRA_EDITOR_JAVA=%JAVA_HOME%\bin\java.exe"
"%LYRA_EDITOR_JAVA%" --enable-preview --add-modules=jdk.jdi --enable-native-access=ALL-UNNAMED -cp "%~dp0*;%~dp0lib\*" io.mindspice.lyra.editor.EditorLauncher %*
