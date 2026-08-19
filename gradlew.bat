@rem Minimal Gradle wrapper launcher. The wrapper JAR resolves Gradle 8.7 from gradle-wrapper.properties.
@echo off
setlocal
set APP_HOME=%~dp0
java -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
endlocal

