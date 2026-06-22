#!/usr/bin/env sh
#
# Copyright 2015 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

##############################################################################
# Gradle start up script for UN*X
##############################################################################
die () {
    echo
    echo "$*"
    echo
    exit 1
} >&2

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found."
fi

# Use the maximum available, or set MAX_FD != -1 to use that value.
MAX_FD="maximum"

# OS specific support
CYGWIN=false
MSYS=false
DARWIN=false
NONSTOP=false
case "$( uname )" in
  CYGWIN* ) CYGWIN=true ;;
  Darwin* ) DARWIN=true ;;
  MSYS* | MINGW* ) MSYS=true ;;
  NONSTOP* ) NONSTOP=true ;;
esac

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

APP_HOME=$( cd "${0%/*}/.." && pwd -P )
APP_NAME="Gradle"
APP_BASE_NAME="${0##*/}"

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# Allow to override JVM opts by environment variable
if [ -n "$JAVA_OPTS" ] ; then
    DEFAULT_JVM_OPTS="$JAVA_OPTS $DEFAULT_JVM_OPTS"
fi

APP_HOME="${APP_HOME%/}"

exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
  "-Dorg.gradle.appname=$APP_BASE_NAME" \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"
