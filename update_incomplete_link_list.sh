#!/usr/bin/env bash
set -e
JAVA_OPTS="-Xms1512M -Xmx4096m -Xss1M -XX:+CMSClassUnloadingEnabled -XX:MaxPermSize=1024M -XX:+HeapDumpOnOutOfMemoryError"
java $JAVA_OPTS -jar `dirname $0`/sbt-launch.jar ${1} 'project digiroad2' "runMain fi.liikennevirasto.digiroad2.util.UpdateIncompleteLinkList"
